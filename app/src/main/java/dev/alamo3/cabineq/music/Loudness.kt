package dev.alamo3.cabineq.music

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Programme loudness as ITU-R BS.1770 measures it (LUFS): K-weighting, 400 ms blocks with 75 %
 * overlap, absolute gate at -70 and relative gate 10 below the ungated mean. This is the number
 * streaming services normalise to; peak or plain RMS level says little about how loud music
 * sounds.
 */
class LoudnessMeter {
    private var rate = 0
    // K-weighting: a high shelf (the head) then a high-pass (RLB). Per-channel filter state.
    private val shelf = DoubleArray(5)
    private val pass = DoubleArray(5)
    private val z = Array(2) { DoubleArray(4) }

    /** Energy of the 100 ms piece being filled, and how many frames are in it. */
    private var pieceSum = 0.0
    private var pieceFrames = 0
    private var pieceSize = 0
    /** The last three finished pieces: four in a row make one 400 ms block. */
    private val recent = DoubleArray(3)
    private var pieces = 0
    /** Mean-square (both channels summed) of every 400 ms block so far. */
    private var blocks = DoubleArray(1024)
    private var blockCount = 0

    /** Seconds of audio measured. */
    val seconds: Double get() = pieces * 0.1

    private fun configure(sampleRate: Int) {
        rate = sampleRate
        pieceSize = (sampleRate / 10).coerceAtLeast(1)
        run {
            val k = tan(PI * 1681.974450955533 / sampleRate)
            val vh = 10.0.pow(3.999843853973347 / 20)
            val vb = vh.pow(0.4996667741545416)
            val q = 0.7071752369554196
            val a0 = 1 + k / q + k * k
            shelf[0] = (vh + vb * k / q + k * k) / a0
            shelf[1] = 2 * (k * k - vh) / a0
            shelf[2] = (vh - vb * k / q + k * k) / a0
            shelf[3] = 2 * (k * k - 1) / a0
            shelf[4] = (1 - k / q + k * k) / a0
        }
        run {
            val k = tan(PI * 38.13547087602444 / sampleRate)
            val q = 0.5003270373238773
            val a0 = 1 + k / q + k * k
            pass[0] = 1.0; pass[1] = -2.0; pass[2] = 1.0
            pass[3] = 2 * (k * k - 1) / a0
            pass[4] = (1 - k / q + k * k) / a0
        }
        for (s in z) s.fill(0.0)
        pieceSum = 0.0
        pieceFrames = 0
    }

    /** Interleaved stereo floats. A change of sample rate restarts the filters, not the measure. */
    fun add(stereo: FloatArray, frames: Int, sampleRate: Int) {
        if (sampleRate <= 0) return
        if (sampleRate != rate) configure(sampleRate)
        for (i in 0 until frames) {
            for (ch in 0..1) {
                val s = z[ch]
                val x = stereo[i * 2 + ch].toDouble()
                // Two biquads in transposed direct form II.
                val y1 = shelf[0] * x + s[0]
                s[0] = shelf[1] * x - shelf[3] * y1 + s[1]
                s[1] = shelf[2] * x - shelf[4] * y1
                val y2 = pass[0] * y1 + s[2]
                s[2] = pass[1] * y1 - pass[3] * y2 + s[3]
                s[3] = pass[2] * y1 - pass[4] * y2
                pieceSum += y2 * y2
            }
            if (++pieceFrames >= pieceSize) finishPiece()
        }
    }

    private fun finishPiece() {
        val mean = pieceSum / pieceFrames
        pieceSum = 0.0
        pieceFrames = 0
        if (pieces >= 3) {
            if (blockCount == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
            blocks[blockCount++] = (recent[0] + recent[1] + recent[2] + mean) / 4
        }
        recent[0] = recent[1]; recent[1] = recent[2]; recent[2] = mean
        pieces++
    }

    /** Integrated loudness so far in LUFS, or null when nothing above the gate has played. */
    fun integrated(): Double? {
        if (blockCount == 0) return null
        val absolute = 10.0.pow((-70.0 + 0.691) / 10)
        var sum = 0.0
        var n = 0
        for (i in 0 until blockCount) if (blocks[i] > absolute) { sum += blocks[i]; n++ }
        if (n == 0) return null
        val relative = sum / n * 0.1          // 10 LU below the absolute-gated mean
        sum = 0.0
        n = 0
        for (i in 0 until blockCount) if (blocks[i] > absolute && blocks[i] > relative) { sum += blocks[i]; n++ }
        if (n == 0) return null
        return -0.691 + 10 * log10(sum / n)
    }
}

/**
 * Loudness matching: one gain per song, so songs sit at the same level without touching the
 * dynamics inside them. Not a compressor — nothing here reacts to the music moment to moment.
 *
 *  - A song measured before plays at its stored level from the first note.
 *  - A new song starts from a guess, settles on its own measurement over the first half minute
 *    (slowly, so the change isn't heard as a move), then holds. Its full measurement is kept
 *    for next time.
 *  - Consecutive songs of one album share a gain, so the album keeps its own quiet and loud.
 *
 * Runs before the EQ and limiter, so a boosted quiet song's peaks are caught there.
 */
class Leveler(
    /** This song's loudness from an earlier play, if known. */
    private val known: Double?,
    /** The previous song's gain when this one continues the same album. */
    private val albumGainDb: Double?,
    /** Gain to begin with when nothing is known: what songs have needed on average. */
    private val startGainDb: Double = DEFAULT_START_DB,
    private val targetLufs: Double = TARGET_LUFS,
) {
    private val meter = LoudnessMeter()
    private var current = Double.NaN
    private var settled: Double? = null

    /** The gain in effect, in dB (for display and for the next album track). */
    val gainDb: Double get() = if (current.isNaN()) wanted() else current

    private fun gainFor(lufs: Double) = (targetLufs - lufs).coerceIn(MAX_CUT_DB, MAX_BOOST_DB)

    private fun wanted(): Double {
        albumGainDb?.let { return it }
        known?.let { return gainFor(it) }
        settled?.let { return it }
        val t = meter.seconds
        if (t < LISTEN_S) return startGainDb
        val now = meter.integrated()?.let(::gainFor) ?: startGainDb
        if (t >= SETTLE_S) settled = now
        return now
    }

    /** Measures [stereo] (as it is, before any gain) and then applies the gain in place. */
    fun process(stereo: FloatArray, frames: Int, sampleRate: Int) {
        if (frames <= 0 || sampleRate <= 0) return
        meter.add(stereo, frames, sampleRate)
        val target = wanted()
        val from = if (current.isNaN()) target else current
        // Slow enough not to be heard as a fade: at most [SLEW_DB_PER_S].
        val step = SLEW_DB_PER_S * frames / sampleRate
        val to = if (abs(target - from) <= step) target else from + step * (if (target > from) 1 else -1)
        current = to
        if (abs(from) < 0.01 && abs(to) < 0.01) return
        val g0 = 10.0.pow(from / 20).toFloat()
        val g1 = 10.0.pow(to / 20).toFloat()
        val inc = (g1 - g0) / frames
        var g = g0
        for (i in 0 until frames) {
            stereo[i * 2] *= g
            stereo[i * 2 + 1] *= g
            g += inc
        }
    }

    /** This song's loudness, once enough of it has played to be worth remembering. */
    fun measured(): Double? = if (meter.seconds >= KEEP_AFTER_S) meter.integrated() else null

    companion object {
        /** Where streaming services put it; loud masters come down, quiet ones up a little. */
        const val TARGET_LUFS = -14.0
        const val MAX_CUT_DB = -12.0
        /** Boost is limited: beyond this the limiter would be working on the peaks all the time. */
        const val MAX_BOOST_DB = 4.0
        /** A typical modern master is about -9 LUFS. */
        const val DEFAULT_START_DB = -5.0
        private const val LISTEN_S = 3.0
        private const val SETTLE_S = 30.0
        private const val KEEP_AFTER_S = 45.0
        private const val SLEW_DB_PER_S = 0.5
    }
}
