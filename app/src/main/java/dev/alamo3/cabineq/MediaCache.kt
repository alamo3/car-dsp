package dev.alamo3.cabineq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Disk cache for the audio of upcoming tracks.
 *
 * Playback itself always streams, so the win is the *next* track: while one song plays, the queue's
 * next entry is downloaded in the background and the player opens the local file instead of the
 * network. The addon hands out short-lived ticket URLs, so the cache is keyed by the track's
 * identity ([Track.mediaId] — ISRC for catalogue tracks), not by URL: a fresh ticket is only needed
 * when the file is not there yet.
 *
 * The cache is capped in megabytes and evicted oldest-first; the user controls both the cap and
 * whether prefetching may use mobile data (Settings).
 */
object MediaCache {
    private const val TAG = "CabinEQ/Cache"
    private const val DIR = "media"
    private const val PREFETCH_WAIT_MS = 90_000L
    private const val MIN_FREE_BYTES = 256L * 1024 * 1024

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Cache").apply { isDaemon = true }
    }
    private val inFlight = HashSet<String>()

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    private fun name(track: Track): String = sha1(track.mediaId) + ".audio"

    /** The cached audio for this track, or null when it hasn't been prefetched. */
    fun fileFor(context: Context, track: Track): File? {
        val f = File(dir(context), name(track))
        if (!f.exists() || f.length() <= 0L) return null
        // Opening a cached track counts as a use for the LRU (lastModified is the eviction key).
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    fun usedBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * Downloads [track] in the background unless it is already cached, caching is off, or the
     * network policy forbids it. [url] skips resolution when the caller already has one.
     */
    fun prefetch(context: Context, track: Track, url: String? = null) {
        val app = context.applicationContext
        val settings = Settings(app)
        if (!settings.cacheEnabled) return
        if (settings.cacheWifiOnly && !onUnmeteredNetwork(app)) {
            Log.i(TAG, "skip ${track.title}: not on Wi-Fi and Wi-Fi-only is set")
            return
        }
        if (fileFor(app, track) != null) return
        if (app.cacheDir.usableSpace < MIN_FREE_BYTES) {
            Log.i(TAG, "skip ${track.title}: only ${app.cacheDir.usableSpace / 1024 / 1024} MB free")
            return
        }
        val key = track.mediaId
        synchronized(inFlight) { if (!inFlight.add(key)) return }
        io.execute {
            try {
                val max = settings.cacheMaxMb.toLong() * 1024 * 1024
                val resolvedUrl = url ?: runCatching {
                    TrackResolver.resolve(app, track, { }, { false }, PREFETCH_WAIT_MS).url
                }.getOrNull()
                if (resolvedUrl == null) {
                    Log.i(TAG, "skip ${track.title}: no stream URL")
                    return@execute
                }
                val size = download(app, track, resolvedUrl, max)
                if (size > 0) {
                    Log.i(TAG, "cached \"${track.title}\" (${size / 1024} kB)")
                    trim(app, max)
                }
            } catch (e: Exception) {
                Log.w(TAG, "prefetch ${track.title} failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
    }

    /** Applies a new cap immediately (the settings screen calls this when the size changes). */
    fun applyLimit(context: Context) {
        val max = Settings(context.applicationContext).cacheMaxMb.toLong() * 1024 * 1024
        io.execute { trim(context.applicationContext, max) }
    }

    private fun download(context: Context, track: Track, url: String, max: Long): Long {
        val target = File(dir(context), name(track))
        if (target.exists() && target.length() > 0L) return 0L
        var conn: HttpURLConnection? = null
        val tmp = File(target.parentFile, target.name + "." + System.nanoTime() + ".part")
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CabinEQ")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.i(TAG, "prefetch ${track.title}: HTTP $code")
                return 0L
            }
            val expected = conn.contentLengthLong
            if (expected > 0 && expected > max) {
                Log.i(TAG, "skip ${track.title}: ${expected / 1024 / 1024} MB exceeds the ${max / 1024 / 1024} MB cap")
                return 0L
            }
            var written = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written > max) break   // never let one file blow the cap
                    }
                }
            }
            if (written <= 0L || (expected > 0 && written < expected)) {
                tmp.delete()
                return 0L
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return 0L
            }
            written
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    /** Oldest-first eviction down to [max] bytes (stale .part files are dropped outright). */
    private fun trim(context: Context, max: Long) {
        val files = dir(context).listFiles() ?: return
        val now = System.currentTimeMillis()
        files.filter { it.name.endsWith(".part") && now - it.lastModified() > 3600_000L }
            .forEach { it.delete() }
        var total = files.filterNot { it.name.endsWith(".part") }.sumOf { it.length() }
        if (total <= max) return
        val oldestFirst = files.filterNot { it.name.endsWith(".part") }.sortedBy { it.lastModified() }
        for (f in oldestFirst) {
            if (total <= max) break
            total -= f.length()
            f.delete()
        }
        Log.i(TAG, "trimmed cache to ${total / 1024 / 1024} MB (cap ${max / 1024 / 1024} MB)")
    }

    private fun onUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
