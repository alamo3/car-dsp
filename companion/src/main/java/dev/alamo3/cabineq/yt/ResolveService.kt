package dev.alamo3.cabineq.yt

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.concurrent.Executors

/**
 * What CabinEQ binds to. Protocol (kept in step with CabinEQ's YouTubeCompanion):
 *
 *  request  what=1 (resolve), data: rid, title, artist?, durationSec (0 = unknown), replyTo set
 *           what=2 (forget a wrong pick), data: title, artist?
 *           what=3 (search), data: rid, query, limit -> results: list of {videoId, title, artist,
 *                  album?, durationSec, thumbnail?}
 *  A resolve may carry videoId: that exact video, no search.
 *  reply    what=1, data: rid, ok=true + url, headers (Bundle), quality, videoId, title, channel, durationSec
 *                         ok=false + error, notFound (Boolean)
 *
 * Only CabinEQ is answered: the calling app's package is checked on every request.
 */
class ResolveService : Service() {
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "CabinEQYt-Resolve").apply { isDaemon = true } }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo
            val data = Bundle(msg.data)
            val what = msg.what
            if (!allowed(msg.sendingUid)) {
                Log.w(TAG, "refused a request from uid ${msg.sendingUid}")
                replyTo?.let { send(it, Bundle().apply { putInt("rid", data.getInt("rid", -1)); putBoolean("ok", false); putString("error", "not allowed") }) }
                return
            }
            pool.execute { work(what, data, replyTo) }
        }
    }

    private fun allowed(uid: Int): Boolean =
        packageManager.getPackagesForUid(uid)?.any { it == CABINEQ || it.startsWith("$CABINEQ.") } == true

    private fun work(what: Int, data: Bundle, replyTo: Messenger?) {
        lastRequestAt = System.currentTimeMillis()
        val title = data.getString("title").orEmpty()
        val artist = data.getString("artist")
        if (what == 2) { Yt.forget(this, title, artist); return }
        if (what == 3) { searchReply(data, replyTo); return }
        // The request's id, echoed so CabinEQ can match the answer to its question.
        val reply = Bundle().apply { putInt("rid", data.getInt("rid", -1)) }
        try {
            val sec = data.getInt("durationSec", 0).takeIf { it > 0 }
            val r = Yt.resolve(this, title, artist, sec, data.getString("videoId"))
            reply.putBoolean("ok", true)
            reply.putString("url", r.url)
            reply.putBundle("headers", Bundle().apply { r.headers.forEach { (k, v) -> putString(k, v) } })
            reply.putString("quality", r.quality)
            reply.putString("videoId", r.videoId)
            reply.putString("title", r.title)
            reply.putString("channel", r.channel)
            reply.putInt("durationSec", r.durationSec)
            Log.i(TAG, "\"$title\" -> ${r.videoId} (${r.channel}, ${r.quality})")
        } catch (e: Exception) {
            reply.putBoolean("ok", false)
            reply.putBoolean("notFound", e is Yt.NotFound)
            reply.putString("error", e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(300) ?: e.javaClass.simpleName)
            Log.w(TAG, "\"$title\" failed: ${e.message}")
        }
        replyTo?.let { send(it, reply) }
    }

    private fun searchReply(data: Bundle, replyTo: Messenger?) {
        val reply = Bundle().apply { putInt("rid", data.getInt("rid", -1)) }
        try {
            val songs = Yt.searchSongs(data.getString("query").orEmpty(), data.getInt("limit", 20))
            reply.putBoolean("ok", true)
            reply.putParcelableArrayList("results", ArrayList(songs.map { s ->
                Bundle().apply {
                    putString("videoId", s.videoId)
                    putString("title", s.title)
                    putString("artist", s.artist)
                    putString("album", s.album)
                    putInt("durationSec", s.durationSec ?: 0)
                    putString("thumbnail", s.thumbnail)
                }
            }))
        } catch (e: Exception) {
            reply.putBoolean("ok", false)
            reply.putString("error", e.message ?: e.javaClass.simpleName)
        }
        replyTo?.let { send(it, reply) }
    }

    private fun send(to: Messenger, data: Bundle) {
        runCatching { to.send(Message.obtain(null, 1).apply { this.data = data }) }
    }

    private val messenger = Messenger(handler)
    override fun onBind(intent: Intent?): IBinder = messenger.binder

    companion object {
        /** When CabinEQ last asked for something: an update must not cut off a song it is loading. */
        @Volatile var lastRequestAt = 0L
        private const val TAG = "CabinEQYt/Service"
        private const val CABINEQ = "dev.alamo3.cabineq"
    }
}
