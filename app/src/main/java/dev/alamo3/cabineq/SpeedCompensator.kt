package dev.alamo3.cabineq

import kotlin.math.exp

/**
 * Turns raw (noisy, irregular) GPS speed into per-band EQ boosts.
 *
 * - Speed is smoothed with a time-constant-based EMA, so irregular GPS update
 *   intervals don't change the smoothing behaviour.
 * - The boost ramps in with a smoothstep between startKmh and fullKmh, so there is
 *   no audible "corner" when crossing the start speed.
 * - Gain changes are further slew-limited in the service, so EQ moves are inaudible.
 */
class SpeedCompensator(private val tauSeconds: Float = 3f) {
    private var smoothedKmh = 0f
    private var lastNanos = 0L

    fun reset() {
        smoothedKmh = 0f
        lastNanos = 0L
    }

    fun update(rawKmh: Float, nowNanos: Long): Float {
        if (lastNanos == 0L) {
            smoothedKmh = rawKmh
        } else {
            val dt = ((nowNanos - lastNanos) / 1e9f).coerceIn(0f, 10f)
            val alpha = 1f - exp(-dt / tauSeconds)
            smoothedKmh += alpha * (rawKmh - smoothedKmh)
        }
        lastNanos = nowNanos
        return smoothedKmh
    }

    val currentKmh: Float get() = smoothedKmh

    companion object {
        /** 0..1 amount of compensation for a given speed. */
        fun amount(kmh: Float, startKmh: Float, fullKmh: Float): Float {
            if (fullKmh <= startKmh) return if (kmh >= fullKmh) 1f else 0f
            val t = ((kmh - startKmh) / (fullKmh - startKmh)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Writes per-band boosts (dB) into [out]. */
        fun bandBoosts(amount: Float, maxBoostDb: Float, out: FloatArray) {
            for (i in out.indices) out[i] = amount * maxBoostDb * Bands.SPEED_WEIGHTS[i]
        }
    }
}
