package dev.alamo3.cabineq.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small album-art loader (framework only, no Glide/Coil): memory LRU + disk cache in
 * cacheDir/art, decoded at the requested size. Safe for recycled views: each ImageView is
 * tagged with what it should show, and stale results are dropped.
 *
 * When an item has no cover URL, or its URL fails, it falls back to Apple's public iTunes
 * Search API (no key) using a text query such as "artist album".
 */
object Artwork {
    private const val TAG = "CabinEQ/Artwork"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "CabinEQ-Art").apply { isDaemon = true } }

    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** iTunes lookups: query → artwork URL ("" = nothing found). */
    private val lookups = ConcurrentHashMap<String, String>()

    // Diagnostics, shown in Settings.
    val loaded = AtomicInteger()
    val failed = AtomicInteger()
    val missing = AtomicInteger()
    val fromFallback = AtomicInteger()
    @Volatile var lastError: String? = null
        private set

    enum class Kind(val entity: String) { ALBUM("album"), SONG("song"), ARTIST("album") }

    /**
     * Apple Music artwork URLs are often templates ("…/{w}x{h}bb.{f}"); fill them in. Plain
     * http is upgraded to https (Android blocks cleartext traffic).
     */
    fun resolveUrl(url: String, sizePx: Int): String {
        val u = url.trim().replace("{w}", sizePx.toString()).replace("{h}", sizePx.toString()).replace("{f}", "jpg")
        return if (u.startsWith("http://")) "https://" + u.removePrefix("http://") else u
    }

    /**
     * Shows a cover in [view]: [url] if it loads, otherwise the iTunes result for
     * [fallbackQuery] (e.g. "Don Toliver Hardstone Psycho"), otherwise the grey placeholder.
     */
    fun load(view: ImageView, url: String?, sizePx: Int, fallbackQuery: String? = null, kind: Kind = Kind.ALBUM) {
        val key = "${url.orEmpty()}|${fallbackQuery.orEmpty()}@$sizePx"
        view.tag = key
        memory.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        if (url.isNullOrBlank() && fallbackQuery.isNullOrBlank()) {
            missing.incrementAndGet()
            return
        }
        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = resolve(ctx, url, sizePx, fallbackQuery, kind) ?: return@execute
            memory.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    /** Same as [load] but delivers the bitmap (null on failure) on the main thread. */
    fun loadBitmap(context: Context, url: String?, sizePx: Int, fallbackQuery: String? = null, done: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank() && fallbackQuery.isNullOrBlank()) { done(null); return }
        val key = "${url.orEmpty()}|${fallbackQuery.orEmpty()}@$sizePx"
        memory.get(key)?.let { done(it); return }
        val ctx = context.applicationContext
        pool.execute {
            val bmp = resolve(ctx, url, sizePx, fallbackQuery, Kind.SONG)
            if (bmp != null) memory.put(key, bmp)
            main.post { done(bmp) }
        }
    }

    /** Diagnostic summary for the settings screen. */
    fun summary(): String {
        val base = "Covers: ${loaded.get()} loaded (${fromFallback.get()} via iTunes), ${failed.get()} failed, ${missing.get()} without art info"
        return lastError?.let { "$base\nLast problem: $it" } ?: base
    }

    // ---------------------------------------------------------------- internals

    private fun resolve(ctx: Context, url: String?, sizePx: Int, fallbackQuery: String?, kind: Kind): Bitmap? {
        if (!url.isNullOrBlank()) {
            fetch(ctx, resolveUrl(url, sizePx), sizePx)?.let { loaded.incrementAndGet(); return it }
        } else {
            missing.incrementAndGet()
        }
        if (!fallbackQuery.isNullOrBlank()) {
            val alt = itunesArtwork(fallbackQuery, kind, sizePx)
            if (alt != null) {
                fetch(ctx, alt, sizePx)?.let {
                    loaded.incrementAndGet()
                    fromFallback.incrementAndGet()
                    return it
                }
            }
        }
        failed.incrementAndGet()
        return null
    }

    /** Apple's public search: https://itunes.apple.com/search?term=…&entity=album|song. */
    private fun itunesArtwork(query: String, kind: Kind, sizePx: Int): String? {
        val cacheKey = "${kind.entity}:${query.lowercase()}"
        lookups[cacheKey]?.let { return it.ifEmpty { null }?.let { u -> sized(u, sizePx) } }
        val term = URLEncoder.encode(query.take(120), "UTF-8")
        val url = "https://itunes.apple.com/search?media=music&entity=${kind.entity}&limit=10&term=$term"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "CabinEQ")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    lastError = "iTunes lookup HTTP ${conn.responseCode}"
                    return null   // not cached: may be rate limiting, retry later
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val results = json.optJSONArray("results")
                val candidates = (0 until (results?.length() ?: 0)).mapNotNull { i ->
                    val r = results?.optJSONObject(i) ?: return@mapNotNull null
                    val art = r.optString("artworkUrl100").ifBlank { return@mapNotNull null }
                    CoverMatch.Candidate(
                        artist = r.optString("artistName"),
                        collection = r.optString("collectionName"),
                        track = r.optString("trackName"),
                        artworkUrl = art,
                    )
                }
                // Only accept a result that actually matches; a wrong cover is worse than none.
                val match = CoverMatch.best(query, candidates)
                if (match == null && candidates.isNotEmpty()) lastError = "No iTunes match for “$query”"
                val art = match?.artworkUrl.orEmpty()
                lookups[cacheKey] = art
                art.ifEmpty { null }?.let { sized(it, sizePx) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            lastError = "iTunes lookup: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }

    /** iTunes gives 100x100; the same URL serves other sizes ("…/100x100bb.jpg" → "…/600x600bb.jpg"). */
    private fun sized(url: String, sizePx: Int): String {
        val s = sizePx.coerceIn(100, 1200)
        return url.replace(Regex("""/\d+x\d+(bb)?\.(jpg|png|webp)$"""), "/${s}x${s}bb.jpg")
    }

    private fun fetch(context: Context, url: String, sizePx: Int): Bitmap? {
        val file = fileAt(context, url) ?: return null
        return decode(file, sizePx) ?: run {
            lastError = "Not an image from ${URL(url).host}"
            null
        }
    }

    /**
     * The cached file for [url], downloading it first if needed. Split out of [fetch] because
     * [ArtworkProvider] has to hand the car a file rather than a bitmap.
     */
    private fun fileAt(context: Context, url: String): File? {
        val dir = File(context.cacheDir, "art").apply { mkdirs() }
        val file = File(dir, sha1(url))
        if (file.exists() && file.length() > 0L) return file
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) CabinEQ")
                setRequestProperty("Accept", "image/*")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    lastError = "HTTP $code from ${URL(url).host}"
                    return null
                }
                val tmp = File(dir, file.name + ".tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(file)) { tmp.delete(); return null }
            } finally {
                conn.disconnect()
            }
            trimDisk(dir)
            return file.takeIf { it.length() > 0L }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message} (${runCatching { URL(url).host }.getOrDefault("?")})"
            Log.w(TAG, "Artwork failed: $lastError")
            return null
        }
    }

    /**
     * Resolves [url], else an iTunes match for [fallbackQuery], to a cached image file, and
     * checks that it really is an image. Called from [ArtworkProvider] on a binder thread, so it
     * may block on the network; the car fetches icons one by one and caches them per screen.
     */
    /**
     * MIME of artwork that is already cached for these arguments — never downloads, so the
     * content provider can answer getType() without a network round trip.
     */
    fun cachedMimeType(context: Context, url: String?, fallbackQuery: String?, sizePx: Int): String? {
        val dir = File(context.cacheDir, "art")
        val candidates = ArrayList<String>(2)
        url?.takeIf { it.isNotBlank() }?.let { candidates += resolveUrl(it, sizePx) }
        if (!fallbackQuery.isNullOrBlank()) {
            // Only an already-resolved iTunes match counts: never do the lookup just to answer.
            lookups["${Kind.SONG.entity}:${fallbackQuery.lowercase()}"]
                ?.takeIf { it.isNotBlank() }?.let { candidates += sized(it, sizePx) }
        }
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            val file = File(dir, sha1(candidate))
            if (file.exists() && file.length() > 0L) sniffImageType(file)?.let { return it }
        }
        return null
    }

    /** Magic-byte check: the cache has no file extensions to trust. */
    private fun sniffImageType(file: File): String? = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(12)
            val n = input.read(head)
            when {
                n >= 3 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
                    head[2] == 'N'.code.toByte() -> "image/png"
                n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
                n >= 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WEBP" -> "image/webp"
                n >= 3 && String(head, 0, 3) == "GIF" -> "image/gif"
                else -> null
            }
        }
    }.getOrNull()

    fun fileFor(context: Context, url: String?, fallbackQuery: String?, sizePx: Int): File? {
        val candidates = ArrayList<String>(2)
        url?.takeIf { it.isNotBlank() }?.let { candidates += resolveUrl(it, sizePx) }
        if (!fallbackQuery.isNullOrBlank()) {
            itunesArtwork(fallbackQuery, Kind.SONG, sizePx)?.let { candidates += it }
        }
        for (candidate in candidates) {
            val file = fileAt(context, candidate) ?: continue
            if (isImage(file)) return file
            file.delete()
        }
        return null
    }

    private fun isImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) { file.delete(); return null }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Keeps the disk cache under ~40 MB, oldest first. */
    private fun trimDisk(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total < MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total < MAX_DISK_BYTES * 3 / 4) break
        }
    }

    private const val MAX_DISK_BYTES = 40L * 1024 * 1024

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
