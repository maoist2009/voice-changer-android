package org.voice.changer.lab

import android.util.Log
import java.io.File

/**
 * The path catalogue and the runner.
 *
 * Recording lives here rather than in the activity on purpose: the lab must
 * run with no UI in front, so every path is exercised from the foreground
 * service and the result is a WAV plus one logcat line.
 */
object Lab {
    class Path(val id: String, val label: String, val run: (File, Int) -> Audio?)

    fun paths(): List<Path> = listOf(
        Path("j1-short-3arg-44k", "short[] 3-arg 44.1k (delegating)") { _, s ->
            Capture.shortDelegating(44100, s)
        },
        Path("j2-short-4arg-8k", "short[] 4-arg 8k (terminal)") { _, s ->
            Capture.shortTerminal(8000, s)
        },
        Path("j3-byte-44k", "byte[] 44.1k") { _, s ->
            Capture.byteArray(44100, s)
        },
        Path("j4-float-48k", "float[] 48k") { _, s ->
            Capture.floatArray(48000, s)
        },
        Path("j5-bytebuffer-44k", "ByteBuffer 2-arg (delegating)") { _, s ->
            Capture.byteBufferDelegating(44100, s)
        },
        Path("j6-bytebuffer-nonblock", "ByteBuffer non-blocking") { _, s ->
            Capture.byteBufferNonBlocking(44100, s)
        },
        Path("j8-bytebuffer-8k", "ByteBuffer 2-arg 8k (recorder shape)") { _, s ->
            Capture.byteBufferDelegating(8000, s)
        },
        Path("n1-aaudio-read", "AAudio read") { dir, s ->
            native("n1-aaudio-read", dir, s, 48000) { p, sec, r -> NativeCapture.aaudioRead(p, sec, r) }
        },
        Path("n2-aaudio-callback", "AAudio callback") { dir, s ->
            native("n2-aaudio-callback", dir, s, 48000) { p, sec, r -> NativeCapture.aaudioCallback(p, sec, r) }
        },
        Path("n3-opensl", "OpenSL ES") { dir, s ->
            native("n3-opensl", dir, s, 44100) { p, sec, r -> NativeCapture.openslRecord(p, sec, r) }
        },
        Path("m1-mediarecorder-aac", "MediaRecorder AAC") { dir, s ->
            Capture.mediaRecorder(File(dir, "m1-mediarecorder-aac.m4a"), s, true)
        },
        Path("m2-mediarecorder-amr", "MediaRecorder AMR") { dir, s ->
            Capture.mediaRecorder(File(dir, "m2-mediarecorder-amr.amr"), s, false)
        },
    )

    fun dir(root: File): File = File(root, "lab").apply { mkdirs() }

    /** Runs one path, writes its WAV, logs the summary and returns it. */
    fun run(path: Path, dir: File, seconds: Int): String {
        val t0 = System.currentTimeMillis()
        val audio = try {
            path.run(dir, seconds)
        } catch (t: Throwable) {
            Log.w("lab", "${path.id} failed: $t")
            null
        }
        val ms = System.currentTimeMillis() - t0
        if (audio == null || audio.frames == 0) {
            val line = "${path.id}: FAILED (${ms} ms)"
            Log.i("lab", line)
            return line
        }
        Wav.write(File(dir, "${path.id}.wav"), audio)
        val slices = audio.slices().joinToString(",") { "%.3f".format(it) }
        val line = "${path.id}: rate=${audio.rate} frames=${audio.frames} " +
            "rms=${"%.3f".format(audio.rms())} peak=${"%.3f".format(audio.peak())} " +
            "ms=$ms slices=$slices"
        Log.i("lab", line)
        return line
    }

    private fun native(
        id: String,
        dir: File,
        seconds: Int,
        rate: Int,
        call: (String, Int, Int) -> Int,
    ): Audio? {
        val raw = File(dir, "$id.pcm")
        val frames = call(raw.absolutePath, seconds, rate)
        Log.i("lab", "$id native frames=$frames rate=$rate")
        if (frames <= 0) return null
        return Wav.readRawS16(raw, rate)
    }
}
