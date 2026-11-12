package dev.alamo3.cabineq

import org.json.JSONArray
import org.json.JSONObject
import dev.alamo3.cabineq.addon.EclipseAddon

/**
 * One queue entry.
 *
 * Serializable by design: a track carries what is needed to resolve it again (source plus the
 * source's ids) instead of a closure, so the queue survives process death and can be handed to
 * the MediaSession queue and, later, to the Android Auto browse tree. [TrackResolver] turns it
 * into a playable URL right before the track starts (stream links expire).
 */
data class Track(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    /** Length the source reported, so a restored queue and the car show a duration before decoding. */
    val durationMs: Long = 0,
    val source: Source,
    /** Addon track id. Null only when the addon handed out a ready URL. */
    val sourceId: String? = null,
    /** Direct, ready-to-play URL (addon search results sometimes include one). */
    val streamUrl: String? = null,
    val torrentId: Int = 0,
    val fileId: Int = 0,
    /**
     * Recording id (ISRC). The same recording shows up under many track ids (album, single,
     * remaster, other sources); this is what lets history, "For you" and dedupe agree on it.
     */
    val isrc: String? = null,
    /** A YouTube video picked from YouTube search results: played through the companion as is. */
    val youtubeId: String? = null,
) {
    enum class Source { ADDON, TORBOX }

    /**
     * Stable id for this track within its source. What the MediaSession queue and (later) the
     * Android Auto browse tree exchange with the system, so it must survive a restart.
     */
    val mediaId: String
        get() = when (source) {
            // Title and artist: discovered songs have neither id nor URL, and the same title by
            // two artists must not share a cache entry.
            Source.ADDON -> "a:" + (sourceId ?: youtubeId?.let { "yt$it" } ?: streamUrl?.let(::stableHash) ?: stableHash("$title|${artist.orEmpty()}"))
            Source.TORBOX -> "t:$torrentId:$fileId"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        artist?.let { put("artist", it) }
        album?.let { put("album", it) }
        artworkUrl?.let { put("art", it) }
        if (durationMs > 0) put("dur", durationMs)
        put("src", source.name)
        sourceId?.let { put("id", it) }
        // An addon stream URL is short-lived: don't resurrect it days later. With an id the
        // track resolves freshly; without one the URL is all we have, so keep it.
        if (source != Source.ADDON || sourceId == null) streamUrl?.let { put("url", it) }
        if (torrentId != 0) put("tid", torrentId)
        if (fileId != 0) put("fid", fileId)
        isrc?.let { put("isrc", it) }
        youtubeId?.let { put("yt", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): Track? {
            val title = o.optString("title").takeIf { it.isNotBlank() } ?: return null
            val source = runCatching { Source.valueOf(o.optString("src")) }.getOrNull() ?: Source.ADDON
            return Track(
                title = title,
                artist = o.optStringOrNull("artist"),
                album = o.optStringOrNull("album"),
                artworkUrl = o.optStringOrNull("art"),
                durationMs = o.optLong("dur").coerceAtLeast(0),
                source = source,
                sourceId = o.optStringOrNull("id"),
                streamUrl = o.optStringOrNull("url"),
                torrentId = o.optInt("tid"),
                fileId = o.optInt("fid"),
                isrc = o.optStringOrNull("isrc"),
                youtubeId = o.optStringOrNull("yt"),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

        private fun stableHash(s: String): String = Integer.toHexString(s.hashCode())
    }
}

/**
 * What the player needs to show and resume the saved queue after the process died: the queue,
 * where playback was, and how far into that track.
 */data class QueueSnapshot(
    val title: String?,
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        title?.let { put("title", it) }
        put("index", index)
        put("pos", positionMs)
        put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): QueueSnapshot? {
            val arr = o.optJSONArray("tracks") ?: return null
            val tracks = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Track::fromJson) }
            if (tracks.isEmpty()) return null
            return QueueSnapshot(
                title = o.optString("title").takeIf { it.isNotBlank() },
                tracks = tracks,
                index = o.optInt("index").coerceIn(0, tracks.lastIndex),
                positionMs = o.optLong("pos").coerceAtLeast(0),
            )
        }
    }
}

/** Our queue entry for an addon search/album result. */
fun EclipseAddon.Track.toTrack(): Track = Track(
    title = title,
    artist = artist.ifBlank { null },
    album = album,
    artworkUrl = artworkUrl,
    durationMs = (durationSec ?: 0) * 1000L,
    source = Track.Source.ADDON,
    sourceId = id,
    streamUrl = streamUrl,
    isrc = isrc,
)
