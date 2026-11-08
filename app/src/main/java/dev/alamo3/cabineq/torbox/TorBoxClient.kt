package dev.alamo3.cabineq.torbox

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Any failed TorBox call: HTTP error, `success: false`, timeout or unreadable response. */
class TorBoxException(
    message: String,
    /** HTTP status, or 0 if no response arrived (network error / timeout). */
    val status: Int,
    /** TorBox error code from the body, e.g. "BAD_TOKEN", "ACTIVE_LIMIT". */
    val code: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val isAuthError: Boolean
        get() = status == 401 || status == 403 || code == "BAD_TOKEN" || code == "AUTH_ERROR" || code == "NO_AUTH"
    val isRateLimited: Boolean get() = status == 429
}

data class TorBoxFile(
    val id: Int,
    /** Path inside the torrent, e.g. "Album/CD1/01 - Track.flac". */
    val name: String,
    val shortName: String,
    val size: Long,
    val mimetype: String?,
)

data class TorBoxTorrent(
    val id: Int,
    val hash: String,
    val name: String,
    val size: Long,
    val downloadState: String,
    /** 0..1 */
    val progress: Double,
    val downloadSpeed: Long,
    val eta: Long,
    val seeds: Int,
    val downloadFinished: Boolean,
    val downloadPresent: Boolean,
    val files: List<TorBoxFile>,
) {
    /** Files can be streamed once TorBox has the whole torrent on its servers. */
    val isReady: Boolean get() = downloadFinished && downloadPresent && files.isNotEmpty()
}

data class CreatedTorrent(val torrentId: Int, val hash: String?, val name: String?)

data class SearchResult(
    val hash: String,
    val title: String,
    val magnet: String,
    val size: Long,
    val seeders: Int,
    /** Null when the search API didn't report cache state. */
    val cached: Boolean?,
    val tracker: String?,
)

/**
 * Blocking client for the TorBox Main API (torrents) and Search API. Call off the main thread.
 *
 * Main API:   https://api.torbox.app/v1 + /api/torrents/...  (Bearer auth)
 * Search API: https://search-api.torbox.app                   (Bearer auth)
 */
class TorBoxClient(
    apiKey: String,
    private val baseUrl: String = API_BASE,
    private val searchBaseUrl: String = SEARCH_BASE,
) {
    private val apiKey = apiKey.trim()

    init {
        require(this.apiKey.isNotEmpty()) { "TorBox API key is missing" }
    }

    // ---------------------------------------------------------------- torrents

    /** GET /v1/api/torrents/mylist */
    fun listTorrents(bypassCache: Boolean = false, offset: Int? = null, limit: Int? = null): List<TorBoxTorrent> {
        val q = buildQuery("bypass_cache" to bypassCache, "offset" to offset, "limit" to limit)
        val data = request("GET", "$baseUrl/api/torrents/mylist$q")
        val arr = data as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseTorrent) }
    }

    /** GET /v1/api/torrents/mylist?id=… — null if TorBox doesn't know the id. */
    fun getTorrent(id: Int, bypassCache: Boolean = true): TorBoxTorrent? {
        val q = buildQuery("id" to id, "bypass_cache" to bypassCache)
        val data = try {
            request("GET", "$baseUrl/api/torrents/mylist$q")
        } catch (e: TorBoxException) {
            if (e.status == 404) return null
            throw e
        }
        return when (data) {
            is JSONObject -> parseTorrent(data)
            is JSONArray -> data.optJSONObject(0)?.let(::parseTorrent)
            else -> null
        }
    }

    /** Finds a torrent already on the account by info-hash. */
    fun findByHash(hash: String): TorBoxTorrent? =
        listTorrents(bypassCache = true).firstOrNull { it.hash.equals(hash, ignoreCase = true) }

    /** POST /v1/api/torrents/createtorrent with a magnet link. */
    fun createTorrentFromMagnet(
        magnet: String,
        name: String? = null,
        seed: Seed? = null,
        allowZip: Boolean = false,
        asQueued: Boolean? = null,
    ): CreatedTorrent {
        require(magnet.startsWith("magnet:?")) { "Not a magnet link" }
        val form = Multipart().field("magnet", magnet)
        return createTorrent(form, name, seed, allowZip, asQueued)
    }

    /** POST /v1/api/torrents/createtorrent with the bytes of a .torrent file. */
    fun createTorrentFromFile(
        torrentBytes: ByteArray,
        fileName: String = "upload.torrent",
        name: String? = null,
        seed: Seed? = null,
        allowZip: Boolean = false,
        asQueued: Boolean? = null,
    ): CreatedTorrent {
        val form = Multipart().file("file", fileName, "application/x-bittorrent", torrentBytes)
        return createTorrent(form, name, seed, allowZip, asQueued)
    }

    private fun createTorrent(form: Multipart, name: String?, seed: Seed?, allowZip: Boolean, asQueued: Boolean?): CreatedTorrent {
        name?.let { form.field("name", it) }
        seed?.let { form.field("seed", it.apiValue.toString()) }
        // Zipping would hide the individual audio files we want to stream.
        form.field("allow_zip", allowZip.toString())
        asQueued?.let { form.field("as_queued", it.toString()) }
        val data = request("POST", "$baseUrl/api/torrents/createtorrent", form) as? JSONObject
            ?: throw TorBoxException("createtorrent returned no data", 200)
        return CreatedTorrent(
            torrentId = data.optInt("torrent_id", -1).takeIf { it >= 0 }
                ?: throw TorBoxException("createtorrent returned no torrent_id", 200),
            hash = data.optStringOrNull("hash"),
            name = data.optStringOrNull("name"),
        )
    }

    /**
     * GET /v1/api/torrents/requestdl — a short-lived direct HTTPS link to one file.
     * Request it right before playback; links expire.
     */
    fun requestDownloadUrl(torrentId: Int, fileId: Int): String {
        val q = buildQuery("token" to apiKey, "torrent_id" to torrentId, "file_id" to fileId, "redirect" to false)
        val data = request("GET", "$baseUrl/api/torrents/requestdl$q")
        return (data as? String)?.takeIf { it.startsWith("http") }
            ?: throw TorBoxException("requestdl returned no link", 200)
    }

    // ---------------------------------------------------------------- search

    /**
     * Keyword search through TorBox's search engine.
     * GET https://search-api.torbox.app/torrents/search/{query}?check_cache=true
     */
    fun search(query: String, checkCache: Boolean = true): List<SearchResult> {
        val path = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val q = buildQuery("metadata" to false, "check_cache" to checkCache)
        val data = request("GET", "$searchBaseUrl/torrents/search/$path$q")
        val arr = when (data) {
            is JSONObject -> data.optJSONArray("torrents") ?: JSONArray()
            is JSONArray -> data
            else -> JSONArray()
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseSearchResult) }
            .distinctBy { it.hash.lowercase() }
    }

    // ---------------------------------------------------------------- core

    /** Performs the call and returns the envelope's `data` (JSONObject, JSONArray, String, … or null). */
    private fun request(method: String, url: String, form: Multipart? = null): Any? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val safeUrl = url.substringBefore('?')
        try {
            if (form != null) {
                val body = form.build()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=${form.boundary}")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()

            if (status !in 200..299 || json == null || !json.optBoolean("success", false)) {
                val code = json?.optStringOrNull("error") ?: if (json == null) "INVALID_RESPONSE" else null
                val detail = json?.optStringOrNull("detail") ?: code ?: text.take(200).ifBlank { "HTTP $status" }
                throw TorBoxException("TorBox $method $safeUrl → $status: $detail", status, code)
            }
            val data = json.opt("data")
            return if (data == null || data == JSONObject.NULL) null else data
        } catch (e: TorBoxException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw TorBoxException("TorBox $method $safeUrl timed out", 0, "TIMEOUT", e)
        } catch (e: IOException) {
            throw TorBoxException("TorBox $method $safeUrl failed: ${e.message}", 0, "NETWORK_ERROR", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTorrent(o: JSONObject): TorBoxTorrent {
        val filesArr = o.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesArr.length()).mapNotNull { i ->
            val f = filesArr.optJSONObject(i) ?: return@mapNotNull null
            val name = f.optStringOrNull("name") ?: f.optStringOrNull("short_name") ?: return@mapNotNull null
            TorBoxFile(
                id = f.optInt("id", i),
                name = name,
                shortName = f.optStringOrNull("short_name") ?: name.substringAfterLast('/'),
                size = f.optLong("size", 0L),
                mimetype = f.optStringOrNull("mimetype"),
            )
        }
        return TorBoxTorrent(
            id = o.optInt("id", -1),
            hash = o.optStringOrNull("hash").orEmpty(),
            name = o.optStringOrNull("name") ?: "(unnamed)",
            size = o.optLong("size", 0L),
            downloadState = o.optStringOrNull("download_state") ?: "unknown",
            progress = o.optDouble("progress", 0.0).let { if (it.isNaN()) 0.0 else it },
            downloadSpeed = o.optLong("download_speed", 0L),
            eta = o.optLong("eta", 0L),
            seeds = o.optInt("seeds", 0),
            downloadFinished = o.optBoolean("download_finished", false),
            downloadPresent = o.optBoolean("download_present", false),
            files = files,
        )
    }

    private fun parseSearchResult(o: JSONObject): SearchResult? {
        val magnet0 = o.optStringOrNull("magnet")
        val hash = o.optStringOrNull("hash") ?: magnet0?.let(::hashFromMagnet) ?: return null
        val title = o.optStringOrNull("raw_title") ?: o.optStringOrNull("title") ?: o.optStringOrNull("name") ?: hash
        val magnet = magnet0 ?: "magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(title, "UTF-8")}"
        val cached = if (o.has("cached") && !o.isNull("cached")) o.optBoolean("cached") else null
        return SearchResult(
            hash = hash,
            title = title,
            magnet = magnet,
            size = o.optLong("size", 0L),
            seeders = if (o.has("last_known_seeders")) o.optInt("last_known_seeders", 0) else o.optInt("seeders", 0),
            cached = cached,
            tracker = o.optStringOrNull("tracker"),
        )
    }

    enum class Seed(val apiValue: Int) { AUTO(1), SEED(2), NONE(3) }

    /** Minimal multipart/form-data builder. */
    private class Multipart {
        val boundary = "CabinEQ-" + UUID.randomUUID().toString()
        private val bytes = ByteArrayOutputStream()
        private val out = DataOutputStream(bytes)

        fun field(name: String, value: String) = apply {
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            out.write(value.toByteArray(Charsets.UTF_8))
            out.writeBytes("\r\n")
        }

        fun file(name: String, fileName: String, contentType: String, data: ByteArray) = apply {
            val safeName = fileName.replace("\"", "")
            out.writeBytes("--$boundary\r\n")
            out.write("Content-Disposition: form-data; name=\"$name\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8))
            out.writeBytes("Content-Type: $contentType\r\n\r\n")
            out.write(data)
            out.writeBytes("\r\n")
        }

        fun build(): ByteArray {
            out.writeBytes("--$boundary--\r\n")
            out.flush()
            return bytes.toByteArray()
        }
    }

    companion object {
        const val API_BASE = "https://api.torbox.app/v1"
        const val SEARCH_BASE = "https://search-api.torbox.app"
        private const val USER_AGENT = "CabinEQ"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        fun hashFromMagnet(magnet: String): String? =
            Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.get(1)?.lowercase()

        private fun buildQuery(vararg params: Pair<String, Any?>): String {
            val parts = params.filter { it.second != null }
                .map { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), "UTF-8") }
            return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
    }
}
