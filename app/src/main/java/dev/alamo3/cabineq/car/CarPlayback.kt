package dev.alamo3.cabineq.car

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.alamo3.cabineq.EqService
import dev.alamo3.cabineq.FilePlayer
import dev.alamo3.cabineq.MediaBrowse
import dev.alamo3.cabineq.Track
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Playback entry point for the Car App Library screens.
 *
 * Item clicks run on the main thread inside our process, and starting a track joins the previous
 * worker (up to ~2 s), so everything happens on a background thread. The id is a [MediaBrowse]
 * media id ("q:3", "foryou#t2", "alb:<id>#<trackId>"), which already knows how to rebuild its
 * queue — the same ids the media-browser tree hands to Android Auto.
 */
object CarPlayback {
    private const val TAG = "CabinEQ/CarPlay"
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-CarPlay").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Plays exactly the list the user was looking at.
     *
     * Ids like "q:3" are *positions*: resolving them later re-reads history / re-queries the
     * addon, and the list may have changed in between — which played the wrong song. Screens
     * capture their rendered list and hand it over here instead.
     */
    fun playTracks(
        context: Context,
        tracks: List<Track>,
        index: Int,
        title: String?,
        onStarted: (() -> Unit)? = null,
        radio: Track? = null,
    ) {
        if (tracks.isEmpty()) return
        val app = context.applicationContext
        val start = index.coerceIn(0, tracks.lastIndex)
        Log.i(
            TAG,
            "playTracks: ${tracks.size} tracks, index=$start, title=$title, first=${tracks[start].title}" +
                " (source=${tracks[start].source} id=${tracks[start].sourceId} url=${tracks[start].streamUrl})"
        )
        executor.execute {
            // Bring the session up first: the host renders the playback template from it, and a
            // session registered *after* the template appears shows up as "nothing playing".
            ensureService(app)
            FilePlayer.get(app).play(tracks, start, title, radio = radio)
            runCatching {
                app.startForegroundService(
                    Intent(app, EqService::class.java).setAction(EqService.ACTION_PLAYER_ATTACH)
                )
            }.onFailure {
                Log.w(TAG, "Couldn't attach the EQ service: ${it.javaClass.simpleName}: ${it.message}")
            }
            onStarted?.let { started -> main.post { runCatching(started) } }
        }
    }

    /**
     * A search result is not a queue: it is a list of titles that matched the words. Play just
     * the tapped track; the player asks [dev.alamo3.cabineq.Radio] for one more when it drains.
     */
    fun playSearch(
        context: Context,
        tapped: Track,
        title: String?,
        onStarted: (() -> Unit)? = null,
    ) {
        playTracks(context, listOf(tapped), 0, title, onStarted, radio = tapped)
    }

    fun playId(context: Context, mediaId: String, onStarted: (() -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            val spec = runCatching { MediaBrowse.playback(app, mediaId) }.getOrElse {
                Log.w(TAG, "Can't resolve $mediaId: ${it.message}")
                null
            } ?: return@execute
            FilePlayer.get(app).play(spec.tracks, spec.index, spec.title)
            runCatching {
                app.startForegroundService(
                    Intent(app, EqService::class.java).setAction(EqService.ACTION_PLAYER_ATTACH)
                )
            }.onFailure {
                Log.w(TAG, "Couldn't attach the EQ service: ${it.javaClass.simpleName}: ${it.message}")
            }
            // Navigate on the main thread: callers use this to open the playback screen.
            onStarted?.let { started -> main.post { runCatching(started) } }
        }
    }

    /**
     * Called once per car connection. Android Auto is supposed to send "play" on its own (the
     * "Start music automatically" setting), but that does not fire in every host — so if we have
     * a restored queue and were only paused, pick it up where it stopped.
     *
     * Never overrides an explicit pause: navigating around the car UI must not start playback.
     */
    fun resumeOnConnect(context: Context) {
        val app = context.applicationContext
        ensureService(app)
        executor.execute {
            val player = FilePlayer.get(app)
            if (player.userPaused) {
                Log.i("CabinEQ/CarPlay", "Not resuming: playback was paused by the user")
                return@execute
            }
            val resumable = player.state == FilePlayer.State.PAUSED ||
                (player.state == FilePlayer.State.IDLE && player.queue.isNotEmpty() && player.positionMs > 0)
            if (!resumable) return@execute
            Log.i("CabinEQ/CarPlay", "Resuming on connect at ${player.positionMs} ms")
            player.resume()
        }
    }

    /** Brings the EQ service (and therefore the MediaSession) up without starting playback. */
    fun ensureService(context: Context) {
        val app = context.applicationContext
        runCatching {
            app.startForegroundService(
                Intent(app, EqService::class.java).setAction(EqService.ACTION_PLAYER_ATTACH)
            )
        }.onFailure {
            Log.w(TAG, "ensureService failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
