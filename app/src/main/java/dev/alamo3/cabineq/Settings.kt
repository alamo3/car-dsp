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

    /** Process audio in our own native DSP (capture) instead of the Android effect engine. */
    var dspEngine: Boolean
        get() = prefs.getBoolean(KEY_DSP_ENGINE, true)
        set(v) = prefs.edit().putBoolean(KEY_DSP_ENGINE, v).apply()

    /**
     * Allow DSP output on the phone's own speaker. Off by default: in the car, output on the
     * phone speaker always means routing went wrong, so the app falls back instead.
     */
    var allowSpeakerOutput: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SPEAKER, false)
        set(v) = prefs.edit().putBoolean(KEY_ALLOW_SPEAKER, v).apply()

    /** Apps that block playback capture; they always use the Android effect engine. */
    var captureBlockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_CAPTURE_BLOCKED, null)?.toSet() ?: DEFAULT_CAPTURE_BLOCKED
        set(v) = prefs.edit().putStringSet(KEY_CAPTURE_BLOCKED, v).apply()

    fun markCaptureBlocked(pkgs: Collection<String>) {
        captureBlockedPackages = captureBlockedPackages + pkgs
    }

    fun resetCaptureBlocked() {
        prefs.edit().remove(KEY_CAPTURE_BLOCKED).apply()
    }

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

    /** TorBox API key (TorBox dashboard → Settings → API). Stored in app-private prefs. */
    var torboxApiKey: String
        get() = prefs.getString(KEY_TORBOX_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TORBOX_KEY, v.trim()).apply()

    /** Only show search results TorBox already has cached (instant playback). */
    var torboxCachedOnly: Boolean
        get() = prefs.getBoolean(KEY_TORBOX_CACHED_ONLY, false)
        set(v) = prefs.edit().putBoolean(KEY_TORBOX_CACHED_ONLY, v).apply()

    /**
     * Eclipse addon install link (e.g. Nebula). It embeds the user's TorBox key, so it's kept
     * in app-private prefs and only its host is ever shown.
     */
    var addonLink: String
        get() = prefs.getString(KEY_ADDON_LINK, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ADDON_LINK, v.trim()).apply()

    /** Most recent first, at most [MAX_RECENT]. */
    val recentSearches: List<String>
        get() = prefs.getString(KEY_RECENT_SEARCHES, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun addRecentSearch(query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        val list = (listOf(q) + recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT_SEARCHES, list.joinToString("\n")).apply()
    }

    fun clearRecentSearches() = prefs.edit().remove(KEY_RECENT_SEARCHES).apply()

    /** Display name from the addon's manifest, e.g. "Nebula". */
    var addonName: String
        get() = prefs.getString(KEY_ADDON_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ADDON_NAME, v).apply()

    /** Format filter for TorBox search and track picking (AudioPicker.FormatFilter name). */
    var torboxFormat: dev.alamo3.cabineq.torbox.AudioPicker.FormatFilter
        get() = runCatching {
            dev.alamo3.cabineq.torbox.AudioPicker.FormatFilter.valueOf(prefs.getString(KEY_TORBOX_FORMAT, null) ?: "ANY")
        }.getOrDefault(dev.alamo3.cabineq.torbox.AudioPicker.FormatFilter.ANY)
        set(v) = prefs.edit().putString(KEY_TORBOX_FORMAT, v.name).apply()

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
        const val KEY_DSP_ENGINE = "dsp_engine"
        const val KEY_ALLOW_SPEAKER = "allow_speaker"
        const val KEY_CAPTURE_BLOCKED = "capture_blocked"
        // Known to opt out of playback capture.
        val DEFAULT_CAPTURE_BLOCKED = setOf("com.spotify.music", "com.android.chrome")
        const val KEY_SPEED_ON = "speed_on"
        const val KEY_SPEED_MAX = "speed_max_db"
        const val KEY_SPEED_START = "speed_start_kmh"
        const val KEY_SPEED_FULL = "speed_full_kmh"
        const val KEY_SIM_ON = "sim_on"
        const val KEY_SIM_KMH = "sim_kmh"
        const val KEY_TORBOX_KEY = "torbox_api_key"
        const val KEY_TORBOX_CACHED_ONLY = "torbox_cached_only"
        const val KEY_TORBOX_FORMAT = "torbox_format"
        const val KEY_ADDON_LINK = "addon_link"
        const val KEY_ADDON_NAME = "addon_name"
        const val KEY_RECENT_SEARCHES = "recent_searches"
        const val MAX_RECENT = 8
        fun bandKey(i: Int) = "band_$i"
    }
}
