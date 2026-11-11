package dev.alamo3.cabineq.music

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        // Decoded PCM is little-endian, whatever the buffer claims to be. MediaCodec hands out
        // buffers whose byte order is the JVM's default (big-endian) on some devices, and reading
        // one of those as little-endian gives samples far above full scale — heard as distortion,
        // and invisible to any check that trusts the buffer's own order.
        buf.order(ByteOrder.LITTLE_ENDIAN)
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

    /**
     * What the data in [buf] actually is, when the decoder's word for it cannot be trusted.
     *
     * Devices exist whose decoder reports float while producing 16-bit samples (and the other way
     * round). Read as float, 16-bit samples are garbage — most of it denormal, some of it far
     * above full scale — and a limiter then holds that down as constant distortion. The
     * declaration is checked once per stream against the data: a misread of real audio looks like
     * noise (adjacent samples unrelated), while any correct read of audio looks like audio.
     */
    class Reading(val encoding: Int, val switched: Boolean, val note: String)

    /** Frames the running check looks at: enough for garbage to show itself, small enough to run often. */
    const val CHECK_FRAMES = 512

    /**
     * True when reading this buffer as float gives samples that cannot be audio. Cheap enough to
     * run on every buffer, which is what makes it correct: a stream's format is configured once,
     * and a later song of the same format never reconfigures the sink even if its decoder
     * produces a different word size.
     */
    fun floatIsImpossible(buf: ByteBuffer, channels: Int): Boolean {
        if (channels <= 0) return false
        val frames = buf.remaining() / (bytesPerSample(ENCODING_FLOAT) * channels)
        if (frames < 32) return false
        val stats = measure(buf, ENCODING_FLOAT, channels, minOf(frames, CHECK_FRAMES))
        return stats.isImpossibleFloat()
    }

    /**
     * True when reading this buffer as float gives ordinary audio. Used the other way round: a
     * reading learned for a previous song must not outlive it, so a buffer that is plainly float
     * takes the declared interpretation back.
     */
    fun floatReadsAsAudio(buf: ByteBuffer, channels: Int): Boolean {
        if (channels <= 0) return false
        val frames = buf.remaining() / (bytesPerSample(ENCODING_FLOAT) * channels)
        if (frames < 32) return false
        val stats = measure(buf, ENCODING_FLOAT, channels, minOf(frames, CHECK_FRAMES))
        return !stats.isImpossibleFloat() && stats.isPlausibleAudio()
    }

    /** The word size that reads as audio in this buffer, when float does not. */
    fun alternativeToFloat(buf: ByteBuffer, channels: Int): Reading {
        if (channels <= 0) return Reading(ENCODING_FLOAT, false, "no channel count")
        val pick = interpretations(buf, ENCODING_FLOAT, channels).minByOrNull { it.second.roughness }
            ?: return Reading(ENCODING_FLOAT, false, "float samples are impossible, but nothing else is audio")
        return switched(ENCODING_FLOAT, pick)
    }

    private fun switched(declared: Int, pick: Pair<Int, Stats>) = Reading(
        pick.first, true,
        "declared ${name(declared)} but the data is ${name(pick.first)} (${pick.second.describe()})",
    )

    /** Every other word size that looks like audio in this buffer, with what it looks like. */
    private fun interpretations(buf: ByteBuffer, declared: Int, channels: Int): List<Pair<Int, Stats>> = buildList {
        // 32-bit integer PCM is not something a decoder outputs, and it reads exactly like float
        // for the same bytes (both are the same 32-bit words, scaled), so it is not considered.
        for (candidate in intArrayOf(ENCODING_16BIT, ENCODING_24BIT_PACKED, ENCODING_FLOAT)) {
            if (candidate == declared) continue
            val n = buf.remaining() / (bytesPerSample(candidate) * channels)
            if (n < 32) continue
            val stats = measure(buf, candidate, channels, minOf(n, 4096))
            if (stats.isAudio()) add(candidate to stats)
        }
    }

    fun name(encoding: Int): String = when (encoding) {
        ENCODING_FLOAT -> "float"
        ENCODING_16BIT -> "16-bit"
        ENCODING_24BIT_PACKED -> "24-bit"
        ENCODING_32BIT -> "32-bit"
        ENCODING_8BIT -> "8-bit"
        else -> "encoding $encoding"
    }

    private class Stats(
        val peak: Double,
        val rms: Double,
        val tiny: Double,
        /** Adjacent samples within a channel: ~0.05 for music, ~1.2 for unrelated noise. */
        val roughness: Double,
        /** Adjacent samples across the interleave: channels of real audio are related. */
        val jump: Double,
    ) {
        fun describe(): String = String.format(
            java.util.Locale.US, "peak %.1f dBFS, roughness %.2f, jump %.2f",
            if (peak > 0) 20 * Math.log10(peak) else -120.0, roughness, jump,
        )

        fun isAudio(): Boolean = peak.isFinite() && peak in 1e-4..2.0 && rms > 1e-5 &&
            !isMostlyDenormal() && roughness < 0.9 && jump < 1.0

        /** The declared encoding gets a looser rope: genuinely noisy music must not be "fixed". */
        fun isPlausibleAudio(): Boolean = peak.isFinite() && peak in 1e-4..2.0 && rms > 1e-5 &&
            !isMostlyDenormal() && roughness < 1.2 && jump < 1.3

        /** Bit patterns reinterpreted as float land almost entirely in the denormal range. */
        fun isMostlyDenormal(): Boolean = tiny > 0.5

        /**
         * These samples cannot be decoded audio, whatever they are: a signal six times over full
         * scale, almost every value in the denormal range (16-bit samples read as float), or not
         * a number at all.
         */
        fun isImpossibleFloat(): Boolean = !peak.isFinite() || peak > 4.0 || (isMostlyDenormal() && peak < 1.0)
    }

    private fun measure(buf: ByteBuffer, encoding: Int, channels: Int, frames: Int): Stats {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val bps = bytesPerSample(encoding)
        val base = buf.position()
        val pairs = minOf(channels, 2)
        var peak = 0.0
        var sum = 0.0
        var count = 0
        var tiny = 0
        var delta = 0.0
        var deltaCount = 0
        var jump = 0.0
        var jumpCount = 0
        var last = Double.NaN
        var previous = Double.NaN
        for (i in 0 until frames) {
            for (c in 0 until pairs) {
                val x = readSample(buf, base + (i * channels + c) * bps, encoding).toDouble()
                val a = if (x.isFinite()) Math.abs(x) else Double.MAX_VALUE
                if (a > peak) peak = a
                sum += if (x.isFinite()) x * x else 1.0
                count++
                if (a > 0.0 && a < 1e-30) tiny++
                if (c == 0 && last.isFinite() && x.isFinite()) {
                    delta += Math.abs(x - last)
                    deltaCount++
                }
                if (c == 0) last = x
                if (previous.isFinite() && x.isFinite()) {
                    jump += Math.abs(x - previous)
                    jumpCount++
                }
                previous = x
            }
        }
        val rms = if (count > 0) Math.sqrt(sum / count) else 0.0
        // Adjacent-sample difference relative to level: ~0.05 for music, ~1.2 for unrelated noise.
        val roughness = if (deltaCount > 0 && rms > 0) (delta / deltaCount) / rms else Double.MAX_VALUE
        val jumpiness = if (jumpCount > 0 && rms > 0) (jump / jumpCount) / rms else Double.MAX_VALUE
        return Stats(peak, rms, if (count > 0) tiny.toDouble() / count else 0.0, roughness, jumpiness)
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
