package dev.alamo3.cabineq.yt

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube Music's own song search: the request its website makes, answered in well under a second
 * over a plain web request (no yt-dlp process). Results are songs as YouTube Music lists them,
 * official audio with title, artists, album, length and cover.
 *
 * Unofficial: if YouTube changes its shape, [search] returns an empty list or throws and callers
 * fall back to yt-dlp's slower search. Only searching happens here; the audio link (the part
 * YouTube guards and keeps changing) always comes from yt-dlp.
 */
object YtmSearch {
    private const val TAG = "CabinEQYt/Ytm"
    private const val URL_SEARCH = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val CLIENT_VERSION = "1.20251101.01.00"
    /** "Songs" filter, as the website sends it. */
    private const val SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"

    data class Song(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSec: Int?,
        val thumbnail: String?,
    )

    fun search(query: String, limit: Int = 20): List<Song> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "en")
                .put("gl", "US")))
            .put("query", query)
            .put("params", SONGS)
        val conn = (URL(URL_SEARCH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            setRequestProperty("X-YouTube-Client-Name", "67")
            setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "search -> HTTP ${conn.responseCode}")
                return emptyList()
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Song>()
            collect(json) { item -> parse(item)?.let { if (out.none { s -> s.videoId == it.videoId }) out += it } }
            return out.take(limit)
        } finally {
            conn.disconnect()
        }
    }

    /** Every list item in the answer, wherever the current layout puts it. */
    private fun collect(node: Any?, onItem: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { onItem(it); return }
                node.keys().forEach { collect(node.opt(it), onItem) }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), onItem)
        }
    }

    private fun parse(item: JSONObject): Song? {
        val columns = item.optJSONArray("flexColumns") ?: return null
        fun runs(i: Int): JSONArray? = columns.optJSONObject(i)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")
        val titleRuns = runs(0) ?: return null
        val title = titleRuns.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() } ?: return null
        val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: titleRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: return null
        val artists = ArrayList<String>()
        var album: String? = null
        var duration: Int? = null
        val meta = runs(1)
        if (meta != null) for (i in 0 until meta.length()) {
            val r = meta.optJSONObject(i) ?: continue
            val text = r.optString("text").trim()
            val page = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
            when {
                page.endsWith("ARTIST") -> artists += text
                page.endsWith("ALBUM") -> album = text
                Regex("^\\d{1,2}(:\\d{2}){1,2}$").matches(text) ->
                    duration = text.split(':').fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }
            }
        }
        // Some layouts put the artist without a link: the first plain run before a separator.
        if (artists.isEmpty() && meta != null) {
            meta.optJSONObject(0)?.optString("text")?.trim()
                ?.takeIf { it.isNotBlank() && it != "Song" && it != "•" }?.let { artists += it }
        }
        val thumbs = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
            ?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
        return Song(videoId, title, artists.joinToString(", ").ifBlank { "Unknown artist" }, album, duration, thumb)
    }
}
