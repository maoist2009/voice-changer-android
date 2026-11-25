package org.voice.changer.hook

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A hooked stream inside the target process.
 *
 * The audio thread only moves samples in and out of two queues; a worker
 * performs the binder round trip. The queues never drop samples and never hand
 * the original audio back: a stream that is engaged stays converted for its
 * whole life, so when the engine is behind, the target's audio thread waits.
 * Waiting is the only failure mode left, and it is counted.
 *
 * Waiting alone would deadlock at stream start: the engine needs a look-ahead
 * of capture before it can emit its first block, and that capture can only come
 * from the target's own `read`. [HookAudio] solves it by reading ahead itself
 * (see `preRoll`), which is why this class exposes [enqueue] and [await]
 * separately instead of one blocking [exchange] call.
 */
class HookTap(
    private val pkg: String,
    private val process: String,
    private val rate: Int,
    private val channels: Int,
) {
    private val send = FloatFifo(rate * 8) // capture waiting for the engine
    private val recv = FloatFifo(rate * 6) // converted audio waiting for the app
    private val sendLock = Object()
    private val recvLock = Object()

    @Volatile private var running = true

    @Volatile var lastAt = System.currentTimeMillis()
        private set

    private val inFrames = AtomicLong(0)
    private val outFrames = AtomicLong(0)
    /** Capture handed to the app side during the current stream. */
    private val fedFrames = AtomicLong(0)
    private val stalls = AtomicInteger(0)
    private val stallMs = AtomicLong(0)

    /** Must stay zero: a non-zero value means raw audio reached the app. */
    private val rawBlocks = AtomicLong(0)
    private val reported = AtomicBoolean(false)

    // One engine stream per process: two processes sharing a stream would
    // hear each other's audio.
    private val key = "$pkg#$process|$rate|$channels"

    private val worker = Thread(::loop, "beatrice-tap").also {
        it.isDaemon = true
        // This thread is what feeds the target's audio callback; it must not be
        // starved by the app's own work.
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
        it.start()
    }

    /** Hands capture to the engine. Never drops: a full queue makes it wait. */
    fun enqueue(mono: FloatArray) {
        lastAt = System.currentTimeMillis()
        lastFeedAt = lastAt
        inFrames.addAndGet(mono.size.toLong())
        fedFrames.addAndGet(mono.size.toLong())
        synchronized(sendLock) {
            while (running && send.free < mono.size) sendLock.wait(20)
            if (!running) return
            send.write(mono)
            sendLock.notifyAll()
        }
    }

    fun hasOutput(n: Int): Boolean = synchronized(recvLock) { recv.size >= n }

    /**
     * True while a read is waiting for converted audio. The worker only asks
     * the app for pending output in that case, so an idle stream costs nothing.
     */
    @Volatile private var waiting = false

    /** Set once a read has been served: priming is a stream-start concern only. */
    @Volatile var primed = false
        private set

    /**
     * Capture this stream must read ahead (frames at [rate]) before the engine
     * can emit its first block, as reported by the app side. Zero means the
     * engine has not been built yet.
     */
    @Volatile var needFrames = 0
        private set

    /**
     * Wall-clock budget the backend needs before its first block can exist, as
     * reported by the app side. A stream waits this long before deciding that
     * the engine wants more capture; a fixed short wait made the hook re-read a
     * whole read-ahead every time and left that surplus in the stream forever.
     */
    @Volatile var firstBlockMs = DEFAULT_FIRST_MS
        private set

    /** Last time this stream fed capture; a long gap means a new stream. */
    private var lastFeedAt = 0L

    /**
     * Asks the app side once per stream; the engine's demand is stable. A gap in
     * the feed means the previous stream ended (its session closed), so the
     * priming state has to be forgotten or the next stream would start without
     * any read-ahead and deadlock.
     */
    fun fetchNeed() {
        val now = System.currentTimeMillis()
        if (now - lastFeedAt > STREAM_GAP_MS) {
            primed = false
            needFrames = 0
            fedFrames.set(0)
        }
        if (needFrames > 0 || primed) return
        val (frames, firstMs) = HookRemote.need(key, pkg, rate, channels)
        needFrames = frames
        if (firstMs > 0) firstBlockMs = firstMs
    }

    /**
     * Capture this stream still has to read ahead (frames at [rate]) to satisfy
     * the engine's start-up context. Zero when the app side has not reported a
     * demand yet.
     */
    fun preRollTarget(): Int {
        if (needFrames <= 0) return 0
        return (needFrames - fedFrames.get()).coerceAtLeast(0L).toInt()
    }

    /**
     * Waits for [n] converted frames. Returns null only when the stream is
     * being torn down, which the caller counts as a defect.
     */
    fun await(n: Int): FloatArray? {
        val t0 = System.nanoTime()
        waiting = true
        val out = synchronized(recvLock) {
            var ready = recv.read(n)
            while (running && ready == null) {
                // A blocked consumer is an active stream: keep the pruner away.
                lastAt = System.currentTimeMillis()
                recvLock.wait(20)
                ready = recv.read(n)
            }
            ready
        }
        waiting = false
        if (out == null) {
            rawBlocks.incrementAndGet()
            return null
        }
        val waited = (System.nanoTime() - t0) / 1_000_000
        if (waited >= 1) {
            stalls.incrementAndGet()
            stallMs.addAndGet(waited)
        }
        lastAt = System.currentTimeMillis()
        outFrames.addAndGet(out.size.toLong())
        primed = true
        return out
    }

    /**
     * Like [await] but gives up after [ms]. Null means "nothing yet"; the caller
     * distinguishes it from a closed stream by [primed]/[hasOutput].
     */
    fun tryAwait(n: Int, ms: Long): FloatArray? {
        val deadline = System.nanoTime() + ms * 1_000_000
        waiting = true
        try {
            synchronized(recvLock) {
                var ready = recv.read(n)
                while (running && ready == null && System.nanoTime() < deadline) {
                    lastAt = System.currentTimeMillis()
                    recvLock.wait(5)
                    ready = recv.read(n)
                }
                if (ready == null) return null
                lastAt = System.currentTimeMillis()
                outFrames.addAndGet(ready.size.toLong())
                primed = true
                return ready
            }
        } finally {
            waiting = false
        }
    }

    fun close() {
        running = false
        synchronized(sendLock) { sendLock.notifyAll() }
        synchronized(recvLock) { recvLock.notifyAll() }
    }

    private fun loop() {
        var pending: FloatArray? = null
        while (running) {
            val chunk = pending ?: synchronized(sendLock) {
                val taken = send.take(MAX_CHUNK)
                if (taken.isNotEmpty()) sendLock.notifyAll()
                taken
            }
            pending = null
            if (chunk.isEmpty()) {
                // Nothing to feed, but the engine may still owe converted audio
                // and a read may be blocked waiting for it: pull it now. (Only
                // the arrival of new capture used to move audio, so a waiting
                // read could sit on output that was already converted.)
                if (waiting) {
                    val late = HookRemote.drain(key)
                    if (late != null && late.isNotEmpty()) {
                        push(late)
                        continue
                    }
                }
                idleReport()
                Thread.sleep(2)
                continue
            }
            val out = HookRemote.process(key, pkg, rate, channels, chunk)
            if (out == null) {
                // The app side did not take this audio (dead, restarting, engine
                // busy). Keep the samples and try again: dropping them would cut
                // content out of the recording, and passing them through would
                // put raw audio inside it.
                pending = chunk
                Thread.sleep(20)
                continue
            }
            if (out.isEmpty()) {
                // Accepted, nothing converted yet: keep feeding so the engine
                // can reach its look-ahead. (Waiting here instead would deadlock
                // — this thread is the only one that can supply that input.)
                continue
            }
            push(out)
        }
    }

    /** Hands converted audio to the audio thread. Never trims: a full queue waits. */
    private fun push(out: FloatArray) {
        synchronized(recvLock) {
            while (running && recv.free < out.size) recvLock.wait(20)
            if (running) {
                recv.write(out)
                recvLock.notifyAll()
            }
        }
    }

    /** One line per idle period: the numbers that prove a stream was converted. */
    private fun idleReport() {
        if (System.currentTimeMillis() - lastAt < 3_000) return
        if (!reported.compareAndSet(false, true)) return
        val sendQueued = synchronized(sendLock) { send.size }
        val recvQueued = synchronized(recvLock) { recv.size }
        android.util.Log.i(
            "hook_session",
            "summary $key in=${inFrames.get()} out=${outFrames.get()} stalls=${stalls.get()} " +
                "stallMs=${stallMs.get()} raw=${rawBlocks.get()} send=$sendQueued recv=$recvQueued",
        )
    }

    private companion object {
        const val MAX_CHUNK = 4_000
        /** Silence longer than this ends a stream. */
        const val STREAM_GAP_MS = 1_500L
        /** Used until the app side reports the backend's real budget. */
        const val DEFAULT_FIRST_MS = 900
    }
}

/** One tap per (package, format); streams die with the process. */
object HookTaps {
    private val taps = ConcurrentHashMap<String, HookTap>()

    fun get(pkg: String, process: String, rate: Int, channels: Int): HookTap {
        val key = "$pkg#$process|$rate|$channels"
        return taps.getOrPut(key) { HookTap(pkg, process, rate, channels) }
    }
}
