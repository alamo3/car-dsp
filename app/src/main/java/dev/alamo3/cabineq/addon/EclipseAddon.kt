package dev.alamo3.cabineq.addon

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

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
 *   GET /stream/{id}       → 200 { url, format, sampleRate, bitDepth, … }
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

    data class SearchResults(
        val tracks: List<Track>,
        val albums: List<Album>,
        val artists: List<Artist> = emptyList(),
    )

    data class AlbumDetail(val album: Album, val tracks: List<Track>)

    data class ArtistDetail(val artist: Artist, val topTracks: List<Track>, val albums: List<Album>)

    data class Notice(val title: String?, val message: String, val progress: Double?)

    data class Stream(
        val url: String,
        val format: String?,
        val sampleRate: Int?,
        val bitDepth: Int?,
        val codec: String?,
    )

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

    fun search(query: String): SearchResults {
        val o = getJson("/search?q=" + enc(query.trim())).json ?: return SearchResults(emptyList(), emptyList())
        return SearchResults(
            tracks = o.optJSONArray("tracks").objects().mapNotNull(::parseTrack),
            albums = o.optJSONArray("albums").objects().mapNotNull(::parseAlbum),
            artists = o.optJSONArray("artists").objects().mapNotNull(::parseArtist),
        )
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
        onNotice: (Notice?) -> Unit = {},
    ): Stream {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (true) {
            when (val r = streamOnce(trackId)) {
                is StreamResult.Ready -> return r.stream
                is StreamResult.Pending -> {
                    onNotice(r.notice)
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
            artist = o.optStr("artist") ?: "",
            album = o.optStr("album"),
            durationSec = o.optIntOrNull("duration"),
            format = o.optStr("format"),
            streamUrl = o.optStr("streamURL"),
            artworkUrl = artwork(o),
        )
    }

    private fun parseAlbum(o: JSONObject): Album? {
        val id = o.optStr("id") ?: return null
        val title = o.optStr("title") ?: return null
        return Album(
            id = id,
            title = title,
            artist = o.optStr("artist") ?: "",
            year = o.optStr("year")?.take(4),
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

    /** Protocol field is artworkURL; accept common variants too. */
    private fun artwork(o: JSONObject): String? =
        o.optStr("artworkURL") ?: o.optStr("artworkUrl") ?: o.optStr("artwork") ?: o.optStr("image")

    private fun parseNotice(o: JSONObject): Notice? {
        val message = o.optStr("message") ?: return null
        val progress = if (o.has("progress") && !o.isNull("progress")) o.optDouble("progress", Double.NaN).takeIf { !it.isNaN() } else null
        return Notice(o.optStr("title"), message, progress)
    }

    companion object {
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

class AddonException(message: String, val status: Int, cause: Throwable? = null) : IOException(message, cause)
