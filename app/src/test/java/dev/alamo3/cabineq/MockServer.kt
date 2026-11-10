package dev.alamo3.cabineq

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A stand-in for TorBox's API and a Nebula-style addon, on a random local port. Canned answers
 * only: what the clients send and how they read the replies is what the tests are about.
 */
class MockServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = server.address.port
    private val streamTries = ConcurrentHashMap<String, Int>()

    init {
        server.createContext("/") { ex -> runCatching { handle(ex) }.onFailure { reply(ex, 500, """{"error":"mock crashed"}""") } }
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "mock").apply { isDaemon = true } }
        server.start()
    }

    override fun close() = server.stop(0)

    private fun reply(ex: HttpExchange, status: Int, json: String, retryAfter: String? = null) {
        val data = json.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        retryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
        ex.sendResponseHeaders(status, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }

    private fun handle(ex: HttpExchange) {
        // The raw path on purpose: the clients' percent-encoding is part of what is tested.
        val path = ex.requestURI.rawPath
        val q = query(ex)
        val body = ex.requestBody.readBytes()
        if (path.startsWith("/addon/")) return addon(ex, path.removePrefix("/addon/"), q)
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer good-key") {
            return reply(ex, 403, """{"success":false,"error":"BAD_TOKEN","detail":"Invalid token.","data":null}""")
        }
        if (ex.requestMethod == "POST") {
            if (path == "/v1/api/torrents/createtorrent") {
                val text = String(body, Charsets.ISO_8859_1)
                if ("name=\"magnet\"" in text && "dup" in text) {
                    return reply(ex, 400, """{"success":false,"error":"DOWNLOAD_EXISTS","detail":"exists","data":null}""")
                }
                return reply(ex, 200, """{"success":true,"error":null,"detail":"Found cached torrent.","data":{"torrent_id":42,"hash":"abcdef0123","name":"Some Album","auth_id":"u"}}""")
            }
            return reply(ex, 404, """{"success":false,"error":"ENDPOINT_NOT_FOUND","detail":"$path","data":null}""")
        }
        when {
            path == "/v1/api/torrents/mylist" -> when {
                "id" !in q -> reply(ex, 200, ok("[$TORRENT]"))
                q["id"] == "42" -> reply(ex, 200, ok(TORRENT))
                else -> reply(ex, 404, """{"success":false,"error":"DATABASE_ERROR","detail":"nope","data":null}""")
            }
            path == "/v1/api/torrents/requestdl" ->
                reply(ex, 200, ok("\"https://cdn.example/${q["torrent_id"]}/${q["file_id"]}?token=${q["token"]}\""))
            path.startsWith("/torrents/search/") -> reply(ex, 200, ok(SEARCH))
            path == "/v1/api/torrents/checkcached" -> {
                val good = q["format"] == "list" && q["list_files"] == "true"
                val found = q["hash"].orEmpty().split(',').mapNotNull { CACHED[it] }
                reply(ex, 200, ok(if (good) found.joinToString(",", "[", "]") else "null"))
            }
            path == "/v1/api/ratelimited" -> reply(ex, 429, """{"success":false,"error":null,"detail":"slow","data":null}""")
            else -> reply(ex, 404, """{"success":false,"error":"ENDPOINT_NOT_FOUND","detail":"$path","data":null}""")
        }
    }

    private fun ok(data: String) = """{"success":true,"error":null,"detail":"ok","data":$data}"""

    private fun addon(ex: HttpExchange, rest: String, q: Map<String, String>) {
        val token = rest.substringBefore('/')
        val path = rest.substringAfter('/', "")
        if (token != "TOKEN") return reply(ex, 401, """{"error":"bad token"}""")
        when {
            path == "manifest.json" -> reply(ex, 200, """{"id":"app.eclipse.nebula","name":"Nebula","version":"1.4.0","resources":["search",{"name":"stream"},"catalog"]}""")
            path == "search" -> reply(ex, 200, ADDON_SEARCH)
            path == "artist/ar1" -> reply(ex, 200, ARTIST)
            path.startsWith("artist/") -> reply(ex, 404, """{"error":"no artist endpoint"}""")
            path.startsWith("album/") -> reply(ex, 200, ALBUM)
            path.startsWith("stream/") -> {
                val id = path.removePrefix("stream/")
                if (id == "missing") return reply(ex, 404, """{"notice":{"message":"Not found on TorBox"}}""")
                val n = streamTries.merge(id, 1) { a, b -> a + b }!! - 1
                if (n < 2) {
                    return reply(ex, 503,
                        """{"notice":{"id":"prep","title":"Waiting on TorBox","message":"Caching","progress":${0.4 * (n + 1)}}}""",
                        retryAfter = "1")
                }
                reply(ex, 200, """{"url":"https://cdn.torbox.example/$id.flac","format":"flac","sampleRate":44100,"bitDepth":16}""")
            }
            else -> reply(ex, 404, """{"error":"nope"}""")
        }
    }

    private companion object {
        const val TORRENT = """{"id":42,"hash":"abcdef0123","name":"Some Album [FLAC]","size":98000000,"download_state":"cached","progress":1,"download_speed":0,"eta":0,"seeds":10,"download_finished":true,"download_present":true,"files":[
{"id":0,"name":"Album/CD2/01 - Opening.flac","short_name":"01 - Opening.flac","size":30000000,"mimetype":"audio/flac"},
{"id":1,"name":"Album/CD1/10 - Ten.flac","short_name":"10 - Ten.flac","size":30000000,"mimetype":"audio/flac"},
{"id":2,"name":"Album/CD1/2 - Two.flac","short_name":"2 - Two.flac","size":30000000,"mimetype":"audio/flac"},
{"id":3,"name":"Album/cover.jpg","short_name":"cover.jpg","size":500000,"mimetype":"image/jpeg"},
{"id":4,"name":"Album/MP3/01 - Opening.mp3","short_name":"01 - Opening.mp3","size":8000000,"mimetype":"audio/mpeg"}]}"""

        const val SEARCH = """{"total_torrents":3,"torrents":[
{"hash":"aaa","raw_title":"Artist - Album (2020) [MP3 320]","magnet":"magnet:?xt=urn:btih:aaa","size":100,"last_known_seeders":500,"cached":false},
{"hash":"bbb","raw_title":"Artist - Album (2020) [FLAC 24bit]","magnet":null,"size":900,"last_known_seeders":5,"cached":false},
{"raw_title":"Artist - Album WEB FLAC","magnet":"magnet:?xt=urn:btih:CCC&dn=x","size":800,"last_known_seeders":1,"cached":true},
{"hash":"aaa","raw_title":"duplicate","magnet":"magnet:?xt=urn:btih:aaa","size":1,"last_known_seeders":0}]}"""

        val CACHED = mapOf(
            "ccc" to """{"name":"Artist - Album WEB FLAC","size":800,"hash":"ccc","files":[{"name":"Album/01.flac","size":30000000},{"name":"Album/02.flac","size":30000000}]}""",
            "ddd" to """{"name":"Artist Live","size":900,"hash":"ddd","files":[{"name":"Live/concert.mkv","size":3000000000}]}""",
        )

        const val ADDON_SEARCH = """{"tracks":[{"id":"t/1","title":"Lose My Mind","artist":"Don Toliver","album":"F1","duration":212,"format":"flac"},{"id":"t2","title":"No artist"},{"title":"missing id"}],
"albums":[{"id":"al 1","title":"F1 The Album","artist":"Various","year":"2025-06-27","trackCount":"12","artworkURL":"https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.{f}"}],
"artists":[{"id":"ar1","name":"Don Toliver","artworkURL":"https://art/ar1.jpg","genres":["Hip-Hop","Rap"]},{"id":"ar2","title":"Doja Cat"},{"name":"no id"}],"query":""}"""

        const val ARTIST = """{"id":"ar1","name":"Don Toliver","artworkURL":"https://art/ar1.jpg",
"topTracks":[{"id":"tt1","title":"Top","artist":"Don Toliver"},{"id":"tt2","title":"Top2","artist":"Don Toliver","artworkURL":"https://art/tt2.jpg"}],
"albums":[{"id":"a9","title":"Hardstone Psycho","artist":"Don Toliver"}]}"""

        const val ALBUM = """{"id":"al 1","title":"F1 The Album","artist":"Various","artworkURL":"https://art/al1.jpg","tracks":[{"id":"a1","title":"One","artist":"X"},{"id":"a2","title":"Two","artist":"Y","album":"Other"}]}"""
    }
}
