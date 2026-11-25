package org.voice.changer.lab

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** Turns a MediaRecorder output file back into PCM so it can be compared. */
object Decode {
    fun toAudio(file: File): Audio? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ShortBuf()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                when (val idx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                    else -> if (idx >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(idx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val view = buf.order(ByteOrder.LITTLE_ENDIAN)
                            val frames = info.size / 2 / channels.coerceAtLeast(1)
                            val chunk = ShortArray(frames)
                            for (i in 0 until frames) {
                                var sum = 0
                                for (c in 0 until channels) sum += view.short.toInt()
                                chunk[i] = (sum / channels).toShort()
                            }
                            out.add(chunk)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()
            return Audio(out.toArray(), rate)
        } catch (t: Throwable) {
            android.util.Log.w("lab", "decode failed: $t")
            return null
        } finally {
            runCatching { ex.release() }
        }
    }
}
