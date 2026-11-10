package dev.alamo3.cabineq.addon

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Client for an Eclipse Music addon (protocol: https://eclipsemusic.app/docs), e.g. Nebula,
 * which searches Apple Music's catalog and streams the audio through your TorBox account.
 *
 * The user pastes the same install link they use in Eclipse, e.g.
 *   https://nebula.eclipsemusic.app/<personal token>/manifest.json
 * That link carries their TorBox key, so treat it as a secret: never log it or show it whole.
 *
 * Endpoints (relative to the base = link without /manifest.json):
 *   GET /manifest.json     → { id, name, version, resources[] }
 *   GET /search?q=…        → { tracks[], albums[], artists[], playlists[] }   (all optional)
 *   GET /album/{id}        → { id, title, artist, artworkURL, year, trackCount, tracks[] }
 *   GET /playlist/{id}     → { id, title, description, trackCount, tracks[] }
 *   GET /stream/{id}       → 200 { url, format, quality, sampleRate, bitDepth, … }
 *                            503 + Retry-After { notice: { title, message, progress } } = not ready yet
 */
class EclipseAddon(link: String) {

    /** Base URL without trailing "/manifest.json" or "/". */
    val baseUrl: String = normalize(link)

    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://localhost")) {
            "Addon link must start with https://"
        }
    }

    data class Manifest(val id: String, val name: String, val version: String, val resources: List<String>) {
        val canSearch get() = "search" in resources
        val canStream get() = "stream" in resources
    }

    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSec: Int?,
        val format: String?,
        /** Some addons include a ready stream URL in search results. */
        val streamUrl: String?,
        val artworkUrl: String? = null,
        /** Recording id: identical across releases of the same recording, unlike the track id. */
        val isrc: String? = null,
    )

    data class Album(
        val id: String,
        val title: String,
        val artist: String,
        val year: String?,
        val trackCount: Int?,
        val artworkUrl: String? = null,
    )

    data class Artist(val id: String, val name: String, val artworkUrl: String?, val genres: List<String>)

    data class Playlist(
        val id: String,
        val title: String,
        val description: String?,
        val trackCount: Int?,
        val artworkUrl: String? = null,
    )

    data class SearchResults(
        val tracks: List<Track>,
        val albums: List<Album>,
        val artists: List<Artist> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
    )

    data class AlbumDetail(val album: Album, val tracks: List<Track>)

    data class PlaylistDetail(val playlist: Playlist, val tracks: List<Track>)

    data class ArtistDetail(val artist: Artist, val topTracks: List<Track>, val albums: List<Album>)

    data class Notice(val title: String?, val message: String, val progress: Double?)

    data class Stream(
        val url: String,
        val format: String?,
        val sampleRate: Int?,
        val bitDepth: Int?,
        val codec: String?,
        /** The addon's own verdict, e.g. "lossless". There is no client-side quality choice. */
        val quality: String? = null,
    ) {
        /** "FLAC · lossless" for display until the decoder reports the real stream. */
        fun qualityLabel(): String? {
            val codec = format?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            return when {
                codec != null && quality != null -> "$codec · $quality"
                codec != null -> codec
                else -> quality
            }
        }
    }

    // ---------------------------------------------------------------- endpoints

    /** Resource list from the last [manifest] call; used to skip endpoints the addon lacks. */
    @Volatile var resources: List<String> = emptyList()
        private set

    fun manifest(): Manifest {
        val o = getJson("/manifest.json").json
            ?: throw AddonException("Addon returned no manifest", 200)
        val res = o.optJSONArray("resources")
        val resources = (0 until (res?.length() ?: 0)).mapNotNull { i ->
            // Resources are strings, or objects like { "name": "stream", … } in some addons.
            when (val r = res?.opt(i)) {
                is String -> r
                is JSONObject -> r.optStr("name")
                else -> null
            }
        }
        this.resources = resources
        return Manifest(
            id = o.optStr("id") ?: "unknown",
            name = o.optStr("name") ?: "Addon",
            version = o.optStr("version") ?: "?",
            resources = resources,
        )
    }

    /**
     * [limit] caps each result list (the protocol applies it per type; Nebula caps at 20).
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT, deep: Boolean = true): SearchResults {
        val q = query.trim()
        val primary = searchOnce(q, limit)
        if (q.isBlank()) return primary
        // The addon matches the whole string, so "title by artist" only works if the artist is
        // spelled out exactly. When part of the query matched nothing, retry with the tail of
        // the query as an artist and keep the songs whose title still matches the rest.
        // Recall costs two extra requests; as-you-type searches skip it.
        if (!deep) return primary
        val tokens = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 4) return primary
        val unmatched = tokens.filter { tok ->
            primary.tracks.none { it.title.contains(tok, true) || it.artist.contains(tok, true) } &&
                primary.albums.none { it.title.contains(tok, true) || it.artist.contains(tok, true) } &&
                primary.playlists.none { it.title.contains(tok, true) }
        }
        if (unmatched.size < 2) return primary      // every word was found somewhere: leave it

        val leading = tokens.dropLast(2)
        if (leading.size < 2) return primary
        val extraTracks = ArrayList<Track>()
        val extraAlbums = ArrayList<Album>()
        for (take in 2..3) {
            if (take > tokens.size - 2) break
            val suffix = tokens.takeLast(take).joinToString(" ")
            val r = runCatching { searchOnce(suffix, DEFAULT_LIMIT) }.getOrNull() ?: continue
            extraAlbums += r.albums.filter { a -> leading.any { a.title.contains(it, true) || a.artist.contains(it, true) } }
            extraTracks += r.tracks.filter { t ->
                // Keep only what still looks like the requested song, not the artist's other work.
                val hits = leading.count { t.title.contains(it, true) }
                hits >= 2 && hits * 2 >= leading.size
            }
        }
        if (extraTracks.isEmpty() && extraAlbums.isEmpty()) return primary
        return SearchResults(
            tracks = rank((primary.tracks + extraTracks).distinctBy { it.id }, q) { trackRank(it, q) },
            albums = rank((primary.albums + extraAlbums).distinctBy { it.id }, q) { albumRank(it, q) },
            artists = primary.artists,
            playlists = primary.playlists,
        )
    }

    /** One request, no recall tricks; used by [search] and by the artist lookups above it. */
    private fun searchOnce(q: String, limit: Int): SearchResults {
        val o = getJson("/search?q=" + enc(q) + "&limit=" + limit.coerceIn(1, MAX_LIMIT)).json
            ?: return SearchResults(emptyList(), emptyList())
        return SearchResults(
            tracks = o.optJSONArray("tracks").objects().mapNotNull(::parseTrack),
            albums = o.optJSONArray("albums").objects().mapNotNull(::parseAlbum),
            artists = o.optJSONArray("artists").objects().mapNotNull(::parseArtist),
            playlists = o.optJSONArray("playlists").objects().mapNotNull(::parsePlaylist),
        )
    }

    /** Stable sort by [score]: the addon's own (popularity) order breaks ties. */
    private fun <T> rank(items: List<T>, query: String, score: (T) -> Int): List<T> {
        if (query.isBlank() || items.size < 2) return items
        return items.sortedBy(score)
    }

    /** 0 exact, 1 prefix, 2 whole-word, 3 substring, MAX when absent. */
    private fun matchRank(text: String?, query: String): Int {
        if (text.isNullOrBlank() || query.isBlank()) return Int.MAX_VALUE
        val t = text.trim().lowercase()
        val q = query.trim().lowercase()
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            Regex("\\b" + Regex.escape(q) + "\\b").containsMatchIn(t) -> 2
            t.contains(q) -> 3
            else -> Int.MAX_VALUE
        }
    }

    /**
     * How much of the query a field contains, 0..1. Whole-string matching alone loses queries
     * like "the best is yet to come oakfield mansion": no single field holds all of it, yet the
     * artist covers the last two words and the title the rest.
     */
    private fun coverage(text: String?, tokens: List<String>): Float {
        if (text.isNullOrBlank() || tokens.isEmpty()) return 0f
        val t = text.trim().lowercase()
        return tokens.count { t.contains(it) }.toFloat() / tokens.size
    }

    private fun tokens(q: String): List<String> =
        q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

    private fun trackRank(t: Track, q: String): Int {
        val tokens = tokens(q)
        var score = 1.2f * coverage(t.title, tokens) +
            1.0f * coverage(t.artist, tokens) +
            0.3f * coverage(t.album, tokens)
        // Title coverage outweighs the artist (a song search must not become artist search), and
        // an exact phrase in the title is the strongest single signal.
        if (matchRank(t.title, q) <= 2) score += 0.6f
        return (1000 - (score * 100)).toInt()
    }

    private fun albumRank(a: Album, q: String): Int {
        val tokens = tokens(q)
        var score = 1.2f * coverage(a.title, tokens) + 0.8f * coverage(a.artist, tokens)
        if (matchRank(a.title, q) <= 2) score += 0.4f
        return (1000 - (score * 100)).toInt()
    }

    /** GET /playlist/{id} — same shape as an album, plus owner description. */
    fun playlist(id: String): PlaylistDetail {
        val o = getJson("/playlist/" + encPath(id)).json
            ?: throw AddonException("Playlist not found", 404)
        val playlist = parsePlaylist(o) ?: throw AddonException("Playlist response had no id/title", 200)
        val tracks = o.optJSONArray("tracks").objects().mapNotNull(::parseTrack).map { t ->
            t.copy(
                album = t.album ?: playlist.title,
                artworkUrl = t.artworkUrl ?: playlist.artworkUrl,
            )
        }
        return PlaylistDetail(playlist, tracks)
    }

    /**
     * GET /artist/{id} (optional in the protocol). Returns null when the addon doesn't
     * implement it, so the caller can fall back to a name search.
     */
    fun artist(id: String): ArtistDetail? {
        val o = try {
            getJson("/artist/" + encPath(id)).json
        } catch (e: AddonException) {
            if (e.status == 404 || e.status == 405 || e.status == 501) return null
            throw e
        } ?: return null
        val artist = parseArtist(o) ?: return null
        return ArtistDetail(
            artist = artist,
            topTracks = o.optJSONArray("topTracks").objects().mapNotNull(::parseTrack)
                .map { if (it.artworkUrl == null) it.copy(artworkUrl = artist.artworkUrl) else it },
            albums = o.optJSONArray("albums").objects().mapNotNull(::parseAlbum),
        )
    }

    fun album(id: String): AlbumDetail {
        val o = getJson("/album/" + encPath(id)).json ?: throw AddonException("Album not found", 404)
        val album = parseAlbum(o) ?: throw AddonException("Album response had no id/title", 200)
        val fallbackAlbum = album.title
        val tracks = o.optJSONArray("tracks").objects().mapNotNull(::parseTrack)
            .map { t ->
                t.copy(
                    album = t.album ?: fallbackAlbum,
                    // Album tracks often omit artwork; use the album cover.
                    artworkUrl = t.artworkUrl ?: album.artworkUrl,
                    artist = t.artist.ifBlank { album.artist },
                )
            }
        return AlbumDetail(album, tracks)
    }

    sealed class StreamResult {
        class Ready(val stream: Stream) : StreamResult()
        /** Not ready yet (e.g. TorBox is caching it). Retry after [retryAfterSec]. */
        class Pending(val notice: Notice?, val retryAfterSec: Int) : StreamResult()
    }

    /** One attempt at GET /stream/{id}. */
    fun streamOnce(trackId: String): StreamResult {
        val r = getJson("/stream/" + encPath(trackId), allow503 = true)
        val notice = r.json?.optJSONObject("notice")?.let(::parseNotice)
        if (r.status == 503) return StreamResult.Pending(notice, r.retryAfterSec?.coerceIn(1, 30) ?: 3)
        val o = r.json ?: throw AddonException("Stream response was empty", r.status)
        val url = o.optStr("url")
            ?: throw AddonException(notice?.message ?: "Addon has no stream for this track", r.status)
        return StreamResult.Ready(
            Stream(
                url = url,
                format = o.optStr("format"),
                sampleRate = o.optIntOrNull("sampleRate"),
                bitDepth = o.optIntOrNull("bitDepth"),
                codec = o.optStr("codec"),
                quality = o.optStr("quality"),
            )
        )
    }

    /**
     * Blocks until the addon hands back a playable URL, following its 503/Retry-After
     * "preparing" replies. [onNotice] gets each progress message. Call off the main thread.
     */
    fun resolveStream(
        trackId: String,
        maxWaitMs: Long = 5 * 60_000L,
        isCancelled: () -> Boolean = { false },
        /** False: a "not ready" reply throws [AddonNotReady] at once instead of waiting it out. */
        waitIfPending: Boolean = true,
        onNotice: (Notice?) -> Unit = {},
    ): Stream {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (true) {
            when (val r = streamOnce(trackId)) {
                is StreamResult.Ready -> return r.stream
                is StreamResult.Pending -> {
                    onNotice(r.notice)
                    if (!waitIfPending) throw AddonNotReady(r.notice, r.retryAfterSec)
                    if (System.currentTimeMillis() + r.retryAfterSec * 1000L > deadline) {
                        throw AddonException(
                            "Still not ready after ${maxWaitMs / 1000}s" + (r.notice?.message?.let { ": $it" } ?: ""), 503,
                        )
                    }
                    val wakeAt = System.currentTimeMillis() + r.retryAfterSec * 1000L
                    while (System.currentTimeMillis() < wakeAt) {
                        if (isCancelled()) throw AddonException("Cancelled", 0)
                        Thread.sleep(100)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- http

    private class Reply(val status: Int, val json: JSONObject?, val retryAfterSec: Int?)

    private fun getJson(path: String, allow503: Boolean = false): Reply {
        val url = baseUrl + path
        val safe = "addon " + path.substringBefore('?')   // never log the base: it carries the key
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CabinEQ")
        }
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val retry = conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
            if (status in 200..299 || (allow503 && status == 503)) return Reply(status, json, retry)
            val msg = json?.optJSONObject("notice")?.optStr("message")
                ?: json?.optStr("error") ?: json?.optStr("message")
                ?: text.take(200).ifBlank { "HTTP $status" }
            throw AddonException("$safe → $status: $msg", status)
        } catch (e: AddonException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AddonException("$safe timed out", 0, e)
        } catch (e: IOException) {
            throw AddonException("$safe failed: ${e.javaClass.simpleName}: ${e.message}", 0, e)
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun parseTrack(o: JSONObject): Track? {
        val id = o.optStr("id") ?: return null
        val title = o.optStr("title") ?: return null
        return Track(
            id = id,
            title = title,
            // Curated playlists come back with the artist filled in as "Unknown Artist"; an
            // empty string renders better than a lie (the file's own tags fill it in on play).
            artist = o.optStr("artist").orEmpty().takeUnless { it.equals("Unknown Artist", true) }.orEmpty(),
            album = o.optStr("album"),
            durationSec = o.optIntOrNull("duration"),
            format = o.optStr("format"),
            streamUrl = o.optStr("streamURL"),
            artworkUrl = artwork(o),
            isrc = o.optStr("isrc"),
        )
    }

    private fun parseAlbum(o: JSONObject): Album? {
        val id = o.optStr("id") ?: return null
        val title = o.optStr("title") ?: return null
        return Album(
            id = id,
            title = title,
            // Curated playlists come back with the artist filled in as "Unknown Artist"; an
            // empty string renders better than a lie (the file's own tags fill it in on play).
            artist = o.optStr("artist").orEmpty().takeUnless { it.equals("Unknown Artist", true) }.orEmpty(),
            year = o.optStr("year")?.take(4),
            trackCount = o.optIntOrNull("trackCount"),
            artworkUrl = artwork(o),
        )
    }

    private fun parsePlaylist(o: JSONObject): Playlist? {
        val id = o.optStr("id") ?: return null
        val title = o.optStr("title") ?: return null
        return Playlist(
            id = id,
            title = title,
            description = o.optStr("description"),
            trackCount = o.optIntOrNull("trackCount"),
            artworkUrl = artwork(o),
        )
    }

    private fun parseArtist(o: JSONObject): Artist? {
        val id = o.optStr("id") ?: return null
        val name = o.optStr("name") ?: o.optStr("title") ?: return null
        val genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.opt(it)?.toString() } }.orEmpty()
        return Artist(id, name, artwork(o), genres)
    }

    /** Protocol field is artworkURL; accept the variants other sources use too. */
    private fun artwork(o: JSONObject): String? = Companion.artworkFrom(o)

    private fun parseNotice(o: JSONObject): Notice? {
        val message = o.optStr("message") ?: return null
        val progress = if (o.has("progress") && !o.isNull("progress")) o.optDouble("progress", Double.NaN).takeIf { !it.isNaN() } else null
        return Notice(o.optStr("title"), message, progress)
    }

    companion object {
        /** The protocol applies limit per result type; Nebula caps it at 20. */
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50

        private val ART_KEYS = listOf(
            "artworkURL", "artworkUrl", "artwork_url", "artwork", "coverURL", "coverUrl", "cover",
            "image", "imageURL", "imageUrl", "picture", "thumbnail", "thumb", "art",
        )
        private val TIDAL_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /**
         * Finds a cover URL in an item: a plain URL string under any common key, a nested
         * object ({ url / large / … }) or array, or a bare Tidal cover id (UUID), which is
         * turned into Tidal's image URL.
         */
        fun artworkFrom(o: JSONObject): String? {
            for (k in ART_KEYS) {
                if (!o.has(k) || o.isNull(k)) continue
                urlFrom(o.opt(k))?.let { return it }
            }
            // Albums nested in tracks sometimes carry the cover.
            o.optJSONObject("album")?.let { album -> for (k in ART_KEYS) urlFrom(album.opt(k))?.let { return it } }
            return null
        }

        private fun urlFrom(v: Any?): String? = when (v) {
            is String -> v.trim().let { s ->
                when {
                    s.startsWith("https://") || s.startsWith("http://") -> s
                    s.startsWith("//") -> "https:$s"
                    TIDAL_UUID.matches(s) -> "https://resources.tidal.com/images/${s.replace('-', '/')}/640x640.jpg"
                    else -> null
                }
            }
            is JSONObject -> listOf("url", "large", "xl", "original", "src", "href", "medium", "640x640", "small")
                .firstNotNullOfOrNull { k -> if (v.has(k) && !v.isNull(k)) urlFrom(v.opt(k)) else null }
            is JSONArray -> (0 until v.length()).asSequence().mapNotNull { urlFrom(v.opt(it)) }.lastOrNull()  // usually ascending size
            else -> null
        }

        fun normalize(link: String): String =
            link.trim().removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")

        /** Host only, for display (the path holds the user's token). */
        fun displayHost(link: String): String =
            runCatching { URL(normalize(link)).host }.getOrNull() ?: "addon"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun encPath(s: String) = enc(s)

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONObject.optStr(key: String): String? =
            if (has(key) && !isNull(key)) opt(key)?.toString()?.takeIf { it.isNotBlank() } else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) opt(key).let { v ->
                when (v) {
                    is Number -> v.toInt()
                    is String -> v.toDoubleOrNull()?.toInt()
                    else -> null
                }
            } else null
    }
}

open class AddonException(message: String, val status: Int, cause: Throwable? = null) : IOException(message, cause)

/** The source answered "not ready yet" (TorBox is still caching the file) and the caller chose not to wait. */
class AddonNotReady(val notice: EclipseAddon.Notice?, val retryAfterSec: Int) :
    AddonException(notice?.message ?: "Still being prepared", 503)
