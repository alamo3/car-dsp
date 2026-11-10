package dev.alamo3.cabineq.music

import android.util.Log
import dev.alamo3.cabineq.Track
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Where new music comes from: Deezer's public catalogue API (no account, no key).
 *
 * The addon can search, but it has no idea which artists sound alike; Deezer does. It is only an
 * index here — nothing is streamed from it. Its tracks come back with no addon id, and
 * [AddonMatch] finds the same recording on Nebula right before it plays.
 *
 * Endpoints: /search/artist, /artist/{id}/related, /artist/{id}/radio, /artist/{id}/top.
 * The API allows ~50 requests per 5 s; one pool build uses about 25.
 */
object Discover {
    private const val TAG = "CabinEQ/Discover"
    private const val API = "https://api.deezer.com"

    /** Artist name (lowercase) -> Deezer id; null means "looked up, not found". */
    private val ids = ConcurrentHashMap<String, Long>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "CabinEQ-Discover").apply { isDaemon = true }
    }

    data class SimilarArtist(val id: Long, val name: String)

    fun artistId(name: String): Long? {
        val k = name.trim().lowercase()
        if (k.isEmpty() || k in missing) return null
        ids[k]?.let { return it }
        val o = get("/search/artist?q=" + URLEncoder.encode(name.trim(), "UTF-8") + "&limit=5") ?: return null
        val data = o.optJSONArray("data")
        var found: Long? = null
        if (data != null) {
            // Prefer the exact name; otherwise trust Deezer's ranking (most fans first).
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                if (a.optString("name").trim().equals(name.trim(), ignoreCase = true)) {
                    found = a.optLong("id"); break
                }
            }
            if (found == null) found = data.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
        }
        if (found == null) missing.add(k) else ids[k] = found
        return found
    }

    /** Artists Deezer considers similar, most similar first. */
    fun related(artistId: Long, limit: Int = 10): List<SimilarArtist> {
        val o = get("/artist/$artistId/related?limit=$limit") ?: return emptyList()
        val data = o.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val a = data.optJSONObject(i) ?: return@mapNotNull null
            val id = a.optLong("id")
            val n = a.optString("name")
            if (id <= 0 || n.isBlank()) null else SimilarArtist(id, n).also { ids.putIfAbsent(n.trim().lowercase(), id) }
        }
    }

    /** Deezer's own "radio" for an artist: their pick of tracks around that sound. */
    fun radio(artistId: Long): List<Track> = tracks("/artist/$artistId/radio?limit=40")

    fun top(artistId: Long, limit: Int = 5): List<Track> = tracks("/artist/$artistId/top?limit=$limit")

    /** [top] for several artists at once, in parallel; failures are just missing. */
    fun topOf(artists: List<SimilarArtist>, perArtist: Int): List<Track> {
        if (artists.isEmpty()) return emptyList()
        val futures = artists.map { a -> pool.submit<List<Track>> { top(a.id, perArtist) } }
        return futures.flatMap { f ->
            runCatching { f.get(15, TimeUnit.SECONDS) }.getOrDefault(emptyList())
        }
    }

    /** A song as the search index knows it; [rank] is Deezer's popularity (0..~1,000,000). */
    data class TrackHit(val title: String, val artist: String, val rank: Long)

    /**
     * Typo-tolerant song search: what the user probably meant, best first. This is only used to
     * understand the query — results still come from the addon.
     */
    fun searchTracks(query: String, limit: Int = 8): List<TrackHit> {
        val o = get("/search?q=" + URLEncoder.encode(query.trim(), "UTF-8") + "&limit=$limit") ?: return emptyList()
        val data = o.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val t = data.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title_short").ifBlank { t.optString("title") }.trim()
            val artist = t.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
            if (title.isEmpty() || artist.isEmpty()) null else TrackHit(title, artist, t.optLong("rank"))
        }
    }

    /** Artist names matching the query, best first. */
    fun searchArtists(query: String, limit: Int = 3): List<String> {
        val o = get("/search/artist?q=" + URLEncoder.encode(query.trim(), "UTF-8") + "&limit=$limit") ?: return emptyList()
        val data = o.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun tracks(path: String): List<Track> {
        val o = get(path) ?: return emptyList()
        val data = o.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.let(::trackFrom) }
    }

    /**
     * An index entry, not a playable track: no addon id yet ([AddonMatch] supplies one). The
     * plain title (no "Remastered 2011") matches the addon's catalogue better.
     */
    internal fun trackFrom(o: JSONObject): Track? {
        if (o.has("readable") && !o.optBoolean("readable", true)) return null
        val title = o.optString("title_short").ifBlank { o.optString("title") }.trim()
        val artist = o.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
        if (title.isEmpty() || artist.isEmpty()) return null
        val album = o.optJSONObject("album")
        return Track(
            title = title,
            artist = artist,
            album = album?.optString("title")?.trim()?.ifBlank { null },
            artworkUrl = album?.let { a ->
                listOf("cover_xl", "cover_big", "cover_medium").map { a.optString(it) }
                    .firstOrNull { it.startsWith("https://") }
            },
            durationMs = o.optLong("duration").coerceAtLeast(0) * 1000L,
            source = Track.Source.ADDON,
        )
    }

    private fun get(path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "GET $path -> HTTP ${conn.responseCode}")
                return null
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            // Deezer reports errors (quota, bad id) as 200 with an "error" object.
            o.optJSONObject("error")?.let {
                Log.w(TAG, "GET $path -> ${it.optString("type")}: ${it.optString("message")}")
                return null
            }
            o
        } catch (e: Exception) {
            Log.w(TAG, "GET $path failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
