package dev.alamo3.cabineq

import android.content.Context
import android.content.SharedPreferences

/** Thin wrapper over SharedPreferences. The service listens for changes and re-applies. */
class Settings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun bandGain(i: Int): Float = prefs.getFloat(bandKey(i), 0f)
    fun setBandGain(i: Int, db: Float) = prefs.edit().putFloat(bandKey(i), db).apply()

    var preampDb: Float
        get() = prefs.getFloat(KEY_PREAMP, 0f)
        set(v) = prefs.edit().putFloat(KEY_PREAMP, v).apply()

    /** When on, the preamp follows [Bands.recommendedPreamp] instead of [preampDb]. */
    var autoPreamp: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PREAMP, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_PREAMP, v).apply()

    fun bandGains(): FloatArray = FloatArray(Bands.COUNT) { bandGain(it) }

    fun effectivePreampDb(): Float =
        if (autoPreamp) Bands.recommendedPreamp(bandGains()) else preampDb

    var globalMode: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL, false)
        set(v) = prefs.edit().putBoolean(KEY_GLOBAL, v).apply()

    var speedCompEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEED_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_SPEED_ON, v).apply()

    /** Maximum boost (dB) applied at/above [speedFullKmh], before per-band weighting. */
    var speedMaxBoostDb: Float
        get() = prefs.getFloat(KEY_SPEED_MAX, 6f)
        set(v) = prefs.edit().putFloat(KEY_SPEED_MAX, v).apply()

    /** Speed at which compensation starts ramping in. */
    var speedStartKmh: Float
        get() = prefs.getFloat(KEY_SPEED_START, 30f)
        set(v) = prefs.edit().putFloat(KEY_SPEED_START, v).apply()

    /** Speed at which compensation reaches its maximum. */
    var speedFullKmh: Float
        get() = prefs.getFloat(KEY_SPEED_FULL, 110f)
        set(v) = prefs.edit().putFloat(KEY_SPEED_FULL, v).apply()

    /** Debug: use a slider value instead of GPS so the curve can be tested parked. */
    var simulateSpeed: Boolean
        get() = prefs.getBoolean(KEY_SIM_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_SIM_ON, v).apply()

    var simulatedKmh: Float
        get() = prefs.getFloat(KEY_SIM_KMH, 0f)
        set(v) = prefs.edit().putFloat(KEY_SIM_KMH, v).apply()

    fun resetEq() {
        val e = prefs.edit()
        for (i in 0 until Bands.COUNT) e.putFloat(bandKey(i), 0f)
        e.putFloat(KEY_PREAMP, 0f)
        e.apply()
    }

    companion object {
        const val PREFS_NAME = "cabin_eq"
        const val KEY_PREAMP = "preamp_db"
        const val KEY_GLOBAL = "global_mode"
        const val KEY_AUTO_PREAMP = "auto_preamp"
        const val KEY_SPEED_ON = "speed_on"
        const val KEY_SPEED_MAX = "speed_max_db"
        const val KEY_SPEED_START = "speed_start_kmh"
        const val KEY_SPEED_FULL = "speed_full_kmh"
        const val KEY_SIM_ON = "sim_on"
        const val KEY_SIM_KMH = "sim_kmh"
        fun bandKey(i: Int) = "band_$i"
    }
}
