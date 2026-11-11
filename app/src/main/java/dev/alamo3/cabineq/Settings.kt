package dev.alamo3.cabineq

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Thin wrapper over SharedPreferences. The service listens for changes and re-applies. */
class Settings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Credentials (TorBox key, Nebula link — which embeds the key) live in their own file, which
     * the backup rules exclude: the rest of the settings may go to the cloud, these may not.
     */
    private val secrets: SharedPreferences =
        context.applicationContext.getSharedPreferences(SECRETS_NAME, Context.MODE_PRIVATE)
            .also { migrateSecrets(it) }

    private fun migrateSecrets(to: SharedPreferences) {
        if (!prefs.contains(KEY_TORBOX_KEY) && !prefs.contains(KEY_ADDON_LINK)) return
        val e = to.edit()
        prefs.getString(KEY_TORBOX_KEY, null)?.let { if (!to.contains(KEY_TORBOX_KEY)) e.putString(KEY_TORBOX_KEY, it) }
        prefs.getString(KEY_ADDON_LINK, null)?.let { if (!to.contains(KEY_ADDON_LINK)) e.putString(KEY_ADDON_LINK, it) }
        // Write the new copy before dropping the old one, so a crash in between loses nothing.
        if (e.commit()) prefs.edit().remove(KEY_TORBOX_KEY).remove(KEY_ADDON_LINK).apply()
    }

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
    /**
     * Shifts timed lyrics: positive shows lines earlier. For what the app can't measure — a
     * Bluetooth link's delay, a car screen's refresh.
     */
    var lyricsOffsetMs: Int
        get() = prefs.getInt("lyrics_offset_ms", 0)
        set(v) = prefs.edit().putInt("lyrics_offset_ms", v.coerceIn(-5000, 5000)).apply()

    var torboxApiKey: String
        get() = secrets.getString(KEY_TORBOX_KEY, "") ?: ""
        set(v) = secrets.edit().putString(KEY_TORBOX_KEY, v.trim()).apply()

    /** Only show search results TorBox already has cached (instant playback). */
    var torboxCachedOnly: Boolean
        get() = prefs.getBoolean(KEY_TORBOX_CACHED_ONLY, false)
        set(v) = prefs.edit().putBoolean(KEY_TORBOX_CACHED_ONLY, v).apply()

    /**
     * Eclipse addon install link (e.g. Nebula). It embeds the user's TorBox key, so it's kept
     * in app-private prefs and only its host is ever shown.
     */
    var addonLink: String
        get() = secrets.getString(KEY_ADDON_LINK, "") ?: ""
        set(v) = secrets.edit().putString(KEY_ADDON_LINK, v.trim()).apply()

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

    /**
     * Loudness matching: one gain per song so songs play at the same level. On by default —
     * radio mixes many masters, and the jumps between them are the first thing heard.
     */
    var loudnessMatch: Boolean
        get() = prefs.getBoolean("loudness_match", true)
        set(v) = prefs.edit().putBoolean("loudness_match", v).apply()

    /**
     * Play the current song from its download as it arrives (resumes after signal drops, keeps
     * the song for next time) instead of the platform's own stream reader. A switch so the old
     * path is one tap away if a file or a phone misbehaves with it.
     */
    var progressivePlayback: Boolean
        get() = prefs.getBoolean("progressive_playback", true)
        set(v) = prefs.edit().putBoolean("progressive_playback", v).apply()

    /**
     * Play through Media3 (ExoPlayer) instead of the app's own decode loop. On unless it has been
     * turned off: it does the fetching, buffering and the hand-over between songs, and the DSP
     * engine is the same either way. An explicit "off" is kept, so the built-in path stays one
     * tap away for a file or a phone that misbehaves with it.
     */
    var media3Player: Boolean
        get() = prefs.getBoolean(KEY_MEDIA3, MEDIA3_DEFAULT)
        set(v) = prefs.edit().putBoolean(KEY_MEDIA3, v).apply()

    /**
     * Ask decoders for 32-bit float instead of their own 16-bit output (Media3 engine only), so
     * 24-bit files reach the DSP at full precision. Off by default: a decoder that accepts the
     * request but still produces 16-bit is heard as noise, and the declared format is all there
     * is to go by.
     */
    var floatDecoding: Boolean
        get() = prefs.getBoolean("float_decoding", false)
        set(v) = prefs.edit().putBoolean("float_decoding", v).apply()

    /**
     * When a queue runs out, carry on with radio picks from where it ended, whatever was played
     * (a playlist, an album, a shelf, a search). On by default. Off: the queue just ends, unless
     * the user started a radio on purpose ("Start radio from this song").
     */
    var endlessPlayback: Boolean
        get() = prefs.getBoolean("endless_playback", true)
        set(v) = prefs.edit().putBoolean("endless_playback", v).apply()

    /**
     * 4th-order high-pass at 30 Hz, before the EQ. On by default: a boosted shelf in front of
     * nothing spends its headroom on frequencies a car door cannot reproduce, and the limiter
     * then pulls the audible bass down with them.
     */
    var subsonicFilter: Boolean
        get() = prefs.getBoolean(KEY_SUBSONIC, true)
        set(v) = prefs.edit().putBoolean(KEY_SUBSONIC, v).apply()

    /** Prefetch only on Wi-Fi/Ethernet. Off by default: the user decides. */
    var cacheWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_CACHE_WIFI_ONLY, false)
        set(v) = prefs.edit().putBoolean(KEY_CACHE_WIFI_ONLY, v).apply()

    /** Cabin correction curve saved under a level name ("Quiet", "Normal", "Loud"). */
    fun cabinCurve(name: String): String? =
        if (name.isBlank()) null else prefs.getString("cabin_curve_$name", null)

    fun setCabinCurve(name: String, json: String?) {
        if (name.isBlank()) return
        prefs.edit().apply { if (json == null) remove("cabin_curve_$name") else putString("cabin_curve_$name", json) }.apply()
    }

    /** Which saved correction is applied, or blank for none. */
    var cabinActive: String
        get() = prefs.getString("cabin_active", "") ?: ""
        set(v) = prefs.edit().putString("cabin_active", v).apply()

    /** How much of the correction to apply, 0..1. */
    var cabinAmount: Float
        get() = prefs.getFloat("cabin_amount", 1f)
        set(v) = prefs.edit().putFloat("cabin_amount", v.coerceIn(0f, 1f)).apply()

    var cabinBypass: Boolean
        get() = prefs.getBoolean("cabin_bypass", false)
        set(v) = prefs.edit().putBoolean("cabin_bypass", v).apply()

    /** Playlists imported from another service, so they can be listed and forgotten. */
    fun importedPlaylists(): List<String> =
        (prefs.getStringSet(KEY_IMPORTED_PLAYLISTS, emptySet()) ?: emptySet()).sorted()

    fun addImportedPlaylist(name: String) {
        val set = HashSet(prefs.getStringSet(KEY_IMPORTED_PLAYLISTS, emptySet()) ?: emptySet())
        if (set.add(name.trim().take(80))) {
            prefs.edit().putStringSet(KEY_IMPORTED_PLAYLISTS, set).apply()
        }
    }

    fun clearImportedPlaylists() {
        prefs.edit().remove(KEY_IMPORTED_PLAYLISTS).apply()
    }

    /**
     * Low-pass at ~15.5 kHz when the output is Bluetooth: A2DP cannot reproduce that band, so
     * what reaches the listener up there is codec noise — heard as crackle on bright material.
     */
    var bluetoothHfGuard: Boolean
        get() = prefs.getBoolean(KEY_BT_HF_GUARD, true)
        set(v) = prefs.edit().putBoolean(KEY_BT_HF_GUARD, v).apply()

    /**
     * In car mode, raise the media stream to maximum so the mixer does not attenuate the
     * signal before it leaves the phone; restored when car mode ends.
     */
    var maxVolumeInCar: Boolean
        get() = prefs.getBoolean(KEY_MAX_VOLUME_IN_CAR, true)
        set(v) = prefs.edit().putBoolean(KEY_MAX_VOLUME_IN_CAR, v).apply()

    /** Also do it when the audio is going to the phone's own speaker (usually a bad idea). */
    var maxVolumeIncludeSpeaker: Boolean
        get() = prefs.getBoolean(KEY_MAX_VOLUME_SPEAKER, false)
        set(v) = prefs.edit().putBoolean(KEY_MAX_VOLUME_SPEAKER, v).apply()

    /** Volume index to put back when car mode ends; -1 when nothing was changed. */
    var preCarVolume: Int
        get() = prefs.getInt(KEY_PRE_CAR_VOLUME, -1)
        set(v) = prefs.edit().putInt(KEY_PRE_CAR_VOLUME, v).apply()

    /**
     * Emit at the rate the device mixes at, resampling in our own DSP when the source differs.
     * On: the Android mixer's resampler is out of the path. Off: the file's own rate goes to
     * the mixer and the platform converts it.
     */
    var matchDeviceRate: Boolean
        get() = prefs.getBoolean(KEY_MATCH_DEVICE_RATE, true)
        set(v) = prefs.edit().putBoolean(KEY_MATCH_DEVICE_RATE, v).apply()

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
        /** Excluded from backup in res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val SECRETS_NAME = "cabin_eq_secrets"
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
        const val KEY_BT_HF_GUARD = "bluetooth_hf_guard"
        const val KEY_SUBSONIC = "subsonic_filter"
        const val KEY_IMPORTED_PLAYLISTS = "imported_playlists"
        const val KEY_MAX_VOLUME_IN_CAR = "max_volume_in_car"
        const val KEY_MAX_VOLUME_SPEAKER = "max_volume_speaker"
        const val KEY_PRE_CAR_VOLUME = "pre_car_volume"
        const val KEY_MATCH_DEVICE_RATE = "match_device_rate"
        const val KEY_MEDIA3 = "media3_player"
        /** Media3 is the player unless it was deliberately switched off. */
        const val MEDIA3_DEFAULT = true
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
