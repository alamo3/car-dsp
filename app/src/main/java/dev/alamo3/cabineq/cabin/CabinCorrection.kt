package dev.alamo3.cabineq.cabin

import dev.alamo3.cabineq.EqDesign
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Turns a measured response into a correction the DSP can apply: a handful of peaking filters
 * that pull the measured peaks down and lift the dips a little.
 *
 * Rules that matter more than the maths:
 *  - **Cut first.** Cuts are allowed 12 dB, boosts only 6 dB: a boost costs headroom and can push
 *    a speaker past its excursion, a cut cannot.
 *  - **Only where the measurement is trustworthy** (100 Hz – 8 kHz). Outside that the correction
 *    is zero, because the microphone's own response is unknown there.
 *  - **Headroom is not free.** The preamp is lowered by the largest boost so the limiter does not
 *    start working harder just because we corrected the sound.
 */
object CabinCorrection {

    const val MAX_CUT_DB = -12.0
    const val MAX_BOOST_DB = 6.0
    private const val Q = 1.6
    private const val BANDS = 10

    /**
     * Overlapping peaking filters add up, so taking each band straight from the target
     * over-corrects. Until this is a proper least-squares solve it is deliberately scaled back:
     * under-correcting sounds like "less of an improvement", over-correcting sounds broken.
     */
    private const val OVERLAP_COMPENSATION = 0.5

    data class Band(val hz: Double, val gainDb: Double)

    /** What was true when this curve was measured — needed to compare levels later. */
    data class Meta(
        val level: String = "",
        val at: Long = 0,
        val delayMs: Double = 0.0,
        val snrDb: Double = 0.0,
        /** Phone stream volume at the time. The car's own volume is not readable. */
        val volume: Int = -1,
    )

    class Curve(val bands: List<Band>, val preampDb: Double, val meta: Meta = Meta()) {

        fun toJson(): String {
            val arr = JSONArray()
            for (b in bands) arr.put(JSONObject().put("hz", b.hz).put("db", b.gainDb))
            return JSONObject()
                .put("v", 2)
                .put("preamp", preampDb)
                .put("bands", arr)
                .put("level", meta.level)
                .put("at", meta.at)
                .put("delay", meta.delayMs)
                .put("snr", meta.snrDb)
                .put("volume", meta.volume)
                .toString()
        }

        /** Biquad coefficients (5 per band) with the gains scaled by [amount] (0..1). */
        fun coeffs(fs: Double, amount: Float): DoubleArray {
            val out = DoubleArray(bands.size * 5)
            var i = 0
            for (b in bands) {
                if (!b.hz.isFinite() || !b.gainDb.isFinite()) continue
                val c = EqDesign.peakingBand(b.hz, b.gainDb * amount, Q, fs)
                c.copyInto(out, i * 5)
                i++
            }
            return out
        }

        /** Headroom to take away, in dB, for the given amount. */
        fun preampFor(amount: Float): Double = preampDb * amount

        fun describe(): String {
            val worstCut = bands.minByOrNull { it.gainDb } ?: return "no bands"
            val worstBoost = bands.maxByOrNull { it.gainDb } ?: return "no bands"
            return "%d bands (conservative) · cut %.1f dB @ %.0f Hz · boost %+.1f dB @ %.0f Hz"
                .format(java.util.Locale.US, bands.size, worstCut.gainDb, worstCut.hz,
                    worstBoost.gainDb, worstBoost.hz)
        }

        companion object {
            fun fromJson(text: String?): Curve? {
                if (text.isNullOrBlank()) return null
                return runCatching {
                    val o = JSONObject(text)
                    val arr = o.optJSONArray("bands") ?: return null
                    val bands = (0 until arr.length()).mapNotNull { i ->
                        val b = arr.optJSONObject(i) ?: return@mapNotNull null
                        val hz = b.optDouble("hz", Double.NaN)
                        val db = b.optDouble("db", Double.NaN)
                        // A NaN here would design a NaN filter and mute the chain.
                        if (!hz.isFinite() || hz <= 0.0 || !db.isFinite()) null else Band(hz, db)
                    }
                    if (bands.isEmpty()) {
                        null
                    } else {
                        Curve(
                            bands,
                            o.optDouble("preamp", 0.0),
                            Meta(
                                level = o.optString("level", ""),
                                at = o.optLong("at", 0L),
                                delayMs = o.optDouble("delay", 0.0),
                                snrDb = o.optDouble("snr", 0.0),
                                volume = o.optInt("volume", -1),
                            ),
                        )
                    }
                }.getOrNull()
            }
        }
    }

    /**
     * Fits the inverse of [points]. Returns null when the curve is too flat to be worth it — a
     * correction that does nothing is worse than none, because it hides what is happening.
     */
    fun fit(points: List<CabinMeasure.Point>): Curve? {
        if (points.size < 8) return null
        // 1/3-octave smoothing first: the 1/6-octave curve is noisier than a filter should chase.
        val smoothed = movingAverage(points, 0.33)
        val bands = ArrayList<Band>()
        var maxBoost = 0.0
        for (i in 0 until BANDS) {
            val hz = 50.0 * Math.pow(8000.0 / 50.0, i / (BANDS - 1).toDouble())
            if (hz < CabinMeasure.TRUSTED_LOW_HZ || hz > CabinMeasure.TRUSTED_HIGH_HZ) continue
            val measured = valueAt(smoothed, hz) ?: continue
            var gain = -measured
            // Edges: taper in and out so the correction does not end in a step.
            val edge = minOf(
                (Math.log10(hz / CabinMeasure.TRUSTED_LOW_HZ) / Math.log10(1.3)).coerceIn(0.0, 1.0),
                (Math.log10(CabinMeasure.TRUSTED_HIGH_HZ / hz) / Math.log10(1.25)).coerceIn(0.0, 1.0),
            )
            gain *= edge
            gain = (gain * OVERLAP_COMPENSATION).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
            if (abs(gain) < 0.5) continue
            if (gain > maxBoost) maxBoost = gain
            bands.add(Band(Math.round(hz).toDouble(), Math.round(gain * 10) / 10.0))
        }
        // A band per octave and a half is already a lot of correction; refuse to chase dust.
        if (bands.none { abs(it.gainDb) >= 1.0 }) return null
        return Curve(bands, -maxBoost)
    }

    /**
     * Largest difference between two levels' corrections. Because a correction is the inverse of
     * the measured response, a difference here means the car itself changes with volume — its
     * loudness contour — not that we measured badly.
     */
    fun levelDifference(a: Curve, b: Curve): Band? {
        var worst: Band? = null
        for (band in a.bands) {
            val other = b.bands.minByOrNull { kotlin.math.abs(it.hz - band.hz) } ?: continue
            if (kotlin.math.abs(other.hz - band.hz) > band.hz * 0.2) continue
            val diff = Band(band.hz, other.gainDb - band.gainDb)
            if (worst == null || kotlin.math.abs(diff.gainDb) > kotlin.math.abs(worst.gainDb)) worst = diff
        }
        return worst
    }

    // ----------------------------------------------------------------------------- helpers

    /** Average over ±[octaves] around each point, so a single noisy band cannot drive a filter. */
    private fun movingAverage(points: List<CabinMeasure.Point>, octaves: Double): List<CabinMeasure.Point> {
        val lo = Math.pow(2.0, -octaves)
        val hi = Math.pow(2.0, octaves)
        return points.map { p ->
            var sum = 0.0
            var n = 0
            for (q in points) if (q.hz in p.hz * lo..p.hz * hi) { sum += q.db; n++ }
            CabinMeasure.Point(p.hz, if (n > 0) sum / n else p.db)
        }
    }

    /** Interpolates the curve at [hz] (log-frequency linear between neighbouring points). */
    private fun valueAt(points: List<CabinMeasure.Point>, hz: Double): Double? {
        if (points.isEmpty()) return null
        val sorted = points
        var prev: CabinMeasure.Point? = null
        for (p in sorted) {
            if (p.hz >= hz) {
                val a = prev ?: return p.db
                val t = (Math.log10(hz) - Math.log10(a.hz)) / (Math.log10(p.hz) - Math.log10(a.hz))
                return a.db + t * (p.db - a.db)
            }
            prev = p
        }
        return prev?.db
    }
}
