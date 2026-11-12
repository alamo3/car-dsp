package dev.alamo3.cabineq.yt

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * yt-dlp on the phone: finds the official audio of a song on YouTube and returns a playable link.
 *
 * Two yt-dlp runs per song: a flat search (titles, channels, lengths only — fast), then the chosen
 * video's audio formats. The search result is remembered per song, so the second time a song is
 * asked for only the second run happens. Nothing is converted: the link is YouTube's own audio
 * (Opus or AAC), which CabinEQ's player streams and runs through its DSP.
 */
object Yt {
    private const val TAG = "CabinEQYt/Yt"
    private const val CHOSEN_VERSION = 2

    class Result(
        val url: String,
        val headers: Map<String, String>,
        val videoId: String,
        val title: String,
        val channel: String,
        val durationSec: Int,
        val quality: String,
    )

    class NotFound(message: String) : Exception(message)

    private val ready = CountDownLatch(1)
    @Volatile var initError: String? = null
        private set
    @Volatile var lastUpdate: String? = null
        private set

    /** Song key -> chosen video id ("" = searched, nothing suitable). */
    private val chosen = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            Log.i(TAG, "yt-dlp ready")
        } catch (e: Exception) {
            initError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "yt-dlp failed to start", e)
        } finally {
            ready.countDown()
        }
        val prefs = context.getSharedPreferences("yt", Context.MODE_PRIVATE)
        // Picks made by an older matcher may be wrong (v2 requires the whole title): start over.
        if (prefs.getInt("chosenVersion", 1) != CHOSEN_VERSION) {
            prefs.edit().remove("chosen").putInt("chosenVersion", CHOSEN_VERSION).apply()
        }
        prefs.getString("chosen", null)?.let { s ->
            runCatching { val o = JSONObject(s); o.keys().forEach { k -> chosen[k] = o.getString(k) } }
        }
    }

    private fun awaitReady() {
        if (!ready.await(60, TimeUnit.SECONDS)) throw IllegalStateException("yt-dlp is still starting")
        initError?.let { throw IllegalStateException("yt-dlp failed to start: $it") }
    }

    /** Updates yt-dlp from its GitHub releases. Returns what happened, for the status screen. */
    fun update(context: Context): String {
        awaitReady()
        val status = runCatching { YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext) }
        val text = status.fold({ "yt-dlp: ${it ?: "checked"}" }, { "yt-dlp update failed: ${it.message}" })
        lastUpdate = text
        context.getSharedPreferences("yt", Context.MODE_PRIVATE).edit()
            .putLong("updatedAt", System.currentTimeMillis()).putString("updateText", text).apply()
        Log.i(TAG, text)
        return text
    }

    /**
     * A playable link for a song. With [videoId] (a YouTube result the user tapped) that exact
     * video; otherwise the best match for title, artist and length.
     */
    fun resolve(context: Context, title: String, artist: String?, durationSec: Int?, videoId: String? = null): Result {
        if (!videoId.isNullOrBlank()) { awaitReady(); return audio(videoId) }
        val key = songKey(title, artist)
        var id = chosen[key]
        if (id == null) {
            // YouTube Music's own search first (fast, official audio); yt-dlp's as the fallback.
            id = runCatching { musicSearch(title, artist, durationSec) }
                .onFailure { Log.w(TAG, "YouTube Music search failed: ${it.message}") }
                .getOrNull()
                ?: run { awaitReady(); search(title, artist, durationSec) }
                ?: ""
            chosen[key] = id
            // "Nothing found" is remembered only until the companion restarts: YouTube gets new
            // uploads, and a search that failed for a network reason must not stick for good.
            if (id.isNotEmpty()) save(context)
        }
        awaitReady()
        if (id.isEmpty()) throw NotFound("No matching song on YouTube")
        return audio(id)
    }

    /** Forgets the video chosen for a song (it turned out wrong or unplayable). */
    fun forget(context: Context, title: String, artist: String?) {
        chosen.remove(songKey(title, artist))
        save(context)
    }

    private fun save(context: Context) {
        val o = JSONObject()
        chosen.entries.filter { it.value.isNotEmpty() }.takeLast(5000).forEach { (k, v) -> o.put(k, v) }
        context.getSharedPreferences("yt", Context.MODE_PRIVATE).edit().putString("chosen", o.toString()).apply()
    }

    // ---------------------------------------------------------------- search

    /**
     * YouTube Music's songs AND videos for [query], ranked by how much of the query they contain.
     * Songs alone miss remixes, bootlegs and uploads that only exist as videos ("clarity
     * vicetone" is a video), and the songs list happily fills up with anything titled "Clarity".
     */
    fun searchAll(query: String, limit: Int): List<YtmSearch.Song> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val songs = pool.submit<List<YtmSearch.Song>> { runCatching { YtmSearch.search(query, 20, YtmSearch.SONGS) }.getOrDefault(emptyList()) }
            val videos = pool.submit<List<YtmSearch.Song>> { runCatching { YtmSearch.search(query, 20, YtmSearch.VIDEOS) }.getOrDefault(emptyList()) }
            val all = (songs.get(12, TimeUnit.SECONDS) + videos.get(12, TimeUnit.SECONDS)).distinctBy { it.videoId }
            val words = norm(query).split(' ').filter { it.length > 1 }.distinct()
            fun coverage(s: YtmSearch.Song): Double {
                if (words.isEmpty()) return 0.0
                val hay = norm(s.title + " " + s.artist)
                return words.count { it in hay }.toDouble() / words.size
            }
            // Most of the query first; among equals an official song before a video, then YouTube's order.
            return all.withIndex()
                .sortedWith(compareByDescending<IndexedValue<YtmSearch.Song>> { coverage(it.value) }
                    .thenByDescending { if (it.value.isSong) 1 else 0 }
                    .thenBy { it.index })
                .map { it.value }
                .filter { coverage(it) > 0.0 }
                .take(limit)
        } finally {
            pool.shutdownNow()
        }
    }

    /** Songs for the search screen. */
    fun searchSongs(query: String, limit: Int): List<YtmSearch.Song> = searchAll(query, limit)

    private fun musicSearch(title: String, artist: String?, durationSec: Int?): String? {
        val query = listOfNotNull(artist?.split(',')?.first()?.trim(), title).joinToString(" ")
        val candidates = searchAll(query, 20)
        if (candidates.isEmpty()) return null
        val wanted = norm(title).split(' ').filter { it.length > 1 }
        var best: Pair<String, Double>? = null
        for (s in candidates) {
            // Every word of the title must be there: "Clarity (Vicetone Remix)" is not "Clarity".
            val t = norm(s.title)
            if (wanted.any { it !in t }) continue
            // A song listed by YouTube Music is official audio: the Topic lift. Videos get none.
            val channel = if (s.isSong) s.artist + " topic" else s.artist
            val sc = score(title, artist, durationSec, s.title, channel, s.durationSec)
            Log.i(TAG, "music candidate ${s.title} / ${s.artist} (${if (s.isSong) "song" else "video"}) -> ${"%.1f".format(sc)}")
            if (best == null || sc > best.second) best = s.videoId to sc
        }
        // Nothing with the whole title: let yt-dlp's plain YouTube search have a go.
        return best?.takeIf { it.second >= 1.0 }?.first
    }

    private fun search(title: String, artist: String?, durationSec: Int?): String? {
        val query = listOfNotNull(artist?.split(',')?.first()?.trim(), title).joinToString(" ")
        val req = YoutubeDLRequest("ytsearch8:$query")
        req.addOption("--flat-playlist")
        req.addOption("--dump-single-json")
        req.addOption("--no-warnings")
        val out = YoutubeDL.getInstance().execute(req).out
        val entries = JSONObject(out).optJSONArray("entries") ?: return null
        var best: Pair<String, Double>? = null
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.optString("id").takeIf { it.isNotBlank() } ?: continue
            val s = score(
                wantedTitle = title,
                wantedArtist = artist,
                wantedSec = durationSec,
                title = e.optString("title"),
                channel = e.optString("channel").ifBlank { e.optString("uploader") },
                sec = e.optDouble("duration", -1.0).takeIf { it > 0 }?.toInt(),
            )
            Log.i(TAG, "candidate ${e.optString("title")} / ${e.optString("channel")} -> ${"%.1f".format(s)}")
            if (best == null || s > best.second) best = id to s
        }
        return best?.takeIf { it.second >= 1.0 }?.first
    }

    private val unwanted = listOf(
        "live", "cover", "karaoke", "instrumental", "remix", "sped up", "slowed", "nightcore",
        "8d", "reaction", "acoustic", "piano version", "tutorial", "lesson", "mashup", "extended",
    )

    internal fun score(
        wantedTitle: String,
        wantedArtist: String?,
        wantedSec: Int?,
        title: String,
        channel: String,
        sec: Int?,
    ): Double {
        val t = norm(title)
        val wt = norm(wantedTitle)
        val wa = norm(wantedArtist?.split(',')?.first())
        val c = norm(channel)
        var s = 0.0
        val words = wt.split(' ').filter { it.length > 1 }
        val hit = if (words.isEmpty()) 0.0 else words.count { it in t }.toDouble() / words.size
        s += if (hit >= 0.99) 3.0 else if (hit >= 0.6) 1.0 else -4.0
        if (c.endsWith(" topic")) s += 3.0                         // YouTube Music's official audio
        if (wa.isNotEmpty() && (wa in c || wa in t)) s += 2.0
        for (w in unwanted) if (w in t && w !in wt) s -= 3.0
        if ("official video" in t || "music video" in t) s -= 0.5  // may carry intros and skits
        if (wantedSec != null && wantedSec > 0 && sec != null && sec > 0) {
            val diff = abs(sec - wantedSec)
            s += if (diff <= 3) 2.0 else if (diff <= maxOf(10, wantedSec / 20)) 1.0 else if (diff <= maxOf(20, wantedSec / 10)) -1.0 else -5.0
        }
        return s
    }

    private fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return n.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun songKey(title: String, artist: String?) = norm(title) + "|" + norm(artist?.split(',')?.first())

    // ---------------------------------------------------------------- audio link

    private fun audio(id: String): Result {
        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
        // Opus first (best quality per bit), then AAC; never a video stream.
        req.addOption("-f", "bestaudio[acodec=opus]/bestaudio[ext=m4a]/bestaudio")
        req.addOption("--dump-single-json")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        val o = JSONObject(YoutubeDL.getInstance().execute(req).out)
        // With a single format chosen, its fields are on the top level; otherwise in requested_formats.
        val f = o.optJSONArray("requested_formats")?.optJSONObject(0) ?: o
        val url = f.optString("url").takeIf { it.startsWith("http") }
            ?: throw IllegalStateException("yt-dlp returned no audio link")
        val headers = HashMap<String, String>()
        (f.optJSONObject("http_headers") ?: o.optJSONObject("http_headers"))?.let { h ->
            h.keys().forEach { k -> headers[k] = h.optString(k) }
        }
        val codec = f.optString("acodec").substringBefore('.').ifBlank { f.optString("ext") }
        val abr = f.optDouble("abr", 0.0).toInt()
        return Result(
            url = url,
            headers = headers,
            videoId = id,
            title = o.optString("title"),
            channel = o.optString("channel").ifBlank { o.optString("uploader") },
            durationSec = o.optDouble("duration", 0.0).toInt(),
            quality = "YouTube · " + codec.uppercase(Locale.ROOT) + if (abr > 0) " $abr kbps" else "",
        )
    }
}
