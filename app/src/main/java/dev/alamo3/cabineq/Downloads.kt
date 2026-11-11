package dev.alamo3.cabineq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Music kept on the phone on purpose: albums and playlists the listener asked to download.
 *
 * Unlike [MediaCache] (a size-capped, evicting cache of what is about to play) nothing here is
 * ever removed behind the listener's back. Files live in `filesDir/downloads`, named like the
 * cache's so the player finds them through [MediaCache.fileFor]; `downloads.json` holds the
 * collections with full track metadata, so the list and playback work with no network at all.
 */
object Downloads {
    private const val TAG = "CabinEQ/Downloads"
    private const val DIR = "downloads"
    private const val INDEX = "downloads.json"
    private const val RESOLVE_WAIT_MS = 5 * 60_000L
    private const val MIN_FREE_BYTES = 300L * 1024 * 1024
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

    class Collection(val id: String, val title: String, val artworkUrl: String?, val tracks: List<Track>)

    /** Progress of one collection: how many of its songs are on the phone. */
    class Status(val done: Int, val total: Int, val active: Boolean, val problem: String?) {
        val complete: Boolean get() = total > 0 && done >= total
    }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Download").apply { isDaemon = true }
    }
    private val lock = Any()
    private var loaded: MutableList<Collection>? = null
    private val queued = HashSet<String>()
    /** Songs that failed this session (not on the source, no space…): not retried in a loop. */
    private val failed = HashMap<String, String>()

    /** Bumped on every change, so a screen can poll cheaply and redraw when it moves. */
    @Volatile var version = 0
        private set
    /** Why nothing is downloading although something is pending ("Waiting for Wi-Fi"), if so. */
    @Volatile var blocked: String? = null
        private set

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileName(track: Track): String =
        MessageDigest.getInstance("SHA-1").digest(track.mediaId.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".audio"

    /** The downloaded audio for this track, or null. */
    fun fileFor(context: Context, track: Track): File? =
        File(dir(context), fileName(track)).takeIf { it.exists() && it.length() > 0L }

    fun collections(context: Context): List<Collection> = synchronized(lock) { ArrayList(load(context)) }

    fun find(context: Context, id: String): Collection? = collections(context).firstOrNull { it.id == id }

    fun idFor(title: String): String = "d" + Integer.toHexString(title.trim().lowercase().hashCode())

    fun status(context: Context, c: Collection): Status {
        val done = c.tracks.count { fileFor(context, it) != null }
        val (active, problem) = synchronized(lock) {
            c.tracks.any { it.mediaId in queued } to c.tracks.firstNotNullOfOrNull { failed[it.mediaId] }
        }
        return Status(done, c.tracks.size, active, if (done < c.tracks.size) blocked ?: problem else null)
    }

    fun usedBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Keeps [tracks] on the phone under [title] and starts fetching whatever is missing. Adding
     * to an existing collection of the same name extends it.
     */
    fun add(context: Context, title: String, artworkUrl: String?, tracks: List<Track>) {
        // A discovered song has no id yet and can't be re-found offline by one: skip those.
        val keep = tracks.filter { it.sourceId != null || it.source == Track.Source.TORBOX }
            // Stream URLs expire; the id is what resolves it again.
            .map { if (it.sourceId != null) it.copy(streamUrl = null) else it }
        if (keep.isEmpty()) return
        val app = context.applicationContext
        val id = idFor(title)
        synchronized(lock) {
            val all = load(app)
            val i = all.indexOfFirst { it.id == id }
            val merged = if (i >= 0) (all[i].tracks + keep).distinctBy { it.mediaId } else keep.distinctBy { it.mediaId }
            val c = Collection(id, title.trim(), artworkUrl ?: all.getOrNull(i)?.artworkUrl, merged)
            if (i >= 0) all[i] = c else all.add(0, c)
            keep.forEach { failed.remove(it.mediaId) }
            save(app, all)
        }
        version++
        resume(app)
    }

    /** Forgets a collection and deletes the files no other collection still needs. */
    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        synchronized(lock) {
            val all = load(app)
            val gone = all.firstOrNull { it.id == id } ?: return
            all.remove(gone)
            val stillNeeded = all.flatMap { it.tracks }.map { fileName(it) }.toHashSet()
            gone.tracks.map { fileName(it) }.filter { it !in stillNeeded }
                .forEach { File(dir(app), it).delete() }
            save(app, all)
        }
        version++
    }

    fun removeAll(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            load(app).clear()
            save(app, load(app))
            dir(app).listFiles()?.forEach { it.delete() }
            failed.clear()
        }
        version++
    }

    /** Clears this session's failures and tries again (the "Retry" button). */
    fun retry(context: Context) {
        synchronized(lock) { failed.clear() }
        resume(context)
    }

    /**
     * Queues every song that is in a collection but not on the phone yet. Called when something
     * is added and when the app opens, so an interrupted download carries on.
     */
    fun resume(context: Context) {
        val app = context.applicationContext
        val pending = synchronized(lock) {
            load(app).flatMap { it.tracks }.distinctBy { it.mediaId }
                .filter { fileFor(app, it) == null && it.mediaId !in queued && it.mediaId !in failed }
                .onEach { queued.add(it.mediaId) }
        }
        if (pending.isEmpty()) return
        version++
        pending.forEach { track -> io.execute { fetch(app, track) } }
    }

    private fun fetch(app: Context, track: Track) {
        try {
            // Removed from every collection while it waited in the queue.
            val wanted = synchronized(lock) { load(app).any { c -> c.tracks.any { it.mediaId == track.mediaId } } }
            if (!wanted || fileFor(app, track) != null) return
            val settings = Settings(app)
            if (!online(app)) { blocked = "Waiting for a connection"; return }
            if (settings.cacheWifiOnly && metered(app)) { blocked = "Waiting for Wi-Fi (set in Settings)"; return }
            if (app.filesDir.usableSpace < MIN_FREE_BYTES) { blocked = "Not enough free space"; return }
            blocked = null
            val target = File(dir(app), fileName(track))
            // Already prefetched for playback: keep that copy instead of downloading it again.
            val cached = MediaCache.cacheFileFor(app, track)
            if (cached != null && runCatching { cached.copyTo(target, overwrite = true) }.isSuccess) {
                DLog.i(TAG, "kept cached \"${track.title}\"")
                return
            }
            val url = TrackResolver.resolve(app, track, { }, { false }, RESOLVE_WAIT_MS).url
            val size = MediaCache.downloadTo(target, url, MAX_FILE_BYTES, track.title)
            if (size > 0) DLog.i(TAG, "downloaded \"${track.title}\" (${size / 1024} kB)")
            else synchronized(lock) { failed[track.mediaId] = "Couldn't download “${track.title}”" }
        } catch (e: Exception) {
            DLog.w(TAG, "download ${track.title} failed: ${e.javaClass.simpleName}: ${e.message}")
            synchronized(lock) { failed[track.mediaId] = e.message?.takeIf { it.length < 120 } ?: "Couldn't download “${track.title}”" }
        } finally {
            synchronized(lock) { queued.remove(track.mediaId) }
            version++
        }
    }

    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun metered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ------------------------------------------------------------------ index

    private fun load(context: Context): MutableList<Collection> {
        loaded?.let { return it }
        val out = ArrayList<Collection>()
        val f = File(context.filesDir, INDEX)
        if (f.exists()) runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("collections") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tracks = o.optJSONArray("tracks") ?: continue
                val list = (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it)?.let(Track::fromJson) }
                val title = o.optString("title")
                if (title.isBlank() || list.isEmpty()) continue
                out += Collection(
                    id = o.optString("id").ifBlank { idFor(title) },
                    title = title,
                    artworkUrl = o.optString("art").takeIf { it.startsWith("http") },
                    tracks = list,
                )
            }
        }.onFailure { DLog.w(TAG, "Couldn't read the downloads index: ${it.message}") }
        loaded = out
        return out
    }

    private fun save(context: Context, all: List<Collection>) {
        val arr = JSONArray()
        for (c in all) {
            arr.put(
                JSONObject().put("id", c.id).put("title", c.title).putOpt("art", c.artworkUrl)
                    .put("tracks", JSONArray().apply { c.tracks.forEach { put(it.toJson()) } })
            )
        }
        val f = File(context.filesDir, INDEX)
        val tmp = File(context.filesDir, "$INDEX.tmp")
        runCatching {
            tmp.writeText(JSONObject().put("collections", arr).toString())
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }.onFailure { DLog.w(TAG, "Couldn't save the downloads index: ${it.message}") }
    }
}
