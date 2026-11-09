package dev.alamo3.cabineq

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Thin wrapper over SharedPreferences. The service listens for changes and re-applies. */
class Settings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Guards read-modify-write of the string lists: search threads, the player and the car. */
    private val recentSearchesLock = Any()
    private val historyLock = Any()

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

    fun addRecentSearch(query: String) = synchronized(recentSearchesLock) {
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        val list = (listOf(q) + recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT_SEARCHES, list.joinToString("\n")).apply()
    }

    fun clearRecentSearches() = synchronized(recentSearchesLock) {
        prefs.edit().remove(KEY_RECENT_SEARCHES).apply()
    }

    /** Display name from the addon's manifest, e.g. "Nebula". */
    var addonName: String
        get() = prefs.getString(KEY_ADDON_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ADDON_NAME, v).apply()

    /**
     * The artist the current queue should keep going from. Persisted with the queue snapshot so
     * the one-ahead refill still works after Android kills and restarts the process.
     */
    var radioSeed: Track?
        get() = prefs.getString(KEY_RADIO_SEED, null)?.let {
            runCatching { Track.fromJson(JSONObject(it)) }.getOrNull()
        }
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_RADIO_SEED) else putString(KEY_RADIO_SEED, v.toJson().toString())
        }.apply()

    /** Prefetch the next queued track to disk so track changes are instant. */
    var cacheEnabled: Boolean
        get() = prefs.getBoolean(KEY_CACHE_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_CACHE_ENABLED, v).apply()

    /** Prefetch only on Wi-Fi/Ethernet. Off by default: the user decides. */
    var cacheWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_CACHE_WIFI_ONLY, false)
        set(v) = prefs.edit().putBoolean(KEY_CACHE_WIFI_ONLY, v).apply()

    /** Audio cache cap in megabytes. */
    var cacheMaxMb: Int
        get() = prefs.getInt(KEY_CACHE_MAX_MB, 2048)
        set(v) = prefs.edit().putInt(KEY_CACHE_MAX_MB, v.coerceIn(128, 65536)).apply()

    /** Queue loop mode, persisted so it survives a restart (FilePlayer.Repeat name). */
    var repeatMode: String
        get() = prefs.getString(KEY_REPEAT, "OFF") ?: "OFF"
        set(v) = prefs.edit().putString(KEY_REPEAT, v).apply()

    /** The jam session this phone hosted last, so an orphaned one can be cleaned up on restart. */
    var lastJamSession: String?
        get() = prefs.getString(KEY_JAM_SESSION, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_JAM_SESSION) else putString(KEY_JAM_SESSION, v)
        }.apply()

    /** Format filter for TorBox search and track picking (AudioPicker.FormatFilter name). */
    var torboxFormat: dev.alamo3.cabineq.torbox.AudioPicker.FormatFilter
        get() = runCatching {
            dev.alamo3.cabineq.torbox.AudioPicker.FormatFilter.valueOf(prefs.getString(KEY_TORBOX_FORMAT, null) ?: "ANY")
        }.getOrDefault(dev.alamo3.cabineq.torbox.AudioPicker.FormatFilter.ANY)
        set(v) = prefs.edit().putString(KEY_TORBOX_FORMAT, v.name).apply()

    /**
     * The last queue and where playback was, so a relaunch can resume it. Written with commit()
     * from the player's own background thread: apply() can be lost if the process dies right
     * after a track change, which is exactly when this matters.
     */
    var queueSnapshot: QueueSnapshot?
        get() = prefs.getString(KEY_QUEUE, null)?.let {
            runCatching { QueueSnapshot.fromJson(JSONObject(it)) }.getOrNull()
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove(KEY_QUEUE) else e.putString(KEY_QUEUE, v.toJson().toString())
            e.commit()
        }

    /**
     * The same song reaches us as an album track, a single, a remaster or a search hit — ISRC is
     * the only id that agrees across those, so prefer it and fall back to source id + title.
     */
    private fun sameRecording(a: Track, b: Track): Boolean =
        if (a.isrc != null && b.isrc != null) a.isrc.equals(b.isrc, ignoreCase = true)
        else a.mediaId == b.mediaId && a.title.equals(b.title, ignoreCase = true)

    /**
     * Most recently played first, across sessions. This is what makes "Recently played" real
     * (not just the current queue) and what the car's "For you" row is built from.
     */
    fun playHistory(): List<Track> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Track::fromJson) }
        }.getOrDefault(emptyList())
    }

    /** Called when a track starts; duplicates move to the front instead of piling up. */
    fun recordPlayed(track: Track) = synchronized(historyLock) {
        if (track.title.isBlank()) return@synchronized
        val kept = playHistory().filterNot { sameRecording(it, track) }
        val list = (listOf(track) + kept).take(MAX_HISTORY)
        val json = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_HISTORY, json.toString()).apply()
    }

    /** Drops a track from "Recently played" (e.g. it was skipped after two seconds). */
    fun removeFromHistory(track: Track) = synchronized(historyLock) {
        val history = playHistory()
        val kept = history.filterNot { sameRecording(it, track) }
        if (kept.size == history.size) return@synchronized
        val json = JSONArray().apply { kept.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_HISTORY, json.toString()).apply()
    }

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
        const val KEY_JAM_SESSION = "jam_last_session"
        const val KEY_RADIO_SEED = "radio_seed"
        const val KEY_REPEAT = "repeat_mode"
        const val KEY_CACHE_ENABLED = "cache_enabled"
        const val KEY_CACHE_WIFI_ONLY = "cache_wifi_only"
        const val KEY_CACHE_MAX_MB = "cache_max_mb"
        const val KEY_TORBOX_FORMAT = "torbox_format"
        const val KEY_ADDON_LINK = "addon_link"
        const val KEY_ADDON_NAME = "addon_name"
        const val KEY_RECENT_SEARCHES = "recent_searches"
        const val KEY_QUEUE = "queue_snapshot"
        const val KEY_HISTORY = "play_history"
        const val MAX_HISTORY = 40
        const val MAX_RECENT = 8
        fun bandKey(i: Int) = "band_$i"
    }
}
