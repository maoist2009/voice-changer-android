package org.voice.changer.engine

/**
 * What the live loop and the offline converter need from a backend.
 *
 * All positions are absolute 16 kHz input sample indices: the classes are fed
 * a random-access view of the capture timeline and asked for whatever audio
 * has become computable. That lets a stateless windowed model (rc.0) and an
 * append-only streaming model (RVC) share the same audio plumbing.
 */
interface LiveProcessor {
    /** Backend output rate in Hz (24 kHz for rc.0, the voice's rate for RVC). */
    val outputRate: Int

    /** Input samples already converted; monotonic while the session runs. */
    val outputSamples: Long

    /** Absolute input index that must be buffered before [next] can run. */
    val requiredSamples: Int

    /**
     * Input samples that must have been fed before the first output sample can
     * appear. Zero when a backend emits from its very first step.
     */
    val coldStartSamples: Long get() = 0L

    /** Clears all streaming state so the next sample is a fresh start. */
    fun reset()

    fun setParams(params: EngineParams)

    /**
     * Converts the next step. [validFrames] bounds the input length for an
     * offline clip (frames of 160 samples); live callers leave it unbounded.
     * May return an empty [ChunkAudio.audio] when the backend needs more
     * look-ahead.
     */
    fun next(pcm: PcmSource, validFrames: Int = Int.MAX_VALUE): ChunkAudio

    /** Converts a whole 16 kHz clip. */
    fun convert(pcm: FloatArray, progress: ((Float) -> Unit)? = null): FloatArray

    fun close()
}
