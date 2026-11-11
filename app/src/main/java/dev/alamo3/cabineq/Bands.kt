package dev.alamo3.cabineq

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * 10-band octave EQ layout.
 *
 * DynamicsProcessing defines each EQ band by its *upper* cutoff frequency (the band
 * covers everything from the previous band's cutoff up to its own). To make the bands
 * behave like a normal octave EQ centred on [CENTERS], each cutoff is placed at the
 * geometric midpoint between neighbouring centres.
 */
object Bands {
    val CENTERS = floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val COUNT = CENTERS.size

    val CUTOFFS = FloatArray(COUNT) { i ->
        if (i == COUNT - 1) 20000f else sqrt(CENTERS[i] * CENTERS[i + 1])
    }

    /**
     * How much of the speed-compensation boost each band receives (0..1).
     * EV cabin noise at speed is mostly tyre/road rumble and wind, concentrated in the
     * lows and low-mids, so the boost leans heavily on those bands. Treble gets a small
     * lift to preserve clarity against wind noise.
     */
    val SPEED_WEIGHTS = floatArrayOf(1.0f, 1.0f, 0.9f, 0.7f, 0.45f, 0.25f, 0.15f, 0.15f, 0.2f, 0.2f)

    const val MAX_BAND_DB = 12f

    /**
     * Preamp that keeps the EQ from clipping: cut by the highest point of the EQ's
     * response, rounded up to 0.5 dB. That's the largest band gain for the Android engine's
     * band-split EQ, and slightly more for the native EQ, whose peaking bands overlap —
     * using the larger of the two keeps both safe.
     */
    fun recommendedPreamp(gains: FloatArray): Float {
        val maxBand = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
        val response = EqDesign.peakResponseDb(gains).toFloat().coerceAtLeast(0f)
        val peak = maxOf(maxBand, response)
        return -(ceil(peak * 2f) / 2f)
    }

    /** Peak boost (dB) the recommendation is based on, for display. */
    fun peakBoost(gains: FloatArray): Float =
        maxOf((gains.maxOrNull() ?: 0f).coerceAtLeast(0f), EqDesign.peakResponseDb(gains).toFloat().coerceAtLeast(0f))

    fun label(i: Int): String {
        // Band 0 is a low shelf, not a peaking band at its centre: it lifts everything below its
        // corner, so naming it after a frequency overstates what the slider does.
        if (i == 0) return "Bass"
        val f = CENTERS[i]
        return if (f >= 1000f) "${(f / 1000f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} kHz"
        else "${f.toInt()} Hz"
    }
}
