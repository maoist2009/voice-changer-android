package org.voice.changer.hook

import android.media.AudioFormat
import android.media.AudioRecord
import de.robv.android.xposed.XC_MethodHook
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites `AudioRecord.read` results in place.
 *
 * Every overload (byte[], short[], float[], ByteBuffer) is handled, and the
 * channel layout is respected: the buffer is downmixed to mono for the engine
 * and the result is copied back to every channel, which is what a voice call
 * path expects.
 */
object HookAudio {
    /**
     * Key for the direct buffer's position *before* the read. Recorded in
     * `beforeHookedMethod` because `AudioRecord.read(ByteBuffer, ...)` does not
     * reliably advance the position by the bytes read (AOSP still carries a
     * "FIXME: consider implementing position advancement in native code"), so
     * `position - read` cannot be used to find where the samples start.
     */
    const val BB_START = "bbStart"

    /** First few calls per process: enough to see where the path stops. */
    private val traces = java.util.concurrent.atomic.AtomicInteger(0)
    /** First few successful conversions: proof the buffer is actually rewritten. */
    private val converts = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * What the hook itself costs per call, as opposed to what the pipeline
     * delays. `cpu` is downmix + write-back on the target's audio thread,
     * `block` is the time spent waiting for converted audio.
     */
    private val calls = java.util.concurrent.atomic.AtomicInteger(0)
    private val cpuNs = java.util.concurrent.atomic.AtomicLong(0)
    private val blockNs = java.util.concurrent.atomic.AtomicLong(0)

    private fun trace(text: String) = android.util.Log.d("beatrice-hook", text)

    /**
     * Set while the hook itself is reading ahead. `read` is the only source of
     * capture, so the engine's look-ahead can only be filled by calling `read`
     * again on the same thread; that recursive call must not be intercepted or
     * it would wait for output that cannot exist yet.
     */
    private val preRolling = ThreadLocal<Boolean>()

    /** Reads ahead at most this much capture (the engine's own look-ahead is 262 ms). */
    private const val PREROLL_MS = 500L
    private const val PREROLL_TIMEOUT_MS = 5_000L
    /** How long a primed stream waits before reading ahead once more. */
    private const val PREROLL_WAIT_MS = 250L
    private const val PREROLL_RETRIES = 6

    fun intercept(param: XC_MethodHook.MethodHookParam, pkg: String, process: String) {
        if (preRolling.get() == true) return
        val tracing = traces.getAndIncrement() < 6
        val read = param.result as? Int ?: return
        if (read <= 0) {
            if (tracing) trace("skip: read=$read")
            return
        }
        val record = param.thisObject as? AudioRecord ?: return
        val args = param.args ?: return
        if (args.isEmpty()) return

        val rate = runCatching { record.sampleRate }.getOrDefault(0)
        val channels = runCatching { record.channelCount }.getOrDefault(0)
        if (rate <= 0 || channels <= 0) {
            if (tracing) trace("skip: rate=$rate ch=$channels")
            return
        }
        val encoding = runCatching { record.audioFormat }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
        val start = param.getObjectExtra(BB_START) as? Int
        if (tracing) {
            trace(
                "hit: read=$read rate=$rate ch=$channels enc=$encoding " +
                    "buf=${args[0]?.javaClass?.simpleName} off=${args.getOrNull(1)} start=$start " +
                    "pos=${(args[0] as? java.nio.ByteBuffer)?.position()}"
            )
        }

        val t0 = System.nanoTime()
        val mono = readMono(args[0], args, read, channels, encoding, start)
        val t1 = System.nanoTime()
        if (mono == null) {
            if (tracing) trace("skip: readMono returned null (offset/length mismatch)")
            return
        }
        if (mono.isEmpty()) return

        val tap = HookTaps.get(pkg, process, rate, channels)
        // Ask the engine how much capture it needs before it can emit anything.
        // Reading ahead a fixed amount either deadlocks (too little) or becomes
        // permanent delay (too much).
        tap.fetchNeed()
        tap.enqueue(mono)
        // The engine cannot emit its first block until it has capture to look
        // ahead over, and only this thread can produce that capture. Read ahead
        // until something is converted, then wait for it: the read-ahead audio
        // is queued like any other and comes back, in order, on later calls.
        val cap = if (tap.needFrames > 0) maxOf(tap.needFrames, mono.size) else rate * PREROLL_MS.toInt() / 1000
        preRoll(param, args, channels, encoding, start, tap, mono.size, cap, tracing)
        var out = tap.tryAwait(mono.size, PREROLL_WAIT_MS)
        if (out == null && !tap.primed) {
            // The engine wanted more than the app side reported (cold start or a
            // rebuilt engine). Read ahead again rather than idling forever.
            var tries = 0
            while (out == null && tries++ < PREROLL_RETRIES) {
                preRoll(param, args, channels, encoding, start, tap, mono.size, cap, tracing)
                out = tap.tryAwait(mono.size, PREROLL_WAIT_MS)
            }
        }
        out = out ?: tap.await(mono.size)
        val t2 = System.nanoTime()
        if (out == null) {
            if (tracing) trace("skip: stream closed while waiting for ${mono.size} frames")
            return
        }
        if (out.size != mono.size) {
            if (tracing) trace("skip: exchange in=${mono.size} out=${out.size}")
            return
        }
        val n = converts.incrementAndGet()
        if (n <= 3) {
            trace(
                "converted ${out.size} frames in_rms=${"%.4f".format(rms(mono))} " +
                    "out_rms=${"%.4f".format(rms(out))} first_in=${mono.firstOrNull()} " +
                    "first_out=${out.firstOrNull()}"
            )
        }
        writeMono(args[0], args, read, channels, encoding, out, start)
        val t3 = System.nanoTime()
        cpuNs.addAndGet((t1 - t0) + (t3 - t2))
        blockNs.addAndGet(t2 - t1)
        val total = calls.incrementAndGet()
        if (total % 500 == 0) {
            trace(
                "timing calls=$total cpu_us=${cpuNs.get() / 1000 / total} " +
                    "block_us=${blockNs.get() / 1000 / total} read=$read frames=${mono.size}",
            )
        }
    }

    /**
     * Fills the engine's look-ahead by calling `read` again on this thread.
     *
     * Reads ahead only as far as the engine's own look-ahead needs (plus one
     * block), then waits. Reading further would not shorten anything: every
     * extra buffer read here becomes a buffer of permanent extra delay, because
     * the app can only ever consume at realtime. A cold engine therefore stalls
     * the first read instead of shifting the whole stream — the samples are
     * still all converted, in order, and nothing is dropped.
     */
    private fun preRoll(
        param: XC_MethodHook.MethodHookParam,
        args: Array<Any?>,
        channels: Int,
        encoding: Int,
        start: Int?,
        tap: HookTap,
        need: Int,
        capFrames: Int,
        tracing: Boolean,
    ) {
        if (tap.primed || tap.hasOutput(need)) return
        val method = param.method as? java.lang.reflect.Method ?: return
        val t0 = System.nanoTime()
        var rounds = 0
        preRolling.set(true)
        try {
            while (rounds * need < capFrames && !tap.hasOutput(need)) {
                if ((System.nanoTime() - t0) / 1_000_000 >= PREROLL_TIMEOUT_MS) break
                // Re-enters the hook, which returns immediately because of the
                // thread-local flag, and leaves raw capture in the buffer.
                val n = method.invoke(param.thisObject, *args) as? Int ?: break
                if (n <= 0) break
                val more = readMono(args[0], args, n, channels, encoding, start) ?: break
                if (more.isEmpty()) break
                tap.enqueue(more)
                rounds++
            }
            if (tracing) {
                trace(
                    "pre-roll rounds=$rounds frames=${rounds * need} " +
                        "ready=${tap.hasOutput(need)}"
                )
            }
        } catch (t: Throwable) {
            trace("pre-roll stopped: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            preRolling.set(false)
        }
    }

    private fun rms(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        var sum = 0.0
        for (v in a) sum += v.toDouble() * v
        return kotlin.math.sqrt(sum / a.size).toFloat()
    }

    // ------------------------------------------------------------ reading

    private fun readMono(
        target: Any?,
        args: Array<Any?>,
        read: Int,
        channels: Int,
        encoding: Int,
        start: Int?,
    ): FloatArray? = when (target) {
        is ShortArray -> shortsToMono(target, offset(args, 1), read, channels)
        is FloatArray -> floatsToMono(target, offset(args, 1), read, channels)
        is ByteArray -> bytesToMono(target, offset(args, 1), read, channels, bytesPerSample(encoding))
        is ByteBuffer -> bufferToMono(target, start ?: (target.position() - read), read, channels, bytesPerSample(encoding))
        else -> null
    }

    private fun writeMono(
        target: Any?,
        args: Array<Any?>,
        read: Int,
        channels: Int,
        encoding: Int,
        mono: FloatArray,
        start: Int?,
    ) {
        when (target) {
            is ShortArray -> writeShorts(target, offset(args, 1), mono, channels)
            is FloatArray -> writeFloats(target, offset(args, 1), mono, channels)
            is ByteArray -> writeBytes(target, offset(args, 1), mono, channels, bytesPerSample(encoding))
            is ByteBuffer -> writeBuffer(target, start ?: (target.position() - read), mono, channels, bytesPerSample(encoding))
        }
    }

    private fun offset(args: Array<Any?>, index: Int): Int = (args.getOrNull(index) as? Int) ?: 0

    private fun bytesPerSample(encoding: Int): Int =
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2

    private fun shortsToMono(array: ShortArray, off: Int, read: Int, channels: Int): FloatArray? {
        val frames = read / channels
        if (frames <= 0 || off < 0 || off + read > array.size) return null
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            val base = off + i * channels
            for (c in 0 until channels) sum += array[base + c] / 32768f
            mono[i] = sum / channels
        }
        return mono
    }

    private fun floatsToMono(array: FloatArray, off: Int, read: Int, channels: Int): FloatArray? {
        val frames = read / channels
        if (frames <= 0 || off < 0 || off + read > array.size) return null
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            val base = off + i * channels
            for (c in 0 until channels) sum += array[base + c]
            mono[i] = sum / channels
        }
        return mono
    }

    private fun bytesToMono(
        array: ByteArray,
        off: Int,
        read: Int,
        channels: Int,
        bytesPer: Int,
    ): FloatArray? {
        val frameBytes = bytesPer * channels
        val frames = read / frameBytes
        if (frames <= 0 || off < 0 || off + frames * frameBytes > array.size) return null
        val mono = FloatArray(frames)
        var p = off
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (bytesPer == 4) {
                    val bits = (array[p].toInt() and 0xFF) or
                        ((array[p + 1].toInt() and 0xFF) shl 8) or
                        ((array[p + 2].toInt() and 0xFF) shl 16) or
                        ((array[p + 3].toInt() and 0xFF) shl 24)
                    Float.fromBits(bits)
                } else {
                    val v = (array[p].toInt() and 0xFF) or (array[p + 1].toInt() shl 8)
                    v.toShort() / 32768f
                }
                p += bytesPer
            }
            mono[i] = sum / channels
        }
        return mono
    }

    private fun bufferToMono(
        buffer: ByteBuffer,
        start: Int,
        read: Int,
        channels: Int,
        bytesPer: Int,
    ): FloatArray? {
        val frameBytes = bytesPer * channels
        val frames = read / frameBytes
        if (frames <= 0 || start < 0 || start + read > buffer.capacity()) return null
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(start)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (bytesPer == 4) view.getFloat() else view.getShort() / 32768f
            }
            mono[i] = sum / channels
        }
        return mono
    }

    // ------------------------------------------------------------ writing

    private fun writeShorts(array: ShortArray, off: Int, mono: FloatArray, channels: Int) {
        if (off < 0 || off + mono.size * channels > array.size) return
        for (i in mono.indices) {
            val v = (mono[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            val base = off + i * channels
            for (c in 0 until channels) array[base + c] = v
        }
    }

    private fun writeFloats(array: FloatArray, off: Int, mono: FloatArray, channels: Int) {
        if (off < 0 || off + mono.size * channels > array.size) return
        for (i in mono.indices) {
            val v = mono[i].coerceIn(-1f, 1f)
            val base = off + i * channels
            for (c in 0 until channels) array[base + c] = v
        }
    }

    private fun writeBytes(
        array: ByteArray,
        off: Int,
        mono: FloatArray,
        channels: Int,
        bytesPer: Int,
    ) {
        if (off < 0 || off + mono.size * channels * bytesPer > array.size) return
        var p = off
        for (v in mono) {
            val clamped = v.coerceIn(-1f, 1f)
            for (c in 0 until channels) {
                if (bytesPer == 4) {
                    val bits = clamped.toBits()
                    array[p] = (bits and 0xFF).toByte()
                    array[p + 1] = ((bits ushr 8) and 0xFF).toByte()
                    array[p + 2] = ((bits ushr 16) and 0xFF).toByte()
                    array[p + 3] = ((bits ushr 24) and 0xFF).toByte()
                } else {
                    val s = (clamped * 32767f).toInt()
                    array[p] = (s and 0xFF).toByte()
                    array[p + 1] = ((s ushr 8) and 0xFF).toByte()
                }
                p += bytesPer
            }
        }
    }

    private fun writeBuffer(
        buffer: ByteBuffer,
        start: Int,
        mono: FloatArray,
        channels: Int,
        bytesPer: Int,
    ) {
        val need = mono.size * channels * bytesPer
        if (start < 0 || start + need > buffer.capacity()) return
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(start)
        for (v in mono) {
            val clamped = v.coerceIn(-1f, 1f)
            for (c in 0 until channels) {
                if (bytesPer == 4) view.putFloat(clamped) else view.putShort((clamped * 32767f).toInt().toShort())
            }
        }
    }
}
