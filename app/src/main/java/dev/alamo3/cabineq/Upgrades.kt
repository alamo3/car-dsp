package dev.alamo3.cabineq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.music.AddonMatch
import dev.alamo3.cabineq.music.SongKey
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * "Play from YouTube now, upgrade for next time."
 *
 * A song that played from YouTube is looked up on Nebula in the background. When Nebula has the
 * same recording in better quality (lossless), it is downloaded here, and the next time the song
 * plays it comes from this file: better quality, instant start, offline. Never swapped mid-song:
 * the two versions can be different masters, offset or louder.
 *
 * One download at a time (TorBox slots stay free for playback), on Wi-Fi only, inside a cap the
 * user sets (5 GB minimum); least recently played songs go first when the cap is reached.
 */
object Upgrades {
    private const val TAG = "CabinEQ/Upgrade"
    private const val DIR = "upgrades"
    private const val STATE = "upgrades.json"
    /** Nebula's answer time per request, and the longest wait for TorBox to cache a song. */
    private const val NEBULA_TIMEOUT_MS = 15_000
    private const val CACHE_WAIT_MS = 10 * 60_000L
    private const val MAX_FILE_BYTES = 400L * 1024 * 1024
    /** A song with no better version on Nebula is not looked for again for this long. */
    private const val RETRY_AFTER_MS = 14L * 24 * 3600_000

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Upgrade").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val lock = Any()
    /** Songs waiting to be looked at, by key. */
    private val pending = LinkedHashMap<String, Track>()
    /** Songs Nebula had nothing better for (key -> when). */
    private val skipped = HashMap<String, Long>()
    @Volatile private var loaded = false
    @Volatile private var running = false

    private fun key(t: Track): String = SongKey.of(t.title, t.artist)
    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }
    private fun fileName(t: Track) = Integer.toHexString(key(t).hashCode()) + ".audio"

    /** The upgraded copy of [track], if there is one (any version of the same song). */
    fun fileFor(context: Context, track: Track): File? {
        val f = File(dir(context), fileName(track))
        if (!f.exists() || f.length() <= 0L) return null
        f.setLastModified(System.currentTimeMillis())   // played: the eviction order is by last use
        return f
    }

    fun usedBytes(context: Context): Long = dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    fun count(context: Context): Int = dir(context).listFiles()?.count { it.isFile && it.name.endsWith(".audio") } ?: 0

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        synchronized(lock) { skipped.clear(); pending.clear() }
        save(context)
    }

    /** [track] just played from YouTube: look for a better version for next time. */
    fun consider(context: Context, track: Track) {
        val app = context.applicationContext
        val settings = Settings(app)
        if (!settings.upgradeEnabled || settings.addonLink.isBlank()) return
        load(app)
        val k = key(track)
        if (fileFor(app, track) != null) return
        synchronized(lock) {
            skipped[k]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MS) return }
            pending[k] = track
        }
        save(app)
        kick(app)
    }

    /** Carries on with songs left from earlier (called when the app starts, or Wi-Fi returns). */
    fun resume(context: Context) {
        val app = context.applicationContext
        load(app)
        kick(app)
    }

    private fun kick(app: Context) {
        synchronized(lock) {
            if (running || pending.isEmpty()) return
            running = true
        }
        worker.execute {
            try { drain(app) } finally { running = false }
        }
    }

    private fun drain(app: Context) {
        while (true) {
            val settings = Settings(app)
            if (!settings.upgradeEnabled) return
            if (!onWifi(app)) { DLog.i(TAG, "${pending.size} waiting for Wi-Fi"); return }
            val next = synchronized(lock) { pending.entries.firstOrNull() } ?: return
            val (k, track) = next.key to next.value
            val outcome = runCatching { upgrade(app, track) }
                .getOrElse { DLog.w(TAG, "\"${track.title}\": ${it.javaClass.simpleName}: ${it.message}"); null }
            synchronized(lock) {
                pending.remove(k)
                // Nothing better (or no match): remember for a while. A failure is simply dropped;
                // the next play brings it back.
                if (outcome == false) skipped[k] = System.currentTimeMillis()
            }
            save(app)
            evict(app)
        }
    }

    /** True: upgraded. False: Nebula has no better copy. Null-equivalent: throws on failure. */
    private fun upgrade(app: Context, track: Track): Boolean {
        val link = Settings(app).addonLink
        val addon = EclipseAddon(link, NEBULA_TIMEOUT_MS)
        val found = when (val r = AddonMatch.match(addon, track)) {
            is AddonMatch.Outcome.Found -> r.track
            AddonMatch.Outcome.NotFound -> { DLog.i(TAG, "\"${track.title}\": not on Nebula"); return false }
            is AddonMatch.Outcome.Failed -> throw r.error
        }
        // The same recording, not another edit: lengths within a few seconds when both are known.
        val want = (track.durationMs / 1000).toInt()
        val have = found.durationSec ?: 0
        if (want > 0 && have > 0 && kotlin.math.abs(want - have) > 3) {
            DLog.i(TAG, "\"${track.title}\": Nebula's is ${have}s, this one ${want}s — a different edit, skipped")
            return false
        }
        val stream = EclipseAddon(link).resolveStream(found.id, maxWaitMs = CACHE_WAIT_MS, waitIfPending = true)
        if (!isBetter(stream)) {
            DLog.i(TAG, "\"${track.title}\": Nebula's copy is ${stream.qualityLabel()} — not better, kept YouTube")
            return false
        }
        val target = File(dir(app), fileName(track))
        val size = MediaCache.downloadTo(target, stream.url, MAX_FILE_BYTES, track.title)
        if (size <= 0L) throw IllegalStateException("download failed")
        DLog.i(TAG, "\"${track.title}\": upgraded to ${stream.qualityLabel()} (${size / (1024 * 1024)} MB)")
        return true
    }

    /** Better than YouTube's Opus/AAC: lossless (FLAC, ALAC, WAV) or a high bitrate. */
    private fun isBetter(s: EclipseAddon.Stream): Boolean {
        val text = listOfNotNull(s.format, s.codec, s.quality).joinToString(" ").lowercase()
        if (listOf("flac", "alac", "wav", "lossless", "hi-res", "hires").any { it in text }) return true
        return (s.bitDepth ?: 0) >= 16 && (s.sampleRate ?: 0) >= 44_100 && "mp3" !in text && "aac" !in text && "opus" !in text
    }

    /** Over the cap: the least recently played upgrades go first. */
    private fun evict(app: Context) {
        val cap = Settings(app).upgradeCapGb.toLong() * 1024 * 1024 * 1024
        val files = dir(app).listFiles()?.filter { it.isFile && it.name.endsWith(".audio") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= cap) break
            total -= f.length()
            f.delete()
        }
    }

    private fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---------------------------------------------------------------- state

    private fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            runCatching {
                val o = JSONObject(File(context.filesDir, STATE).readText())
                o.optJSONObject("skipped")?.let { s -> s.keys().forEach { k -> skipped[k] = s.optLong(k) } }
                o.optJSONArray("pending")?.let { a ->
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let(Track::fromJson)?.let { pending[key(it)] = it }
                }
            }
        }
    }

    private fun save(context: Context) {
        runCatching {
            val o = JSONObject()
            synchronized(lock) {
                o.put("skipped", JSONObject().apply { skipped.forEach { (k, v) -> put(k, v) } })
                o.put("pending", org.json.JSONArray().apply { pending.values.forEach { put(it.toJson()) } })
            }
            val f = File(context.filesDir, STATE)
            val tmp = File(context.filesDir, "$STATE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }
}
