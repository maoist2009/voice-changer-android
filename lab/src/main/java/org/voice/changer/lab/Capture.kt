package org.voice.changer.lab

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One function per Java capture call shape.
 *
 * The call shape is the point, not the audio. Which overload a target uses
 * decides whether an interception module can see the stream at all, and
 * whether it has to dedupe a delegating wrapper against the method that
 * wrapper calls:
 *
 *   read(short[], int, int)         delegates to the four-argument form
 *   read(short[], int, int, int)    terminal
 *   read(byte[], int, int)          delegates
 *   read(float[], int, int, int)    terminal (no delegating form exists)
 *   read(ByteBuffer, int)           delegates
 *   read(ByteBuffer, int, int)      terminal
 *
 * Hook all of them and every buffer is converted twice.
 */
object Capture {
    private const val SOURCE = MediaRecorder.AudioSource.MIC

    private fun open(rate: Int, encoding: Int): AudioRecord {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
        val size = maxOf(min, rate) // 0.5 s at 16-bit
        return AudioRecord(SOURCE, rate, AudioFormat.CHANNEL_IN_MONO, encoding, size)
    }

    private fun start(rec: AudioRecord) {
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            android.util.Log.w("lab", "startRecording did not take: state=${rec.state} recording=${rec.recordingState}")
        }
    }

    /** 3-argument short[]: the framework delegates to the terminal form. */
    fun shortDelegating(rate: Int, seconds: Int): Audio = shortRead(rate, seconds, delegating = true)

    /** 4-argument short[] with READ_BLOCKING: the terminal method. */
    fun shortTerminal(rate: Int, seconds: Int): Audio = shortRead(rate, seconds, delegating = false)

    private fun shortRead(rate: Int, seconds: Int, delegating: Boolean): Audio {
        val rec = open(rate, AudioFormat.ENCODING_PCM_16BIT)
        val want = rate * seconds
        val out = ShortArray(want)
        val chunk = ShortArray(rate / 10)
        var got = 0
        start(rec)
        try {
            while (got < want) {
                val n = if (delegating) {
                    rec.read(chunk, 0, chunk.size)
                } else {
                    rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                }
                if (n < 0) break
                if (n == 0) continue
                val c = minOf(n, want - got)
                System.arraycopy(chunk, 0, out, got, c)
                got += c
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return Audio(out.copyOf(got), rate)
    }

    /** 16-bit PCM read through a byte[] (the terminal form). */
    fun byteArray(rate: Int, seconds: Int): Audio {
        val rec = open(rate, AudioFormat.ENCODING_PCM_16BIT)
        val want = rate * seconds * 2
        val out = ByteArray(want)
        val chunk = ByteArray(rate / 10 * 2)
        var got = 0
        start(rec)
        try {
            while (got < want) {
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n < 0) break
                if (n == 0) continue
                val c = minOf(n, want - got)
                System.arraycopy(chunk, 0, out, got, c)
                got += c
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return bytesToAudio(out, got, rate)
    }

    /** float[]: needs ENCODING_PCM_FLOAT. */
    fun floatArray(rate: Int, seconds: Int): Audio {
        val rec = open(rate, AudioFormat.ENCODING_PCM_FLOAT)
        val want = rate * seconds
        val pcm = ShortArray(want)
        val chunk = FloatArray(rate / 10)
        var got = 0
        start(rec)
        try {
            while (got < want) {
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n < 0) break
                if (n == 0) continue
                val c = minOf(n, want - got)
                for (i in 0 until c) {
                    val v = chunk[i].coerceIn(-1f, 1f)
                    pcm[got + i] = (v * 32767f).toInt().toShort()
                }
                got += c
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return Audio(pcm.copyOf(got), rate)
    }

    /** Direct ByteBuffer, two-argument form: what the recorder app calls. */
    fun byteBufferDelegating(rate: Int, seconds: Int): Audio {
        val rec = open(rate, AudioFormat.ENCODING_PCM_16BIT)
        val cap = rate / 10 * 2
        val bb = ByteBuffer.allocateDirect(cap)
        val out = ShortBuf(rate * seconds)
        val tmp = ShortArray(cap / 2)
        val want = rate * seconds
        var got = 0
        start(rec)
        try {
            while (got < want) {
                bb.clear()
                val n = rec.read(bb, cap)
                if (n < 0) break
                if (n == 0) continue
                val count = n / 2
                bb.position(0)
                bb.limit(n)
                val view = bb.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) tmp[i] = view.short
                out.add(tmp, count)
                got += count
            }
        } finally {
            rec.stop()
            rec.release()
        }
        return Audio(out.toArray(), rate)
    }

    /** Direct ByteBuffer, three-argument NON_BLOCKING: short reads and zeros. */
    fun byteBufferNonBlocking(rate: Int, seconds: Int): Audio {
        val rec = open(rate, AudioFormat.ENCODING_PCM_16BIT)
        val cap = rate / 20 * 2
        val bb = ByteBuffer.allocateDirect(cap)
        val out = ShortBuf(rate * seconds)
        val tmp = ShortArray(cap / 2)
        val want = rate * seconds
        var got = 0
        var zeros = 0
        start(rec)
        try {
            while (got < want) {
                bb.clear()
                val n = rec.read(bb, cap, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) break
                if (n == 0) {
                    zeros++
                    Thread.sleep(5)
                    continue
                }
                val count = n / 2
                bb.position(0)
                bb.limit(n)
                val view = bb.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) tmp[i] = view.short
                out.add(tmp, count)
                got += count
            }
        } finally {
            rec.stop()
            rec.release()
        }
        android.util.Log.i("lab", "byteBufferNonBlocking zero-reads=$zeros")
        return Audio(out.toArray(), rate)
    }

    // ------------------------------------------------------- MediaRecorder

    /**
     * MediaRecorder writes a file and never hands PCM to the app, so this path
     * can only be checked by decoding what it produced. If a module can change
     * this file, it intercepted below the Java API; if it cannot, the app-side
     * PCM never existed.
     */
    fun mediaRecorder(file: java.io.File, seconds: Int, aac: Boolean): Audio? {
        val mr = MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (aac) {
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(44100)
            } else {
                mr.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                mr.setAudioSamplingRate(8000)
            }
            mr.setAudioChannels(1)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            Thread.sleep(seconds * 1000L)
            mr.stop()
        } catch (t: Throwable) {
            android.util.Log.w("lab", "mediaRecorder failed: $t")
            runCatching { mr.release() }
            return null
        }
        mr.release()
        return Decode.toAudio(file)
    }

    private fun bytesToAudio(bytes: ByteArray, size: Int, rate: Int): Audio {
        val n = size / 2
        val pcm = ShortArray(n)
        val view = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) pcm[i] = view.short
        return Audio(pcm, rate)
    }
}
