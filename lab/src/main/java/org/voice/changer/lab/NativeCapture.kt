package org.voice.changer.lab

/**
 * Native capture paths. The .so is built from src/main/cpp/lab_capture.c with
 * the NDK's arm64 host toolchain; see src/main/jniLibs/README.md.
 *
 * Each call writes raw s16le mono PCM to the given path and returns the number
 * of frames captured (negative on failure).
 */
object NativeCapture {
    init {
        System.loadLibrary("lab_capture")
    }

    /** AAudio blocking read: the function-return style capture path. */
    external fun aaudioRead(path: String, seconds: Int, rate: Int): Int

    /** AAudio data callback: the callback-proxy style path. */
    external fun aaudioCallback(path: String, seconds: Int, rate: Int): Int

    /** OpenSL ES recorder over an Android simple buffer queue. */
    external fun openslRecord(path: String, seconds: Int, rate: Int): Int
}
