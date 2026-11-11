package dev.alamo3.cabineq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the bass controls actually do to the signal.
 *
 * Two decisions are pinned here, because both were wrong before and both are audible:
 *
 *  - the low shelf turns over at 60 Hz, not 44 Hz, so its +6 dB lands on bass a car door can
 *    move (a 44 Hz shelf spent most of the boost below 40 Hz);
 *  - a 4th-order high-pass at 30 Hz sits in front of the EQ, so the boost does not ask the
 *    limiter for headroom it will then take out of the audible bass too.
 */
class EqDesignTest {

    private val fs = 48_000.0

    /** Magnitude of a cascade of biquads (b0, b1, b2, a1, a2 per section) at [hz]. */
    private fun magnitudeDb(coeffs: DoubleArray, hz: Double): Double {
        val w = 2 * PI * hz / fs
        val zr = cos(w)
        val zi = -sin(w)
        var re = 1.0
        var im = 0.0
        var i = 0
        while (i + 4 < coeffs.size) {
            val b0 = coeffs[i]; val b1 = coeffs[i + 1]; val b2 = coeffs[i + 2]
            val a1 = coeffs[i + 3]; val a2 = coeffs[i + 4]
            // Numerator b0 + b1 z^-1 + b2 z^-2, denominator 1 + a1 z^-1 + a2 z^-2.
            val nr = b0 + b1 * zr + b2 * (zr * zr - zi * zi)
            val ni = b1 * zi + b2 * (2 * zr * zi)
            val dr = 1 + a1 * zr + a2 * (zr * zr - zi * zi)
            val di = a1 * zi + a2 * (2 * zr * zi)
            val den = dr * dr + di * di
            val qr = (nr * dr + ni * di) / den
            val qi = (ni * dr - nr * di) / den
            val tr = re * qr - im * qi
            val ti = re * qi + im * qr
            re = tr
            im = ti
            i += 5
        }
        return 20 * log10(sqrt(re * re + im * im))
    }

    private fun assertNear(what: String, expected: Double, tolerance: Double, actual: Double) {
        assertEquals(what, expected, actual, tolerance)
    }

    @Test
    fun theSubsonicFilterIsAButterworthHighPass() {
        val hp = EqDesign.highPass(fs)
        assertNear("=-3 dB at the cutoff", -3.0, 0.5, magnitudeDb(hp, 30.0))
        assertNear("transparent an octave and a half up", 0.0, 0.5, magnitudeDb(hp, 120.0))
        assertTrue("steep below it: ${magnitudeDb(hp, 15.0)} dB", magnitudeDb(hp, 15.0) < -20.0)
        assertTrue("steeper still at 10 Hz: ${magnitudeDb(hp, 10.0)} dB", magnitudeDb(hp, 10.0) < -36.0)
    }

    @Test
    fun theBassShelfTurnsOverWhereItIsAudible() {
        val shelf = EqDesign.designAll(floatArrayOf(6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), fs)
        assertNear("full +6 dB well below the corner", 6.0, 0.3, magnitudeDb(shelf, 12.0))
        assertNear("half the gain at the corner", 3.0, 0.3, magnitudeDb(shelf, 60.0))
        assertNear("mostly gone by 250 Hz", 0.0, 0.5, magnitudeDb(shelf, 250.0))
    }

    @Test
    fun theBoostLandsOnTheAudibleBassNotOnRumble() {
        // The chain the engine runs: high-pass, then the user's curve, then cabin correction.
        val gains = floatArrayOf(6f, 5.5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)
        val chain = EqDesign.highPass(fs) + EqDesign.designAll(gains, fs)

        assertTrue("20 Hz must be pulled down, not lifted: ${magnitudeDb(chain, 20.0)} dB",
            magnitudeDb(chain, 20.0) < -5.0)
        assertTrue("50 Hz carries the boost: ${magnitudeDb(chain, 50.0)} dB",
            magnitudeDb(chain, 50.0) > 7.0)
        assertTrue("63 Hz carries it too: ${magnitudeDb(chain, 63.0)} dB",
            magnitudeDb(chain, 63.0) > 8.0)
        assertTrue("and it does not boom at 250 Hz: ${magnitudeDb(chain, 250.0)} dB",
            magnitudeDb(chain, 250.0) < 4.0)
    }

    @Test
    fun thePreampStillCoversTheBoost() {
        val gains = floatArrayOf(6f, 5.5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)
        val peak = Bands.peakBoost(gains)
        assertTrue("the summed response is above the largest band: $peak", peak > 6.0)
        assertEquals("the recommendation is the negated peak, rounded up to 0.5 dB",
            -(kotlin.math.ceil(peak * 2f) / 2f), Bands.recommendedPreamp(gains), 0.001f)
    }
}
