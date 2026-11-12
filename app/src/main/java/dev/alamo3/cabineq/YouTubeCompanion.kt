package dev.alamo3.cabineq

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks the CabinEQ YouTube companion (a separate app, installed from GitHub) for a playable link.
 * CabinEQ itself carries no downloader: if the companion is not installed, [installed] is false
 * and nothing here is used. Protocol: see the companion's ResolveService.
 */
object YouTubeCompanion {
    private const val TAG = "CabinEQ/YouTube"
    const val PACKAGE = "dev.alamo3.cabineq.yt"
    private const val SERVICE = "dev.alamo3.cabineq.yt.ResolveService"
    private const val BIND_WAIT_MS = 8_000L

    class Link(val url: String, val headers: Map<String, String>, val quality: String, val videoId: String)

    class NotFound(message: String) : Exception(message)

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    /** YouTube should be used at all: the companion is installed and the setting allows it. */
    fun usable(context: Context): Boolean = Settings(context).youtubeSource != Settings.YOUTUBE_OFF && installed(context)

    @Volatile private var service: Messenger? = null
    private var bound = false
    private val lock = Object()
    private val replies = HandlerThread("CabinEQ-YouTubeReplies").apply { start() }
    private val pending = ConcurrentHashMap<Int, (Bundle) -> Unit>()
    private val ids = AtomicInteger()

    private val replyMessenger = Messenger(object : Handler(replies.looper) {
        override fun handleMessage(msg: Message) {
            val data = msg.data ?: return
            pending.remove(data.getInt("rid", -1))?.invoke(data)
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(lock) { service = binder?.let(::Messenger); lock.notifyAll() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { service = null }
        }
        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) { service = null; bound = false }
        }
    }

    private fun messenger(context: Context): Messenger {
        synchronized(lock) {
            service?.let { return it }
            if (!bound) {
                val intent = Intent().setComponent(ComponentName(PACKAGE, SERVICE))
                bound = context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) throw IllegalStateException("The YouTube companion isn't installed")
            }
            val until = System.currentTimeMillis() + BIND_WAIT_MS
            while (service == null) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) throw IllegalStateException("The YouTube companion did not start")
                lock.wait(left)
            }
            return service!!
        }
    }

    /**
     * A playable link for [track], blocking up to [timeoutMs]. Throws [NotFound] when YouTube has
     * no suitable recording, anything else when the companion could not answer.
     */
    fun resolve(context: Context, track: Track, timeoutMs: Long = 25_000L): Link {
        val target = messenger(context)
        val rid = ids.incrementAndGet()
        val done = CountDownLatch(1)
        var answer: Bundle? = null
        pending[rid] = { answer = it; done.countDown() }
        val msg = Message.obtain(null, 1).apply {
            data = Bundle().apply {
                putInt("rid", rid)
                putString("title", track.title)
                putString("artist", track.artist)
                putInt("durationSec", (track.durationMs / 1000).toInt())
                track.youtubeId?.let { putString("videoId", it) }
            }
            replyTo = replyMessenger
        }
        val started = System.currentTimeMillis()
        try {
            target.send(msg)
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw java.net.SocketTimeoutException("YouTube took too long")
        } catch (e: android.os.RemoteException) {
            synchronized(lock) { service = null }
            throw IllegalStateException("The YouTube companion stopped", e)
        } finally {
            pending.remove(rid)
        }
        val a = answer!!
        if (!a.getBoolean("ok")) {
            val error = a.getString("error") ?: "YouTube failed"
            DLog.i(TAG, "\"${track.title}\": $error")
            if (a.getBoolean("notFound")) throw NotFound("Not found on YouTube")
            throw IllegalStateException("YouTube: $error")
        }
        val headers = HashMap<String, String>()
        a.getBundle("headers")?.let { h -> h.keySet().forEach { k -> h.getString(k)?.let { headers[k] = it } } }
        DLog.i(TAG, "\"${track.title}\" -> ${a.getString("videoId")} (${a.getString("channel")}) in ${System.currentTimeMillis() - started} ms")
        return Link(a.getString("url")!!, headers, a.getString("quality") ?: "YouTube", a.getString("videoId").orEmpty())
    }

    class Song(val videoId: String, val title: String, val artist: String, val album: String?, val durationSec: Int, val thumbnail: String?) {
        fun toTrack() = Track(
            title = title,
            artist = artist,
            album = album,
            artworkUrl = thumbnail,
            durationMs = durationSec * 1000L,
            source = Track.Source.ADDON,
            youtubeId = videoId,
        )
    }

    /** Songs from YouTube Music for the search screen; empty when the companion can't answer. */
    fun search(context: Context, query: String, limit: Int = 20, timeoutMs: Long = 8_000L): List<Song> {
        val target = runCatching { messenger(context) }.getOrNull() ?: return emptyList()
        val rid = ids.incrementAndGet()
        val done = CountDownLatch(1)
        var answer: Bundle? = null
        pending[rid] = { answer = it; done.countDown() }
        try {
            target.send(Message.obtain(null, 3).apply {
                data = Bundle().apply { putInt("rid", rid); putString("query", query); putInt("limit", limit) }
                replyTo = replyMessenger
            })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return emptyList()
        } catch (e: Exception) {
            return emptyList()
        } finally {
            pending.remove(rid)
        }
        val a = answer ?: return emptyList()
        if (!a.getBoolean("ok")) { DLog.i(TAG, "search failed: ${a.getString("error")}"); return emptyList() }
        @Suppress("DEPRECATION")
        val list = a.getParcelableArrayList<Bundle>("results").orEmpty()
        return list.mapNotNull { b ->
            val id = b.getString("videoId") ?: return@mapNotNull null
            Song(id, b.getString("title").orEmpty(), b.getString("artist").orEmpty(), b.getString("album"),
                b.getInt("durationSec"), b.getString("thumbnail"))
        }
    }

    /** The video picked for [track] was wrong or would not play: the companion searches again next time. */
    fun forget(context: Context, track: Track) {
        runCatching {
            messenger(context).send(Message.obtain(null, 2).apply {
                data = Bundle().apply { putString("title", track.title); putString("artist", track.artist) }
            })
        }
    }
}
