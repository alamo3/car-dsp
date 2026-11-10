package dev.alamo3.cabineq

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A stand-in for TorBox's API and a Nebula-style addon, on a random local port. Canned answers
 * only: what the clients send and how they read the replies is what the tests are about.
 *
 * A bare socket server on purpose: Android unit tests compile against android.jar, which has
 * no JDK HTTP server. One request per connection ("Connection: close") is all the clients need.
 */
class MockServer : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    private val streamTries = ConcurrentHashMap<String, Int>()
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "mock").apply { isDaemon = true } }

    /** One parsed request and the socket to answer on. */
    private class Exchange(val socket: Socket, val method: String, val rawPath: String, val rawQuery: String,
                           val headers: Map<String, String>, val body: ByteArray)

    init {
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute {
                    client.use { c ->
                        runCatching {
                            val ex = read(c) ?: return@runCatching
                            runCatching { handle(ex) }.onFailure { reply(ex, 500, """{"error":"mock crashed"}""") }
                        }
                    }
                }
            }
        }, "mock-accept").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket.close() }
        workers.shutdownNow()
    }

    private fun read(c: Socket): Exchange? {
        val input = c.getInputStream()
        // Headers end at the first blank line; read byte by byte so the body is left untouched.
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        val request = lines.firstOrNull()?.split(' ') ?: return null
        if (request.size < 2) return null
        val headers = lines.drop(1).associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(body, got, length - got)
            if (n < 0) break
            got += n
        }
        val target = request[1]
        return Exchange(c, request[0], target.substringBefore('?'), target.substringAfter('?', ""), headers, body)
    }

    private fun reply(ex: Exchange, status: Int, json: String, retryAfter: String? = null) {
        val data = json.toByteArray()
        val head = StringBuilder("HTTP/1.1 $status ${if (status in 200..299) "OK" else "Error"}\r\n")
            .append("Content-Type: application/json\r\n")
            .append("Content-Length: ${data.size}\r\n")
            .append("Connection: close\r\n")
        retryAfter?.let { head.append("Retry-After: $it\r\n") }
        head.append("\r\n")
        ex.socket.getOutputStream().apply {
            write(head.toString().toByteArray(Charsets.ISO_8859_1))
            write(data)
            flush()
        }
    }

    /** How many requests each /file/ name has had (the tests check resumes and side requests). */
    val fileHits = ConcurrentHashMap<String, Int>()

    /**
     * A 300 kB "song" whose byte at offset i is (i * 31 + 7) mod 251, so any range can be checked.
     * The name picks the behaviour: "drop" cuts the first connection short, "norange" ignores
     * Range, "slow" trickles, "expired" refuses the first request, "gone" refuses them all.
     */
    private fun file(ex: Exchange, name: String) {
        val hit = fileHits.merge(name, 1) { a, b -> a + b } ?: 1
        if (name == "gone" || (name == "expired" && hit == 1)) return reply(ex, 403, """{"error":"expired"}""")
        val size = FILE_SIZE
        val range = ex.headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
        val from = if (name == "norange") 0 else (range ?: 0)
        val partial = range != null && name != "norange"
        val head = StringBuilder(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: audio/flac\r\n")
            .append("Content-Length: ${size - from}\r\n")
            .append("Connection: close\r\n")
        if (name != "norange") head.append("Accept-Ranges: bytes\r\n")
        if (partial) head.append("Content-Range: bytes $from-${size - 1}/$size\r\n")
        head.append("\r\n")
        val out = ex.socket.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        // "drop": the first connection dies after 100 kB although it promised everything.
        val until = if (name == "drop" && hit == 1) 100 * 1024 else size
        var i = from
        val chunk = ByteArray(8 * 1024)
        while (i < until) {
            val n = minOf(chunk.size, until - i)
            for (k in 0 until n) chunk[k] = fileByte(i + k)
            out.write(chunk, 0, n)
            out.flush()
            i += n
            // "slow": the main download trickles; ranged side requests are served at full speed.
            if (name == "slow" && !partial) Thread.sleep(120)
        }
        out.flush()
    }

    private fun query(ex: Exchange): Map<String, String> =
        ex.rawQuery.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }

    private fun handle(ex: Exchange) {
        // The raw path on purpose: the clients' percent-encoding is part of what is tested.
        val path = ex.rawPath
        val q = query(ex)
        val body = ex.body
        if (path.startsWith("/addon/")) return addon(ex, path.removePrefix("/addon/"), q)
        if (path.startsWith("/file/")) return file(ex, path.removePrefix("/file/"))
        if (ex.headers["authorization"] != "Bearer good-key") {
            return reply(ex, 403, """{"success":false,"error":"BAD_TOKEN","detail":"Invalid token.","data":null}""")
        }
        if (ex.method == "POST") {
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

    private fun addon(ex: Exchange, rest: String, q: Map<String, String>) {
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

    companion object {
        const val FILE_SIZE = 300 * 1024
        fun fileByte(i: Int): Byte = ((i * 31 + 7) % 251).toByte()

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