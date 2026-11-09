package dev.alamo3.cabineq

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Song lyrics from LRCLIB (https://lrclib.net): free, no key, and — unlike most sources — it
 * hands out line-level timings, which is what both the phone view and the car screen need.
 *
 * The addon has no lyrics resource (its manifest lists search/stream/catalog/settings/isrc/
 * notifications only), so this is a second, independent source. Everything is cached on disk:
 * a hit for a month, a miss for a week, so an instrumental is asked about once and never
 * again in that window, and repeat plays work with no network.
 *
 * Three outcomes, deliberately distinct:
 *   * lyrics          → [Sheet] with lines
 *   * no lyrics       → [Sheet] with no lines ("instrumental", or simply not in the database)
 *   * lookup failed   → null (network trouble: nothing cached, shown as "couldn't reach")
 *
 * [load] does I/O; never call it on the main thread.
 */
object Lyrics {

    private const val TAG = "CabinEQ/Lyrics"
    private const val BASE = "https://lrclib.net/api"
    private const val UA = "CabinEQ/0.1 (personal music player)"
    private const val HIT_TTL_MS = 30L * 24 * 3_600_000
    private const val MISS_TTL_MS = 7L * 24 * 3_600_000

    /** One lyric line. [atMs] is -1 for plain (untimed) lyrics. */
    data class Line(val atMs: Long, val text: String)

    class Sheet(val lines: List<Line>, val plain: Boolean, val source: String) {
        /** Timed, line by line: the only kind that can follow playback. */
        val synced: Boolean get() = !plain && lines.size > 1
        val empty: Boolean get() = lines.none { it.text.isNotBlank() }

        /** Index of the line being sung at [ms], or -1 before the first one / when untimed. */
        fun indexAt(ms: Long): Int {
            if (!synced) return -1
            var lo = 0
            var hi = lines.lastIndex
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lines[mid].atMs <= ms) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found
        }
    }

    private val NOTHING = Sheet(emptyList(), plain = true, source = "")

    // Hits and misses, keyed by [keyOf]; both are also on disk, this just skips the file read.
    private val hits = ConcurrentHashMap<String, Sheet>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    /** One fetch per song at a time; a second caller waits for it instead of failing. */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Lyrics for whatever [player] is on, using the file's own tags when the source gave none.
     * Null when nothing is playing or the lookup itself failed; an empty [Sheet] means the
     * song exists but has no lyrics.
     */
    fun forPlayer(context: Context, player: FilePlayer): Sheet? {
        val q = queryOf(player) ?: return null
        return load(context, q.title, q.artist, q.album, q.durationMs, q.isrc)
    }

    /** Same song resolution, cache only: lets a screen paint instantly and never block. */
    fun cachedForPlayer(context: Context, player: FilePlayer): Sheet? {
        val q = queryOf(player) ?: return null
        return cached(context, q.title, q.artist, q.durationMs, q.isrc)
    }

    private class Query(val title: String, val artist: String?, val album: String?, val durationMs: Long, val isrc: String?)

    /** The source's metadata, completed by the file's own tags when it has none. */
    private fun queryOf(player: FilePlayer): Query? {
        val t = player.currentTrack ?: return null
        val info = player.currentInfo
        val title = t.title.ifBlank { info?.title.orEmpty() }
        if (title.isBlank()) return null
        return Query(
            title = title,
            artist = t.artist ?: info?.artist,
            album = t.album ?: info?.album,
            durationMs = if (player.durationMs > 0) player.durationMs else t.durationMs,
            isrc = t.isrc,
        )
    }

    /** Cached lyrics only: safe to call when a screen is rendered, never touches the network. */
    fun cached(context: Context, title: String, artist: String?, durationMs: Long, isrc: String?): Sheet? {
        val key = keyOf(title, artist, durationMs, isrc)
        hits[key]?.let { return it }
        if (key in misses) return NOTHING
        val sheet = readDisk(context, key) ?: return null
        if (sheet.empty) {
            misses.add(key)
            return NOTHING
        }
        hits[key] = sheet
        return sheet
    }

    fun load(
        context: Context,
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long,
        isrc: String?,
    ): Sheet? {
        if (title.isBlank()) return null
        val key = keyOf(title, artist, durationMs, isrc)
        hits[key]?.let { return it }
        if (key in misses) return NOTHING
        readDisk(context, key)?.let { sheet ->
            if (sheet.empty) {
                misses.add(key)
                return NOTHING
            }
            hits[key] = sheet
            return sheet
        }
        // The phone page and the car screen can ask at the same time. Serialise per song and
        // re-check the cache inside the lock, so the second caller returns the answer the first
        // one fetched instead of telling the user the service was unreachable.
        val lock = locks.getOrPut(key) { Any() }
        try {
            synchronized(lock) {
                hits[key]?.let { return it }
                if (key in misses) return NOTHING
                readDisk(context, key)?.let { sheet ->
                    if (sheet.empty) {
                        misses.add(key)
                        return NOTHING
                    }
                    hits[key] = sheet
                    return sheet
                }
                val sheet = fetch(title, artist, album, durationMs) ?: NOTHING
                writeDisk(context, key, sheet)
                if (sheet.empty) misses.add(key) else hits[key] = sheet
                return sheet
            }
        } catch (e: Exception) {
            // Network trouble is not "this song has no lyrics": leave no cache entry so a
            // later attempt can succeed.
            Log.i(TAG, "lookup failed for \"$title\": ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            return null
        } finally {
            locks.remove(key, lock)
        }
    }

    // -------------------------------------------------------------------------------- source

    /** Marks a 404 from /api/get: the song is simply not there, which is worth remembering. */
    private object NotFound

    /**
     * Tries the exact endpoint first, then looser forms — the title the addon hands us is often
     * "So What (feat. John Coltrane, Cannonball Adderley & Bill Evans)" with the whole band in
     * the artist field, which the database has never seen in that shape.
     */
    private fun fetch(title: String, artist: String?, album: String?, durationMs: Long): Sheet? {
        val clean = cleanTitle(title)
        val main = mainArtist(artist)
        val sec = if (durationMs > 5_000) durationMs / 1000 else -1L

        val attempts = LinkedHashSet<String>()
        fun getPath(a: String?, d: Long) {
            if (a.isNullOrBlank()) return
            attempts += buildString {
                append("/get?track_name=").append(enc(clean)).append("&artist_name=").append(enc(a))
                if (d > 0) append("&duration=").append(d)
            }
        }
        getPath(main, sec)
        getPath(main, -1)
        if (artist != null && !artist.equals(main, ignoreCase = true)) getPath(artist.trim(), -1)

        for (path in attempts) {
            when (val r = get(path)) {
                is JSONObject -> parse(r)?.let { return it }
                NotFound -> Unit
                else -> Unit       // HTTP/network trouble: [get] already logged it
            }
        }

        // Search is looser still; rank by title and duration, preferring timed lyrics.
        val searches = LinkedHashSet<String>()
        if (!main.isNullOrBlank()) {
            searches += "/search?track_name=${enc(clean)}&artist_name=${enc(main)}"
        }
        searches += "/search?q=" + enc(listOfNotNull(clean, main).joinToString(" "))
        var best: Sheet? = null
        var bestScore = Double.MAX_VALUE
        for (path in searches) {
            val arr = get(path) as? JSONArray ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sheet = parse(o) ?: continue
                val candidateTitle = o.optString("trackName")
                val sameTitle = norm(candidateTitle) == norm(clean)
                val d = o.optDouble("duration", -1.0)
                val near = if (d > 0 && sec > 0) kotlin.math.abs(d - sec) else if (d > 0) 20.0 else 0.0
                if (near > 15.0 && !sameTitle) continue     // a different recording
                var score = near
                if (!sameTitle) score += 40.0              // prefer the song that was asked for
                if (sheet.synced) score -= 10.0
                if (score < bestScore) {
                    bestScore = score
                    best = sheet
                }
            }
            if (best != null) break                        // first search that produced a hit
        }
        return best
    }

    private fun get(path: String): Any? {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
        }
        try {
            return when (val code = conn.responseCode) {
                404 -> NotFound
                in 200..299 ->
                    JSONTokener(conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }).nextValue()
                else -> throw java.io.IOException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * [JSONObject.optString] turns a JSON null into the string "null" (and `has` is true for
     * explicit nulls), which once painted a page whose only lyric line was the word "null".
     */
    private fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun parse(o: JSONObject): Sheet? {
        str(o, "syncedLyrics")?.let { synced ->
            val lines = parseLrc(synced)
            if (lines.size > 1) return Sheet(lines, plain = false, source = "LRCLIB")
        }
        str(o, "plainLyrics")?.let { plain ->
            val lines = plain.lines().map { Line(-1, it.trim()) }
            if (lines.any { it.text.isNotBlank() }) return Sheet(lines, plain = true, source = "LRCLIB")
        }
        if (o.optBoolean("instrumental")) return Sheet(emptyList(), plain = true, source = "LRCLIB")
        return null
    }

    /** `[mm:ss.xx] text`, several stamps per line allowed, `<mm:ss.xx>` word timings dropped. */
    private val STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_STAMP = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

    private fun parseLrc(text: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in text.lines()) {
            val stamps = STAMP.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val line = WORD_STAMP.replace(raw.substring(stamps.last().range.last + 1), "").trim()
            if (line.isEmpty()) continue
            for (m in stamps) {
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out += Line(m.groupValues[1].toLong() * 60_000L + m.groupValues[2].toLong() * 1000L + ms, line)
            }
        }
        return out.sortedBy { it.atMs }
    }

    // ------------------------------------------------------------------------ query cleaning

    private val BRACKETS = Regex("""\s*[\(\[][^)\]]*[)\]]""")
    private val VERSION_TAG = Regex(
        """\s+-\s+(?:\d{4}\s+)?(?:digital\s+)?(?:remaster(?:ed)?|remastered\s+version|live|radio\s+edit|single\s+version|bonus(?:\s+track)?|mono|stereo|deluxe|album\s+version|explicit)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun cleanTitle(s: String): String {
        val t = s.replace(BRACKETS, "").replace(VERSION_TAG, "").trim()
        return t.ifBlank { s.trim() }
    }

    /** "Miles Davis, John Coltrane, …" → "Miles Davis" (commas only: "Simon & Garfunkel" stays). */
    private fun mainArtist(a: String?): String? =
        a?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }

    // --------------------------------------------------------------------------------- cache

    /** ISRC is the stable identity; the fallback ignores the album so a compilation still hits. */
    private fun keyOf(title: String, artist: String?, durationMs: Long, isrc: String?): String {
        isrc?.takeIf { it.isNotBlank() }?.let { return "isrc:" + it.trim().uppercase() }
        val sec = if (durationMs > 5_000) (durationMs / 1000).toString() else "?"
        return norm(title) + "|" + norm(artist.orEmpty()) + "|" + sec
    }

    private fun norm(s: String): String = cleanTitle(s).lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun dir(context: Context) = File(context.filesDir, "lyrics").apply { mkdirs() }

    private fun file(context: Context, key: String): File =
        File(dir(context), Integer.toHexString(key.hashCode()) + "-" + key.length + ".json")

    private fun readDisk(context: Context, key: String): Sheet? {
        val f = file(context, key)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            // A hashCode collision would otherwise serve another song's lyrics for a month.
            if (!o.has("key") || o.optString("key") != key) return null
            val ttl = if (o.optBoolean("miss")) MISS_TTL_MS else HIT_TTL_MS
            if (System.currentTimeMillis() - o.optLong("at") > ttl) return null
            if (o.optBoolean("miss")) return NOTHING
            val arr = o.optJSONArray("lines") ?: return null
            val lines = (0 until arr.length()).mapNotNull { i ->
                val l = arr.optJSONObject(i) ?: return@mapNotNull null
                Line(l.optLong("at"), l.optString("text"))
            }
            Sheet(lines, plain = o.optBoolean("plain"), source = o.optString("source", "LRCLIB"))
        } catch (e: Exception) {
            Log.i(TAG, "unreadable cache ${f.name}: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun writeDisk(context: Context, key: String, sheet: Sheet) {
        val o = JSONObject().put("at", System.currentTimeMillis()).put("key", key)
        if (sheet.empty) {
            o.put("miss", true)
        } else {
            o.put("plain", sheet.plain).put("source", sheet.source)
            val arr = JSONArray()
            for (l in sheet.lines) arr.put(JSONObject().put("at", l.atMs).put("text", l.text))
            o.put("lines", arr)
        }
        runCatching { file(context, key).writeText(o.toString()) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
