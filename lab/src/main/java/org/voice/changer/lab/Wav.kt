package org.voice.changer.lab

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Mono PCM as the lab compares it: 16-bit, one channel. */
class Audio(val pcm: ShortArray, val rate: Int) {
    val frames: Int get() = pcm.size

    fun rms(): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (v in pcm) {
            val f = v / 32768.0
            sum += f * f
        }
        return sqrt(sum / pcm.size).toFloat()
    }

    fun peak(): Float {
        var m = 0
        for (v in pcm) {
            val a = abs(v.toInt())
            if (a > m) m = a
        }
        return m / 32768f
    }

    /**
     * RMS of eight equal time slices: a cheap fingerprint for comparing two
     * takes of the same sentence. It says nothing about timbre; the module's
     * own log and the WAV files carry the real evidence.
     */
    fun slices(): FloatArray {
        val out = FloatArray(8)
        if (pcm.size < 2048) return out
        val slice = pcm.size / 8
        for (b in 0 until 8) {
            var sum = 0.0
            val from = b * slice
            for (i in from until from + slice) {
                val f = pcm[i] / 32768.0
                sum += f * f
            }
            out[b] = sqrt(sum / slice).toFloat()
        }
        return out
    }
}

object Wav {
    private const val HEADER = 44

    fun write(file: File, audio: Audio) {
        val data = audio.pcm.size * 2
        val buf = ByteBuffer.allocate(HEADER + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + data)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)                       // PCM
        buf.putShort(1)                       // mono
        buf.putInt(audio.rate)
        buf.putInt(audio.rate * 2)            // byte rate
        buf.putShort(2)                       // block align
        buf.putShort(16)                      // bits
        buf.put("data".toByteArray())
        buf.putInt(data)
        for (v in audio.pcm) buf.putShort(v)
        file.writeBytes(buf.array())
    }

    /** Reads raw little-endian s16 mono, which is what the native paths write. */
    fun readRawS16(file: File, rate: Int): Audio {
        val bytes = file.readBytes()
        val n = bytes.size / 2
        val pcm = ShortArray(n)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) pcm[i] = buf.short
        return Audio(pcm, rate)
    }

    fun read(file: File): Audio {
        val bytes = file.readBytes()
        if (bytes.size <= HEADER) return Audio(ShortArray(0), 16000)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(22)
        val channels = buf.short.toInt()
        val rate = buf.getInt(24)
        val n = (bytes.size - HEADER) / 2 / channels.coerceAtLeast(1)
        buf.position(HEADER)
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            var sum = 0
            for (c in 0 until channels) sum += buf.short.toInt()
            pcm[i] = (sum / channels).toShort()
        }
        return Audio(pcm, rate)
    }
}

/** Growable ShortArray, for decoders that emit variable-size chunks. */
class ShortBuf(initial: Int = 1 shl 16) {
    private var data = ShortArray(initial)
    private var size = 0

    fun add(src: ShortArray, count: Int = src.size) {
        if (size + count > data.size) {
            var next = data.size * 2
            while (next < size + count) next *= 2
            data = data.copyOf(next)
        }
        System.arraycopy(src, 0, data, size, count)
        size += count
    }

    fun toArray(): ShortArray = data.copyOf(size)
}
