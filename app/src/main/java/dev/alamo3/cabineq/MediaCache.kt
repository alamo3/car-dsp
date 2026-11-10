package dev.alamo3.cabineq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import dev.alamo3.cabineq.music.GrowingDownload
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

    // Two at a time: the next song and the one after it must not queue behind each other (one
    // slow caching wait on a single thread used to hold up every download behind it).
    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "CabinEQ-Cache").apply { isDaemon = true }
    }
    private val inFlight = HashSet<String>()

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    private fun name(track: Track): String = sha1(track.mediaId) + ".audio"

    /**
     * Local audio for this track — a download the listener asked for, else a prefetched copy —
     * or null when it has to be streamed.
     */
    fun fileFor(context: Context, track: Track): File? =
        Downloads.fileFor(context, track) ?: cacheFileFor(context, track)

    /** The prefetched copy only (what this cache owns and may evict). */
    fun cacheFileFor(context: Context, track: Track): File? {
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
    fun prefetch(context: Context, track: Track, url: String? = null, waitMs: Long = PREFETCH_WAIT_MS) {
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
        if (activeFor(track) != null) return
        synchronized(inFlight) { if (!inFlight.add(key)) return }
        io.execute {
            try {
                val resolvedUrl = url ?: runCatching {
                    TrackResolver.resolve(app, track, { }, { false }, waitMs).url
                }.getOrNull()
                if (resolvedUrl == null) {
                    Log.i(TAG, "skip ${track.title}: no stream URL")
                    return@execute
                }
                // Someone (the player) may have started this song while the link was resolving.
                val mine = register(app, track, resolvedUrl) ?: return@execute
                runDownload(app, track, mine)
            } catch (e: Exception) {
                Log.w(TAG, "prefetch ${track.title} failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }
    }

    // ------------------------------------------------------------ play while downloading

    /** Downloads in progress, by media id. The player reads from these while they grow. */
    private val active = HashMap<String, GrowingDownload>()

    /** The download of [track] if one is under way. */
    fun activeFor(track: Track): GrowingDownload? =
        synchronized(active) { active[track.mediaId]?.takeIf { it.isAlive } }

    /**
     * For the player: the download of [track] to read from while it arrives — the one already
     * under way, or a new one from [url]. Null when the cache cannot take it (switched off, no
     * space); the caller then streams the old way.
     */
    fun open(context: Context, track: Track, url: String): GrowingDownload? {
        val app = context.applicationContext
        activeFor(track)?.let { return it }
        if (!Settings(app).cacheEnabled) return null
        if (app.cacheDir.usableSpace < MIN_FREE_BYTES) return null
        val mine = register(app, track, url) ?: return activeFor(track)
        Thread({ runDownload(app, track, mine) }, "CabinEQ-Fetch").apply { isDaemon = true }.start()
        return mine
    }

    /** Creates and records a download; null when one is already recorded for this song. */
    private fun register(app: Context, track: Track, url: String): GrowingDownload? {
        val max = Settings(app).cacheMaxMb.toLong() * 1024 * 1024
        val target = File(dir(app), name(track))
        synchronized(active) {
            if (active[track.mediaId]?.isAlive == true) return null
            val download = GrowingDownload(
                url = url,
                part = File(target.parentFile, target.name + "." + System.nanoTime() + ".part"),
                target = target,
                maxBytes = max,
                label = track.title,
                // The addon's links are short-lived tickets: ask for a new one if this one lapses.
                refreshUrl = {
                    runCatching { TrackResolver.resolve(app, track, { }, { false }, 20_000L).url }.getOrNull()
                },
                log = { Log.i(TAG, it) },
            )
            active[track.mediaId] = download
            return download
        }
    }

    private fun runDownload(app: Context, track: Track, download: GrowingDownload) {
        try {
            download.run()
            if (download.isFinished) {
                Log.i(TAG, "cached \"${track.title}\" (${download.written / 1024} kB)")
                trim(app, Settings(app).cacheMaxMb.toLong() * 1024 * 1024)
            }
        } finally {
            synchronized(active) { if (active[track.mediaId] === download) active.remove(track.mediaId) }
        }
    }

    /**
     * The player has left this song. A download that is nearly complete is allowed to finish (the
     * song is then on the phone next time); one that is not is stopped, so skipping through songs
     * does not leave a trail of downloads running.
     */
    fun leave(download: GrowingDownload) {
        val total = download.total
        if (download.isAlive && (total <= 0 || download.written < total * 8 / 10)) download.cancel()
        download.release()
    }

    /** Applies a new cap immediately (the settings screen calls this when the size changes). */
    fun applyLimit(context: Context) {
        val max = Settings(context.applicationContext).cacheMaxMb.toLong() * 1024 * 1024
        io.execute { trim(context.applicationContext, max) }
    }

    private fun download(context: Context, track: Track, url: String, max: Long): Long =
        downloadTo(File(dir(context), name(track)), url, max, track.title)

    /**
     * Streams [url] into [target] through a .part file, so a half-written download is never
     * mistaken for a song. Returns the bytes written, or 0 when nothing usable arrived.
     */
    fun downloadTo(target: File, url: String, max: Long, label: String): Long {
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
                Log.i(TAG, "prefetch $label: HTTP $code")
                return 0L
            }
            val expected = conn.contentLengthLong
            if (expected > 0 && expected > max) {
                Log.i(TAG, "skip $label: ${expected / 1024 / 1024} MB exceeds the ${max / 1024 / 1024} MB cap")
                return 0L
            }
            var written = 0L
            var capped = false
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written > max) {       // never let one file blow the cap
                            capped = true
                            break
                        }
                    }
                }
            }
            if (written <= 0L || capped || (expected > 0 && written < expected)) {
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
        // "Wi-Fi only" means "not paying per megabyte": a car hotspot or a phone tethered to
        // another phone is Wi-Fi but metered, and the system knows which is which.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (android.os.Build.VERSION.SDK_INT >= 30 &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
