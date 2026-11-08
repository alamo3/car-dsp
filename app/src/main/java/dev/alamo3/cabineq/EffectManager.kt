package dev.alamo3.cabineq

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.abs

/**
 * Owns one DynamicsProcessing instance per audio session and keeps them all in sync
 * with the current EQ state.
 *
 * Chain per session: input gain (preamp/headroom) -> 10-band pre-EQ -> limiter.
 * The multiband compressor and post-EQ stages are left unused for now; the content-aware
 * dynamic EQ in a later milestone will use them.
 */
class EffectManager {

    private val effects = LinkedHashMap<Int, DynamicsProcessing>()

    // Last values pushed to the effects, so the 10 Hz ticker only sends real changes
    // across binder.
    private val appliedBands = FloatArray(Bands.COUNT) { Float.NaN }
    private var appliedInputGain = Float.NaN

    // Current desired state; new sessions are initialised from this.
    private val currentBands = FloatArray(Bands.COUNT)
    private var currentInputGain = 0f

    val sessionIds: Set<Int> get() = effects.keys

    fun attach(sessionId: Int): Boolean {
        if (effects.containsKey(sessionId)) return true
        return try {
            val dp = DynamicsProcessing(PRIORITY, sessionId, buildConfig())
            dp.setEnabled(true)
            effects[sessionId] = dp
            Log.i(TAG, "Attached to session $sessionId (control=${dp.hasControl()})")
            true
        } catch (e: Exception) {
            // Thrown e.g. if the session is already gone, or the platform refuses
            // session 0 on this device.
            Log.w(TAG, "Failed to attach to session $sessionId", e)
            false
        }
    }

    fun detach(sessionId: Int) {
        effects.remove(sessionId)?.let {
            try {
                it.setEnabled(false)
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing session $sessionId", e)
            }
            Log.i(TAG, "Detached from session $sessionId")
        }
    }

    fun releaseAll() {
        for (id in effects.keys.toList()) detach(id)
    }

    /** Sessions where another app with higher priority has taken over the effect. */
    fun sessionsWithoutControl(): List<Int> =
        effects.filter { (_, dp) -> runCatching { !dp.hasControl() }.getOrDefault(true) }.keys.toList()

    /** Push new gains to every attached session. Only changed values are sent. */
    fun apply(bandGainsDb: FloatArray, inputGainDb: Float) {
        bandGainsDb.copyInto(currentBands)
        currentInputGain = inputGainDb
        if (effects.isEmpty()) return

        for (i in 0 until Bands.COUNT) {
            val g = bandGainsDb[i]
            if (appliedBands[i].isNaN() || abs(appliedBands[i] - g) > EPSILON_DB) {
                val band = DynamicsProcessing.EqBand(true, Bands.CUTOFFS[i], g)
                forEachEffect { it.setPreEqBandAllChannelsTo(i, band) }
                appliedBands[i] = g
            }
        }
        if (appliedInputGain.isNaN() || abs(appliedInputGain - inputGainDb) > EPSILON_DB) {
            forEachEffect { it.setInputGainAllChannelsTo(inputGainDb) }
            appliedInputGain = inputGainDb
        }
    }

    private inline fun forEachEffect(block: (DynamicsProcessing) -> Unit) {
        val dead = ArrayList<Int>()
        for ((id, dp) in effects) {
            try {
                block(dp)
            } catch (e: Exception) {
                Log.w(TAG, "Session $id failed, dropping it", e)
                dead.add(id)
            }
        }
        dead.forEach { detach(it) }
    }

    private fun buildConfig(): DynamicsProcessing.Config {
        val eq = DynamicsProcessing.Eq(true, true, Bands.COUNT)
        for (i in 0 until Bands.COUNT) {
            eq.setBand(i, DynamicsProcessing.EqBand(true, Bands.CUTOFFS[i], currentBands[i]))
        }
        val limiter = DynamicsProcessing.Limiter(
            /* inUse = */ true,
            /* enabled = */ true,
            /* linkGroup = */ 0,
            /* attackTime ms = */ 1f,
            /* releaseTime ms = */ 60f,
            /* ratio = */ 10f,
            /* threshold dBFS = */ -1f,
            /* postGain dB = */ 0f,
        )
        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ true, Bands.COUNT,
            /* mbcInUse = */ false, 0,
            /* postEqInUse = */ false, 0,
            /* limiterInUse = */ true,
        )
            .setPreEqAllChannelsTo(eq)
            .setLimiterAllChannelsTo(limiter)
            .setInputGainAllChannelsTo(currentInputGain)
            .build()
    }

    companion object {
        private const val TAG = "CabinEQ/Effects"
        // Highest priority so we win over Samsung's built-in effects / other EQ apps.
        private const val PRIORITY = Int.MAX_VALUE
        private const val EPSILON_DB = 0.02f
    }
}
