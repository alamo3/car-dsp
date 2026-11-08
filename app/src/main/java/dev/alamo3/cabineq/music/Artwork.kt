package dev.alamo3.cabineq.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Small album-art loader (framework only, no Glide/Coil): memory LRU + disk cache in
 * cacheDir/art, decoded at the requested size. Safe for recycled views: each ImageView is
 * tagged with the URL it should show, and stale results are dropped.
 */
object Artwork {
    private const val TAG = "CabinEQ/Artwork"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "CabinEQ-Art").apply { isDaemon = true } }

    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /**
     * Apple Music artwork URLs are often templates ("…/{w}x{h}bb.{f}"). Fill them in; plain
     * URLs pass through unchanged.
     */
    fun resolveUrl(url: String, sizePx: Int): String =
        url.replace("{w}", sizePx.toString()).replace("{h}", sizePx.toString()).replace("{f}", "jpg")

    /** Shows [url] in [view] at about [sizePx]; [placeholder] (a drawable res) until it loads. */
    fun load(view: ImageView, url: String?, sizePx: Int, placeholder: Int = 0) {
        if (url.isNullOrBlank()) {
            view.tag = null
            if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
            return
        }
        val resolved = resolveUrl(url, sizePx)
        val key = "$resolved@$sizePx"
        view.tag = key
        memory.get(key)?.let { view.setImageBitmap(it); return }
        if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = fetch(ctx, resolved, sizePx) ?: return@execute
            memory.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    /** Loads a bitmap off the main thread and delivers it on the main thread (null on failure). */
    fun loadBitmap(context: Context, url: String?, sizePx: Int, done: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) { done(null); return }
        val resolved = resolveUrl(url, sizePx)
        val key = "$resolved@$sizePx"
        memory.get(key)?.let { done(it); return }
        val ctx = context.applicationContext
        pool.execute {
            val bmp = fetch(ctx, resolved, sizePx)
            if (bmp != null) memory.put(key, bmp)
            main.post { done(bmp) }
        }
    }

    private fun fetch(context: Context, url: String, sizePx: Int): Bitmap? {
        val dir = File(context.cacheDir, "art").apply { mkdirs() }
        val file = File(dir, sha1(url))
        try {
            if (!file.exists() || file.length() == 0L) {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CabinEQ")
                }
                try {
                    if (conn.responseCode !in 200..299) return null
                    val tmp = File(dir, file.name + ".tmp")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!tmp.renameTo(file)) { tmp.delete(); return null }
                } finally {
                    conn.disconnect()
                }
                trimDisk(dir)
            }
            return decode(file, sizePx)
        } catch (e: Exception) {
            Log.w(TAG, "Artwork failed: ${e.javaClass.simpleName}")
            return null
        }
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
