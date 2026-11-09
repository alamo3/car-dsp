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

    /**
     * Rate of the stream being fed in. When it differs from the engine's rate the native
     * engine resamples first (exact polyphase, 128 taps), so the Android mixer gets samples
     * at the rate it actually mixes at and has nothing left to convert.
     */
    external fun setInputRate(handle: Long, inputRate: Int)

    /** Largest frame count [process] can return for [inputFrames] frames in. */
    external fun maxFrames(handle: Long, inputFrames: Int): Int

    /**
     * Resamples and processes interleaved stereo float: reads [inputFrames] frames from
     * [input], writes at most [outputFrames] to [output]. Returns the frames written.
     */
    external fun process(
        handle: Long,
        input: FloatArray,
        inputFrames: Int,
        output: FloatArray,
        outputFrames: Int,
    ): Int

    /** End of a track: pushes the resampler's look-ahead through. Returns frames written. */
    external fun flush(handle: Long, output: FloatArray, outputFrames: Int): Int

    /** Ramps the output up from silence over [frames]; call whenever playback (re)starts. */
    external fun fadeIn(handle: Long, frames: Int)

    /**
     * out = [input peak, output peak, min limiter gain, true peak, clicks], all linear except
     * the count; reset on read.
     */
    external fun readMeters(handle: Long, out: FloatArray)
}
