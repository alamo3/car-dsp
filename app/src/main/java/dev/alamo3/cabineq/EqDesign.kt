package dev.alamo3.cabineq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filter design for the native EQ (RBJ Audio EQ Cookbook biquads).
 *
 * Band 0 is a low shelf, the top band a high shelf, everything in between is a peaking
 * filter at the band centre with ~1 octave bandwidth. Unlike the Android engine's
 * band-split EQ, peaking filters overlap, so neighbouring boosts add up a little —
 * [peakResponseDb] computes the true maximum so the preamp recommendation accounts for it.
 */
object EqDesign {
    private const val PEAK_Q = 1.41
    private const val LOW_SHELF_HZ = 44.0     // between the 31 and 63 Hz bands
    private const val HIGH_SHELF_HZ = 11300.0 // between the 8k and 16k bands

    /** Coefficients for every band, 5 per band: b0, b1, b2, a1, a2. */
    fun designAll(gainsDb: FloatArray, fs: Double): DoubleArray {
        val out = DoubleArray(gainsDb.size * 5)
        for (i in gainsDb.indices) design(i, gainsDb[i].toDouble(), fs).copyInto(out, i * 5)
        return out
    }

    fun design(band: Int, gainDb: Double, fs: Double): DoubleArray {
        if (kotlin.math.abs(gainDb) < 0.01) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        val a = 10.0.pow(gainDb / 40.0)
        // We now run at whatever rate the device mixes at, which can be 32 kHz on some routes.
        // A centre at Nyquist degenerates the biquad to a pole on the unit circle (sin(w0) = 0,
        // cos(w0) = -1) — infinite gain, or a screech. Keep every centre below 0.45 fs.
        val limit = fs * 0.45
        fun f(hz: Double) = if (hz < limit) hz else limit
        return when (band) {
            0 -> lowShelf(f(LOW_SHELF_HZ), a, fs)
            Bands.COUNT - 1 -> highShelf(f(HIGH_SHELF_HZ), a, fs)
            else -> peaking(f(Bands.CENTERS[band].toDouble()), a, PEAK_Q, fs)
        }
    }

    /**
     * Two cascaded 2nd-order Butterworth low-passes (24 dB/oct). Used as the Bluetooth guard:
     * A2DP cannot carry much above ~15 kHz, so what it does carry is codec noise — removing it
     * before the codec is why the crackle on bright material goes away.
     */
    fun lowPass(fs: Double, hz: Double): DoubleArray {
        val f = kotlin.math.min(hz, fs * 0.45)
        val w0 = 2 * PI * f / fs
        val cw = kotlin.math.cos(w0)
        val alpha = kotlin.math.sin(w0) / (2 * BUTTERWORTH_Q)
        val b0 = (1 - cw) / 2
        val a0 = 1 + alpha
        // Same filter twice: b0, b1, b2, a1, a2 per stage.
        val stage = doubleArrayOf(b0 / a0, (1 - cw) / a0, b0 / a0, (-2 * cw) / a0, (1 - alpha) / a0)
        return stage + stage
    }

    /** Q of one 2nd-order section of a Butterworth pair. */
    private const val BUTTERWORTH_Q = 0.7071067811865476

    private fun peaking(f0: Double, a: Double, q: Double, fs: Double): DoubleArray {
        val w0 = 2 * PI * f0 / fs
        val alpha = sin(w0) / (2 * q)
        val cw = cos(w0)
        return normalise(
            1 + alpha * a, -2 * cw, 1 - alpha * a,
            1 + alpha / a, -2 * cw, 1 - alpha / a,
        )
    }

    // Shelf slope S = 1.
    private fun lowShelf(f0: Double, a: Double, fs: Double): DoubleArray {
        val w0 = 2 * PI * f0 / fs
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val sa = 2 * sqrt(a) * alpha
        return normalise(
            a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
            (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
        )
    }

    private fun highShelf(f0: Double, a: Double, fs: Double): DoubleArray {
        val w0 = 2 * PI * f0 / fs
        val cw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val sa = 2 * sqrt(a) * alpha
        return normalise(
            a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
            (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
        )
    }

    private fun normalise(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    /** Combined magnitude (dB) of all bands at frequency [f]. */
    fun magnitudeDb(coeffs: DoubleArray, f: Double, fs: Double): Double {
        val w = 2 * PI * f / fs
        val c1 = cos(w); val s1 = sin(w)
        val c2 = cos(2 * w); val s2 = sin(2 * w)
        var totalDb = 0.0
        var i = 0
        while (i + 4 < coeffs.size) {
            val b0 = coeffs[i]; val b1 = coeffs[i + 1]; val b2 = coeffs[i + 2]
            val a1 = coeffs[i + 3]; val a2 = coeffs[i + 4]
            val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
            val dr = 1 + a1 * c1 + a2 * c2;  val di = -(a1 * s1 + a2 * s2)
            val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
            totalDb += 10 * log10(mag2)
            i += 5
        }
        return totalDb
    }

    /** Highest point of the EQ's response between 20 Hz and 20 kHz. */
    fun peakResponseDb(gainsDb: FloatArray, fs: Double = 48000.0): Double {
        if (gainsDb.all { kotlin.math.abs(it) < 0.01f }) return 0.0
        val coeffs = designAll(gainsDb, fs)
        val lo = ln(20.0); val hi = ln(20000.0)
        var peak = Double.NEGATIVE_INFINITY
        val points = 240
        for (k in 0..points) {
            val f = exp(lo + (hi - lo) * k / points)
            peak = max(peak, magnitudeDb(coeffs, f, fs))
        }
        return peak
    }
}
