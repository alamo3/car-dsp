package dev.alamo3.cabineq

/** JNI bindings to the C++ engine in src/main/cpp. Handles are opaque native pointers. */
object NativeDsp {
    init {
        System.loadLibrary("cabindsp")
    }

    external fun create(sampleRate: Int): Long
    external fun destroy(handle: Long)

    /** [coeffs] holds bandCount * 5 values per band: b0, b1, b2, a1, a2 (normalised by a0). */
    external fun setParams(handle: Long, coeffs: DoubleArray, bandCount: Int, preampLin: Float, limiterThresholdLin: Float)

    /** Processes interleaved stereo float samples in place. */
    external fun process(handle: Long, buffer: FloatArray, frames: Int)

    /** out = [input peak, output peak, minimum limiter gain], all linear, reset on read. */
    external fun readMeters(handle: Long, out: FloatArray)
}
