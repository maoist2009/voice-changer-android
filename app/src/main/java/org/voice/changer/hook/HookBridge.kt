package org.voice.changer.hook

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.voice.changer.LiveService
import org.voice.changer.engine.EngineHub
import java.util.concurrent.ConcurrentHashMap

/**
 * App-side end of the hook bridge.
 *
 * A hooked target app talks to us through [HookProvider] (a binder call into
 * this process), which is the only way to reach the engine: the RVC NPU
 * contexts are ~220 MB and must not be duplicated into every hooked process,
 * so the hook itself only moves audio and all conversion happens here.
 *
 * Only one stream can drive the resident engine at a time — the processor is
 * stateful — so sessions claim ownership; an idle owner is stolen after
 * [OWNER_TIMEOUT_MS].
 */
object HookBridge {
    private const val TAG = "hook_bridge"
    private const val OWNER_TIMEOUT_MS = 2_000L
    private const val SESSION_IDLE_MS = 8_000L
    /** Cap on audio returned by one drain request (frames at the target rate). */
    private const val DRAIN_MAX = 24_000

    private val sessions = ConcurrentHashMap<String, HookSession>()
    private val counts = ConcurrentHashMap<String, Long>()
    private val ownerLock = Any()

    @Volatile private var appContext: Context? = null
    private var owner: String? = null
    private var ownerAt = 0L
    private var pruner: Thread? = null

    /** Kept while a stream is converting: a screen-off recording still needs CPU. */
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wakeAt = 0L

    /** Warm-up runs once; a failure may be retried after a pause. */
    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var warmAt = 0L
    private const val WARM_RETRY_MS = 30_000L

    /** Last stream the UI should show as "hooked". */
    @Volatile var lastPkg: String? = null
        private set

    @Volatile var lastAt = 0L
        private set

    /** Capture samples the current stream starts from (debug timing aid). */
    @Volatile var lastBaseSamples = 0L
        private set

    val delivered: Long get() = counts.values.sum()

    fun deliveredFor(pkg: String): Long = counts[pkg] ?: 0L

    fun attach(context: Context) {
        appContext = context.applicationContext
        if (pruner == null) {
            pruner = Thread(::pruneLoop, "hook-pruner").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /**
     * Loads the resident engine ahead of the first capture buffer.
     *
     * Model load plus the first NPU calls take seconds, and the hook only sees
     * audio long after the target app has started, so paying that cost when
     * this process starts is what makes a recording convert from its first
     * block instead of losing the first seconds to raw audio.
     */
    fun warm() {
        val ctx = appContext ?: return
        if (!HookSettings.enabled(ctx) || LiveService.micActive) return
        if (EngineHub.processor != null) return
        val now = SystemClock.elapsedRealtime()
        if (now - warmAt < WARM_RETRY_MS) return
        if (!warming.compareAndSet(false, true)) return
        warmAt = now
        Thread {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val params = HookSettings.engineParams(ctx)
                EngineHub.ensureDefault(ctx, params).let { EngineHub.processor }
                    ?.let { EngineHub.warmup(it) }
                Log.i(TAG, "engine warm in ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (t: Throwable) {
                Log.w(TAG, "warm-up failed: ${t.message}")
            } finally {
                warming.set(false)
            }
        }.apply {
            isDaemon = true
            name = "hook-warm"
        }.start()
    }

    /** Drops every stream; called when the hosting service goes away. */
    fun detach() {
        owner = null
        val stale = sessions.values.toList()
        sessions.clear()
        stale.forEach { runCatching { it.close() } }
    }

    fun isActive(): Boolean = sessions.values.any { it.isActive() }

    /** Latency of the stream that currently owns the engine, or null when idle. */
    fun stats(): HookStats? {
        val key = synchronized(ownerLock) { owner }
            ?: sessions.keys.firstOrNull()
            ?: return null
        return sessions[key]?.stats()
    }

    /** Pushed when the user moves a slider while a stream is hooked. */
    fun setParams(params: org.voice.changer.engine.EngineParams) {
        sessions.values.forEach { it.setParams(params) }
    }

    fun config(): Bundle {
        val ctx = appContext
        val bundle = Bundle()
        if (ctx == null) {
            bundle.putBoolean("enabled", false)
            return bundle
        }
        val enabled = HookSettings.enabled(ctx) && !LiveService.micActive
        bundle.putBoolean("enabled", enabled)
        bundle.putBoolean("all", HookSettings.allApps(ctx))
        bundle.putStringArrayList("targets", ArrayList(HookSettings.targets(ctx)))
        return bundle
    }

    /**
     * Screen-off recording: the audio HAL keeps capture running, but the
     * inference thread still needs the CPU to stay awake. Renewed by every
     * process call, with a hard timeout so a crash cannot leak it.
     */
    private fun holdWake(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - wakeAt < 60_000) return
        // A missing permission must never break the audio path: the wake lock
        // is an optimisation, not a precondition. (It cost a stalled recording
        // once already.)
        runCatching { holdWakeLock(ctx, now) }
            .onFailure { Log.w(TAG, "wake lock unavailable: ${it.message}") }
    }

    private fun holdWakeLock(ctx: Context, now: Long) {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return
        val lock = synchronized(ownerLock) {
            wakeLock ?: pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "beatrice:hook",
            ).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        }
        if (!lock.isHeld) lock.acquire(10 * 60_000L)
        wakeAt = now
    }

    private fun dropWake() {
        synchronized(ownerLock) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeAt = 0L
        }
    }

    /** Binder entry point: one capture buffer in, one converted buffer out. */
    fun processCall(extras: Bundle?): Bundle {
        val ctx = appContext ?: return Bundle()
        if (extras == null) return Bundle()
        holdWake(ctx)

        val key = extras.getString("key") ?: return Bundle()
        val pcm = extras.getFloatArray("pcm") ?: return Bundle()
        val existing = sessions[key]
        if (pcm.isEmpty()) {
            // Drain request: the target is waiting and has no new capture in
            // hand, so hand over whatever conversion already finished.
            if (existing == null) return Bundle()
            val late = existing.drain(DRAIN_MAX)
            if (late.isNotEmpty()) counts.merge(existing.pkg, late.size.toLong(), Long::plus)
            return Bundle().apply { putFloatArray("pcm", late) }
        }
        val pkg = extras.getString("pkg") ?: return Bundle()
        val rate = extras.getInt("rate")
        val channels = extras.getInt("channels", 1)
        if (rate <= 0) return Bundle()

        val session = existing ?: engage(key, pkg, rate, channels, ctx) ?: return Bundle()
        lastBaseSamples = session.baseSamples()
        val out = session.exchange(pcm)
        lastPkg = pkg
        lastAt = System.currentTimeMillis()
        if (out.isNotEmpty()) counts.merge(pkg, out.size.toLong(), Long::plus)
        // Answer every accepted buffer: an empty array means "accepted, nothing
        // converted yet", which the hook must not confuse with "not accepted".
        return Bundle().apply { putFloatArray("pcm", out) }
    }

    /**
     * Engage decision, made once per stream and never revisited: a stream that
     * is converted stays converted, and one that is not stays untouched for its
     * whole life. Disabling the hook therefore affects the next recording, not
     * the one in progress.
     */
    private fun engage(key: String, pkg: String, rate: Int, channels: Int, ctx: Context): HookSession? {
        if (LiveService.micActive || !HookSettings.enabled(ctx)) return null
        val fresh = HookSession(key, pkg, rate, channels, ctx)
        val raced = sessions.putIfAbsent(key, fresh)
        if (raced != null) {
            fresh.close()
            return raced
        }
        Log.i(TAG, "session $key opened")
        return fresh
    }

    /**
     * How much capture the engine still needs before it can emit, in frames at
     * the target rate. The hook asks before it reads ahead: a fixed amount
     * either deadlocks (too little) or becomes permanent delay (too much).
     */
    fun needCall(extras: Bundle?): Bundle {
        val ctx = appContext ?: return Bundle()
        if (extras == null) return Bundle()
        holdWake(ctx)
        val key = extras.getString("key") ?: return Bundle()
        val rate = extras.getInt("rate")
        if (rate <= 0) return Bundle()
        val session = sessions[key] ?: run {
            val pkg = extras.getString("pkg") ?: return Bundle()
            val channels = extras.getInt("channels", 1)
            engage(key, pkg, rate, channels, ctx) ?: return Bundle()
        }
        return Bundle().apply { putInt("need", session.needWithin(300)) }
    }

    fun reportCall(extras: Bundle?) {
        val pkg = extras?.getString("pkg") ?: return
        val frames = extras.getLong("frames", 0L)
        if (frames > 0) counts.merge(pkg, frames, Long::plus)
        lastPkg = pkg
        lastAt = System.currentTimeMillis()
    }

    // -------------------------------------------------------- ownership

    fun isOwner(key: String): Boolean = synchronized(ownerLock) { owner == key }

    /**
     * Claims the engine for [key]. A different stream may only take over once
     * the current owner has stopped sending audio, and the old owner is shut
     * down first so two sessions never drive the stateful processor at once.
     */
    fun claim(key: String): Boolean {
        val retired: HookSession?
        synchronized(ownerLock) {
            val now = SystemClock.elapsedRealtime()
            if (owner == key) {
                ownerAt = now
                return true
            }
            if (owner != null && now - ownerAt < OWNER_TIMEOUT_MS) return false
            val old = owner
            owner = key
            ownerAt = now
            retired = if (old != null) sessions.remove(old) else null
            if (old != null) Log.i(TAG, "engine handed from $old to $key")
        }
        retired?.let { runCatching { it.close() } }
        return true
    }

    fun touch(key: String) {
        synchronized(ownerLock) { if (owner == key) ownerAt = SystemClock.elapsedRealtime() }
    }

    fun release(key: String) {
        synchronized(ownerLock) { if (owner == key) owner = null }
    }

    private fun pruneLoop() {
        while (true) {
            try {
                Thread.sleep(2_000)
                val now = System.currentTimeMillis()
                if (sessions.isEmpty() && wakeAt > 0) dropWake()
                for ((key, session) in sessions) {
                    if (now - session.lastUse > SESSION_IDLE_MS) {
                        sessions.remove(key)
                        session.close()
                        release(key)
                        Log.i(TAG, "session $key idle, closed")
                    }
                }
            } catch (t: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.w(TAG, "prune failed", t)
            }
        }
    }
}
