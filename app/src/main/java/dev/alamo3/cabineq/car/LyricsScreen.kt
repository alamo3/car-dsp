package dev.alamo3.cabineq.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.alamo3.cabineq.FilePlayer
import dev.alamo3.cabineq.Lyrics
import java.util.concurrent.Executors

/**
 * Lyrics in the car. Android Auto has no lyrics surface of its own, so this is a plain
 * [ListTemplate]: a small window of lines around the one being sung, refreshed about once a
 * second — the fastest a host reliably accepts — with the current line marked and each line
 * tappable to seek there.
 *
 * Templates want short text by design (driver distraction, and hosts truncate), so this can
 * never be a scrolling song sheet; the phone view is the full one.
 */
class LyricsScreen(carContext: CarContext) : Screen(carContext) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "CabinEQ-Lyrics").apply { isDaemon = true } }

    @Volatile private var sheet: Lyrics.Sheet? = null
    /** Media id of the song [sheet] (or the lookup in flight) belongs to; a different song reloads. */
    @Volatile private var loadedFor: String? = null
    @Volatile private var done = false
    /** Lets a slow lookup for a song that has since changed be dropped. */
    private var loadToken = 0

    private var shownIndex = Int.MIN_VALUE
    /** The listener's own correction (set on the phone's lyrics page). */
    private val offsetMs: Int by lazy { dev.alamo3.cabineq.Settings(carContext).lyricsOffsetMs }

    private companion object {
        /** Rows per template: enough to read ahead, few enough to fit a compact host. */
        const val WINDOW = 8
        /** Half the 1 s refresh: see [currentIndex]. */
        const val CAR_REFRESH_LEAD_MS = 500L
    }

    /** Only the top screen should poke the host; a buried one would just be throttled. */
    private val tick = object : Runnable {
        override fun run() {
            val top = runCatching { screenManager.top === this@LyricsScreen }.getOrDefault(false)
            if (top) {
                val idx = currentIndex()
                // A new song: redraw so onGetTemplate sees it and fetches that song's lyrics.
                val songChanged = FilePlayer.peek()?.currentTrack?.mediaId.let { it != null && it != loadedFor }
                if (idx != shownIndex || songChanged) {
                    shownIndex = idx
                    runCatching { invalidate() }
                }
            }
            main.postDelayed(this, 1_000)
        }
    }

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) main.removeCallbacks(tick)
        })
        main.postDelayed(tick, 1_000)
    }

    override fun onGetTemplate(): Template {
        val player = FilePlayer.peek()
        val track = player?.currentTrack
        if (track != null) load(track.mediaId)
        if (player == null || track == null) {
            return MessageTemplate.Builder("Nothing playing.")
                .setTitle("Lyrics")
                .setHeaderAction(Action.BACK)
                .build()
        }
        val title = track.title
        val s = sheet
        if (s == null) {
            return MessageTemplate.Builder(
                if (done) "Couldn't reach the lyrics service." else "Looking for lyrics…"
            )
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .build()
        }
        if (s.empty) {
            return MessageTemplate.Builder("No lyrics found for this song.")
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .build()
        }

        val list = ItemList.Builder()
        if (s.synced) {
            val idx = currentIndex()
            shownIndex = idx
            // A fixed-size window: the host keeps its own scroll offset between templates, so a
            // list that grows and shrinks shifts the lines out from under the reader. Two lines
            // of context above, the current one third from the top.
            val from = if (idx < 0) 0 else (idx - 2).coerceAtLeast(0)
            val to = (from + WINDOW - 1).coerceAtMost(s.lines.lastIndex)
            for (i in from..to) {
                val line = s.lines[i]
                val row = Row.Builder()
                    .setTitle(if (i == idx) "▸ ${line.text}" else line.text)
                    .setOnClickListener {
                        if (line.atMs >= 0) {
                            FilePlayer.peek()?.seekTo(line.atMs)
                            runCatching { invalidate() }
                        }
                    }
                if (i == idx) row.setBrowsable(true)
                list.addItem(row.build())
            }
        } else {
            // Untimed: no highlight and no seeking, just the words.
            s.lines.filter { it.text.isNotBlank() }.take(24).forEach {
                list.addItem(Row.Builder().setTitle(it.text).build())
            }
        }
        return ListTemplate.Builder()
            .setTitle("Lyrics")
            .setSingleList(list.build())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun currentIndex(): Int {
        val s = sheet ?: return -1
        val p = FilePlayer.peek() ?: return -1
        // The host redraws about once a second, so a line can show up to a second after its time:
        // look half of that ahead, so on average it is on time.
        return s.indexAt(p.audiblePositionMs + Lyrics.LEAD_MS + CAR_REFRESH_LEAD_MS + offsetMs)
    }

    /**
     * One lookup per song: the screen stays open across songs, so a new song drops the old
     * lyrics at once (never the previous song's words under the new title) and fetches its own.
     * The cache in [Lyrics] makes a song heard before instant.
     */
    private fun load(mediaId: String) {
        if (loadedFor == mediaId) return
        loadedFor = mediaId
        sheet = null
        done = false
        shownIndex = Int.MIN_VALUE
        val token = ++loadToken
        val app: Context = carContext.applicationContext
        io.execute {
            val p = FilePlayer.peek()
            // A song played before paints at once; only a first-time lookup waits on the network.
            val found = p?.let { Lyrics.cachedForPlayer(app, it) } ?: p?.let { Lyrics.forPlayer(app, it) }
            main.post {
                // The song moved on while this was running: that lookup is for another song.
                if (token != loadToken) return@post
                sheet = found
                done = true
                runCatching { invalidate() }
            }
        }
    }
}
