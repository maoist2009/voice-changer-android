package org.voice.changer.hook

import android.content.Context
import android.util.Log
import org.voice.changer.audio.NoiseGate
import org.voice.changer.engine.EngineHub
import org.voice.changer.engine.EngineParams
import org.voice.changer.engine.LiveProcessor
import org.voice.changer.engine.PcmSource
import kotlin.math.max

/**
 * What the UI shows for a live hooked stream.
 *
 * [lookaheadMs] is how much input the backend still needs before it can emit
 * the next block; [queueMs] is converted audio already waiting to be handed
 * back to the target app. Their sum is the delay this session adds on top of
 * the target's own capture/playback buffering.
 */
data class HookStats(
    val lookaheadMs: Int,
    val queueMs: Int,
    val inferMs: Double,
    /** 16 kHz samples of capture dropped when the engine took over. */
    val baseSamples: Long = 0L,
    /** Measured capture-to-hand-over delay of the last served buffer. */
    val measuredMs: Long = 0L,
) {
    val totalMs: Int get() = if (measuredMs > 0) measuredMs.toInt() else lookaheadMs + queueMs
}

/**
 * One hooked audio stream: a target app's microphone at a fixed format,
 * converted by the resident engine.
 *
 * The target app calls [exchange] on its audio thread, so that call has to
 * stay cheap: it only resamples into the 16 kHz ring and pops whatever the
 * worker already produced. Everything expensive (engine init, inference,
 * output resampling) runs on this session's own thread.
 */
class HookSession(
    val key: String,
    val pkg: String,
    val rate: Int,
    val channels: Int,
    private val context: Context,
) {
    companion object {
        private const val TAG = "hook_session"
        private const val RING_SECONDS = 4
        /**
         * Converted audio waiting to be handed back. Large on purpose: the
         * queue is allowed to grow while the engine catches up, it is never
         * trimmed, and a recording must not lose content to a burst.
         */
        private const val OUTPUT_SECONDS = 6.0

        private val EMPTY = FloatArray(0)
    }

    private val inResampler = StreamResampler(rate, 16_000)
    /** Input gate, applied to the 16 kHz capture before it reaches the ring. */
    private val gate = NoiseGate(16_000)
    @Volatile private var gateDb = -50f
    private var outResampler: StreamResampler? = null

    private val inLock = Object()
    private val ring = FloatArray(16_000 * RING_SECONDS)
    private var received = 0L

    /** End-to-end delay of the last served buffer: capture time -> read time. */
    @Volatile var lastLatencyMs = 0L
        private set

    // Counters: the numbers that prove a stream was converted end to end.
    private val inFrames = java.util.concurrent.atomic.AtomicLong(0)
    private val outFrames = java.util.concurrent.atomic.AtomicLong(0)
    private val stalls = java.util.concurrent.atomic.AtomicInteger(0)
    private val stallMs = java.util.concurrent.atomic.AtomicLong(0)
    /** Must stay zero: a non-zero value means raw audio reached the target. */
    private val rawBlocks = java.util.concurrent.atomic.AtomicLong(0)
    private val inputResets = java.util.concurrent.atomic.AtomicLong(0)

    private val outLock = Object()
    private val fifo = FloatFifo((rate * OUTPUT_SECONDS).toInt().coerceAtLeast(rate / 2))

    /**
     * Converted audio waiting to be served. Bigger than one backend block: the
     * engine emits in bursts, and the queue has to absorb the gap between two.
     */

    @Volatile private var running = true
    @Volatile private var engine: LiveProcessor? = null
    @Volatile private var pendingParams: EngineParams? = null

    @Volatile var lastUse = System.currentTimeMillis()
        private set

    @Volatile var delivered = 0L
        private set

    @Volatile var status: String = "starting"
        private set

    @Volatile var lastInferMs: Double = 0.0
        private set

    /** Capture samples dropped when the engine took over (16 kHz). */
    @Volatile private var inputBase = 0L

    /**
     * Input index (16 kHz) that output sample 0 of the current processor
     * corresponds to. The processor numbers its own output from zero, while the
     * ring keeps counting capture, so the two have to be related to time the
     * real delay.
     */
    @Volatile private var latencyBase = 0L

    init {
        Log.d(TAG, "session ${System.identityHashCode(this)} for $key")
    }

    private val worker = Thread(::loop, "hook-${pkg.takeLast(12)}-${rate}").also {
        it.isDaemon = true
        // Inference runs here: give it the audio priority so a busy app or a
        // background cgroup cannot starve the engine below realtime.
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        it.start()
    }

    fun isActive(): Boolean = System.currentTimeMillis() - lastUse < 3_000

    /** Cheap snapshot for the UI: no locks on the audio path, one read per second. */
    fun stats(): HookStats {
        val queued = synchronized(outLock) { fifo.size }
        val pending = engine?.let { it.requiredSamples - it.outputSamples } ?: 0L
        return HookStats(
            lookaheadMs = (pending.coerceAtLeast(0L) * 1000 / 16_000).toInt(),
            queueMs = queued * 1000 / rate.coerceAtLeast(1),
            inferMs = lastInferMs,
            baseSamples = inputBase,
            measuredMs = lastLatencyMs,
        )
    }

    fun setParams(params: EngineParams) {
        pendingParams = params
        gateDb = params.noiseGateDb
    }

    /** Capture samples this stream starts from; the debug harness times with it. */
    fun baseSamples(): Long = inputBase

    /**
     * Feeds one capture buffer and returns whatever converted audio is ready,
     * up to [pcm] frames.
     *
     * This never waits for output on purpose. The caller is the only thread
     * that can feed the engine, so waiting here for audio that requires more
     * input would deadlock — it did, and it stalled every recording. Blocking
     * belongs on the target's audio thread (see [HookTap.await]), where waiting
     * costs nothing but delay and no sample can be lost.
     */
    fun exchange(pcm: FloatArray): FloatArray {
        lastUse = System.currentTimeMillis()
        HookBridge.touch(key)
        inFrames.addAndGet(pcm.size.toLong())
        val mono16 = inResampler.process(pcm)
        if (mono16.isNotEmpty()) {
            gate.setThresholdDb(gateDb)
            gate.process(mono16)
            synchronized(inLock) {
                for (v in mono16) {
                    val slot = (received % ring.size).toInt()
                    ring[slot] = v
                    received++
                }
                inLock.notifyAll()
            }
        }
        if (!running) return EMPTY
        return serve(pcm.size)
    }

    /**
     * Hands over converted audio already queued, without feeding anything. A
     * waiting read uses this when the target sends no new capture: the engine
     * may still owe audio that was fed earlier.
     */
    fun drain(max: Int): FloatArray {
        if (!running) return EMPTY
        return serve(max)
    }

    private fun serve(max: Int): FloatArray {
        val out = synchronized(outLock) { fifo.take(max) }
        if (out.isEmpty()) return out
        outFrames.addAndGet(out.size.toLong())
        // Real delay, measured on monotonic counters instead of timestamps:
        // how much capture has been fed but not yet handed back. Counters wrap
        // nothing, while ring slots wrap every few seconds.
        val served16 = latencyBase + delivered * 16_000 / rate
        val behind = (received - served16).coerceAtLeast(0L)
        lastLatencyMs = behind * 1000 / 16_000
        delivered += out.size
        return out
    }

    fun close() {
        running = false
        synchronized(inLock) { inLock.notifyAll() }
        synchronized(outLock) { outLock.notifyAll() }
        runCatching { worker.join(700) }
        Log.i(
            TAG,
            "summary $key in=${inFrames.get()} out=${outFrames.get()} stalls=${stalls.get()} " +
                "stallMs=${stallMs.get()} raw=${rawBlocks.get()} resets=${inputResets.get()} " +
                "infer=${"%.1f".format(lastInferMs)}ms rtf=${"%.2f".format(lastInferMs / 10.0)}",
        )
    }

    // ------------------------------------------------------------- worker

    private fun loop() {
        Log.d(TAG, "worker ${Thread.currentThread().id} up for $key")
        while (running) {
            if (!HookBridge.isOwner(key) && !HookBridge.claim(key)) {
                // Another hooked app owns the stateful engine; wait until it
                // goes quiet and HookBridge hands it over.
                engine = null
                Thread.sleep(50)
                continue
            }
            val processor = ensureEngine()
            if (processor == null) {
                status = "engine unavailable"
                Thread.sleep(250)
                continue
            }
            pendingParams?.let {
                pendingParams = null
                runCatching { processor.setParams(it) }
            }

            val start: Int
            val end: Int
            val snapshot: FloatArray
            synchronized(inLock) {
                while (running && received < processor.requiredSamples) inLock.wait(20)
                if (!running) return
                if (received < processor.requiredSamples) continue
                if (received - processor.outputSamples > ring.size * 3L / 4) {
                    // More than a ring behind: the queued input is already
                    // stale. Resetting loses capture, so it is counted as a
                    // defect rather than being treated as normal operation.
                    inputResets.incrementAndGet()
                    Log.w(TAG, "input ring overflow, resetting ($key)")
                    processor.reset()
                    latencyBase = received
                    synchronized(outLock) { fifo.clear(); delivered = 0 }
                    continue
                }
                start = max(0, (processor.outputSamples - 560).toInt())
                end = processor.requiredSamples
                snapshot = FloatArray(end - start)
                for (i in start until end) {
                    snapshot[i - start] = ring[(((i % ring.size) + ring.size) % ring.size)]
                }
            }
            status = "running"
            val source = PcmSource { i -> if (i < start || i >= end) 0f else snapshot[i - start] }
            try {
                val t0 = System.nanoTime()
                val part = processor.next(source)
                lastInferMs = (System.nanoTime() - t0) / 1_000_000.0
                if (part.audio.isEmpty()) continue
                val resampler = outResampler ?: continue
                val out = resampler.process(part.audio)
                // Latency breakdown: what the model still needs before it can
                // emit (lookahead), what is waiting in the output queue, and how
                // long one inference took.
                val lookMs = ((processor.requiredSamples - processor.outputSamples)
                    .coerceAtLeast(0L) * 1000 / 16_000).toInt()
                // `lag` is capture queued but not converted yet; `done` is how
                // much audio the engine has consumed since the stream started.
                // Together with the log timestamp they say whether the engine
                // keeps up with realtime.
                val lagMs = ((received - processor.outputSamples).coerceAtLeast(0L) * 1000 / 16_000).toInt()
                Log.d(TAG, "block in=${part.audio.size} out=${out.size} look=${lookMs}ms " +
                    "lag=${lagMs}ms done=${processor.outputSamples * 1000 / 16_000}ms " +
                    "fifo=${fifo.size * 1000 / rate}ms lat=${lastLatencyMs}ms " +
                    "infer=${"%.1f".format(lastInferMs)}ms")
                if (out.isNotEmpty()) {
                    synchronized(outLock) {
                        // Never trim: if the consumer is behind, wait for it.
                        while (running && fifo.free < out.size) outLock.wait(20)
                        if (running) {
                            fifo.write(out)
                            outLock.notifyAll()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "conversion failed, restarting: ${t.message}")
                status = "error: ${t.message}"
                runCatching { processor.reset() }
                synchronized(inLock) {
                    // Same rule as the engine hand-over: never zero the capture
                    // counter while the producer may be waiting for output.
                    latencyBase = received
                }
                synchronized(outLock) { fifo.clear(); delivered = 0 }
                Thread.sleep(100)
            }
        }
    }

    /** Loads the resident engine and takes ownership; null while unavailable. */
    private fun ensureEngine(): LiveProcessor? {
        val current = EngineHub.processor
        if (current != null && current === engine) return current
        if (!HookBridge.claim(key)) return null
        // Re-check after the hand-over: the processor may have been rebuilt.
        val now = EngineHub.processor
        if (now != null && now === engine) return now
        val built = try {
            val params = HookSettings.engineParams(context)
            gateDb = params.noiseGateDb
            EngineHub.ensureDefault(context, params).let { EngineHub.processor }
        } catch (t: Throwable) {
            Log.w(TAG, "no engine: ${t.message}")
            return null
        } ?: return null
        runCatching { built.setParams(HookSettings.engineParams(context)) }
        EngineHub.warmup(built)
        outResampler = StreamResampler(built.outputRate, rate)
        synchronized(inLock) {
            // The capture queued while the engine loaded is converted, not
            // dropped. Zeroing the counter here told the worker that no input
            // existed, while the only thread that could supply more was already
            // waiting for output — that deadlocked the stream.
            // No counter reset: output sample zero is input sample zero.
            latencyBase = 0
            if (received > ring.size) {
                // The ring wrapped, so its index zero is no longer the start of
                // this stream; the oldest capture is gone either way.
                inputResets.incrementAndGet()
            }
            inputBase = 0
        }
        synchronized(outLock) { fifo.clear(); delivered = 0 }
        engine = built
        Log.i(TAG, "engine for $key: ${built.javaClass.simpleName} @${built.outputRate} Hz -> $rate Hz")
        return built
    }
}
