package dev.alamo3.cabineq.music

import java.nio.ByteBuffer

/**
 * Decoded PCM of any encoding and channel count → the interleaved stereo float the DSP engine
 * takes. One implementation for both players, so the engine is fed the same numbers whichever
 * one is decoding. Plain JVM (the encoding constants are Android's AudioFormat values, which
 * Media3 shares), so the unit tests cover it.
 */
object Pcm {
    const val ENCODING_16BIT = 2
    const val ENCODING_8BIT = 3
    const val ENCODING_FLOAT = 4
    const val ENCODING_24BIT_PACKED = 21
    const val ENCODING_32BIT = 22

    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        ENCODING_8BIT -> 1
        ENCODING_16BIT -> 2
        ENCODING_24BIT_PACKED -> 3
        ENCODING_32BIT, ENCODING_FLOAT -> 4
        else -> 2
    }

    /**
     * Converts [frames] interleaved frames starting at the buffer's position into [out]. Mono is
     * duplicated; for more than two channels the front left and right are kept. Does not move
     * the buffer's position.
     */
    fun toStereoFloat(buf: ByteBuffer, encoding: Int, channels: Int, frames: Int, out: FloatArray) {
        val bps = bytesPerSample(encoding)
        val base = buf.position()
        for (i in 0 until frames) {
            val p = base + i * channels * bps
            val l = sanitize(readSample(buf, p, encoding))
            val r = if (channels >= 2) sanitize(readSample(buf, p + bps, encoding)) else l
            out[2 * i] = l
            out[2 * i + 1] = r
        }
    }

    /** One bad sample must never reach the IIR filters: NaN/Inf would poison them for good. */
    fun sanitize(x: Float): Float = when {
        x.isNaN() || x.isInfinite() -> 0f
        x > 4f -> 4f
        x < -4f -> -4f
        else -> x
    }

    fun readSample(buf: ByteBuffer, p: Int, encoding: Int): Float = when (encoding) {
        ENCODING_FLOAT -> buf.getFloat(p)
        ENCODING_32BIT -> buf.getInt(p) / 2147483648f
        ENCODING_24BIT_PACKED -> {
            val v = (buf.get(p).toInt() and 0xFF) or
                ((buf.get(p + 1).toInt() and 0xFF) shl 8) or
                (buf.get(p + 2).toInt() shl 16)   // sign comes from the top byte
            v / 8388608f
        }
        ENCODING_8BIT -> ((buf.get(p).toInt() and 0xFF) - 128) / 128f
        else -> buf.getShort(p) / 32768f
    }
}
