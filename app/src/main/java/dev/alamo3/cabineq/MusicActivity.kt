package dev.alamo3.cabineq

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.alamo3.cabineq.addon.AddonException
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.jam.JamHost
import dev.alamo3.cabineq.music.Artwork
import dev.alamo3.cabineq.music.Ui
import dev.alamo3.cabineq.torbox.AudioPicker
import dev.alamo3.cabineq.torbox.TorBoxClient
import dev.alamo3.cabineq.torbox.TorBoxException
import dev.alamo3.cabineq.torbox.TorBoxTorrent
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Music app screen. Everything plays through [FilePlayer] → native DSP.
 *
 *  - Search as you type through an Eclipse addon such as Nebula (Apple Music catalog, audio
 *    served from the user's TorBox): songs, an album carousel and artists, with cover art.
 *  - Album, artist and playlist pages; settings for the sources.
 *  - Mini player at the bottom, expanding to a full Now Playing view with seeking.
 *
 * Framework-only like the rest of the app. Pages are rendered into one scroll view; a stack
 * of render functions gives back navigation. Network calls run off the main thread and each
 * new action bumps [op], so stale results are dropped.
 */
class MusicActivity : Activity() {

    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private val player by lazy { FilePlayer.get(this) }

    private lateinit var searchInput: EditText
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout

    // Mini player
    private lateinit var mini: LinearLayout
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniToggle: ImageView
    private lateinit var miniProgress: ProgressBar

    // Full player
    private lateinit var full: ScrollView
    private lateinit var fullArt: ImageView
    private lateinit var fullTitle: TextView
    private lateinit var fullArtist: TextView
    private lateinit var fullContext: TextView
    private lateinit var fullRepeat: TextView
    private lateinit var fullSeek: SeekBar
    private lateinit var fullPos: TextView
    private lateinit var fullDur: TextView
    private lateinit var fullToggle: ImageView
    private lateinit var fullInfo: TextView
    private lateinit var fullNote: TextView
    private var seeking = false

    // Lyrics page (the car has its own screen; both share Lyrics.kt and its disk cache).
    private var lyricsOpen = false
    private var lyricsBlock: LinearLayout? = null
    private var lyricViews = ArrayList<TextView>()
    private var lyricSheet: Lyrics.Sheet? = null
    private var lyricIndex = -1
    private var lyricScrolled = Int.MIN_VALUE
    private var lyricTouchedAt = 0L
    private var lyricToken = 0

    @Volatile private var op = 0
    private var shownVersion = -1
    private var askedForNotifications = false
    private var shownArt: String? = "\u0000"
    private val stack = ArrayDeque<() -> Unit>()
    private var searchPageOnTop = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshPlayer()
            // A download finishing (or stalling) updates the page that shows it.
            val v = Downloads.version
            if (v != shownDownloads) {
                shownDownloads = v
                downloadRefresh?.invoke()
            }
            queueRefresh?.invoke()
            val lv = Library.version
            if (lv != shownLibrary) {
                shownLibrary = lv
                libraryRefresh?.invoke()
            }
            handler.postDelayed(this, 500)
        }
    }

    /** While the lyrics page is open the highlight is checked 10x a second, not with the 2 Hz UI tick. */
    private val lyricTicker = object : Runnable {
        override fun run() {
            if (!lyricsOpen) return
            updateLyricsHighlight()
            handler.postDelayed(this, 100)
        }
    }

    private lateinit var fullLike: TextView
    private lateinit var fullWhy: TextView
    /** Redraws a library page when the library changes underneath it. */
    private var libraryRefresh: (() -> Unit)? = null
    /** Redraws the queue page when the queue or the playing song changes. */
    private var queueRefresh: (() -> Unit)? = null
    private var shownLibrary = -1

    /** Redraws the download status on the page being shown, when there is one. */
    private var downloadRefresh: (() -> Unit)? = null
    private var shownDownloads = -1

    private val searchRunnable = Runnable { runSearch(searchInput.text.toString().trim(), fromTyping = true) }

    // ---------------------------------------------------------------------------------
    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.decorView.setBackgroundColor(Ui.BG)
        if (Build.VERSION.SDK_INT >= 30) {
            // Light status/nav bar icons on the dark background.
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        setContentView(buildUi())
        navigate { renderHome() }
        startFromPlayFromSearch(intent)
        handleImportIntent(intent)
        // A download interrupted by the app closing carries on from where it stopped.
        runCatching { Downloads.resume(this) }
    }

    /**
     * "Play <something> on CabinEQ" from Assistant or the car's voice search, declared in the
     * manifest as MEDIA_PLAY_FROM_SEARCH. It is the same search the app performs itself.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startFromPlayFromSearch(intent)
        handleImportIntent(intent)
    }

    /** Same handling whether the activity was created by the intent or already running. */
    private fun startFromPlayFromSearch(intent: Intent?) {
        if (intent?.action != ACTION_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(android.app.SearchManager.QUERY)?.trim().orEmpty()
        if (query.isBlank() || !::searchInput.isInitialized) return
        searchInput.setText(query)
        searchInput.setSelection(query.length)
        handler.removeCallbacks(searchRunnable)
        runSearch(query, fromTyping = false)
    }

    override fun onResume() {
        super.onResume()
        shownVersion = -1
        handler.post(ticker)
    }


    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        op++
        handler.removeCallbacks(searchRunnable)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            full.visibility == View.VISIBLE -> showFull(false)
            stack.size > 1 -> {
                stack.removeLast()
                searchPageOnTop = false
                op++
                stack.last().invoke()
                scroll.scrollTo(0, 0)
            }
            // Back from another tab's first page goes Home before it leaves the app.
            tab != Tab.HOME -> switchTab(Tab.HOME)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------------------------
    // Layout

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Header: search pill + settings
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(8))
        }
        searchInput = EditText(this).apply {
            hint = "Search songs, albums, artists"
            setHintTextColor(Ui.TEXT_2)
            setTextColor(Ui.TEXT)
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = Ui.rounded(Ui.SURFACE, dp(24).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT_2)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Qualified on purpose: inside this apply block `handler` would resolve to
                    // TextView.getHandler(), which is null until the view is attached.
                    this@MusicActivity.handler.removeCallbacks(searchRunnable)
                    val q = s?.toString()?.trim().orEmpty()
                    when {
                        q.isEmpty() -> if (searchPageOnTop) onBackPressedCompat()
                        q.startsWith("magnet:") -> Unit       // added on the Search key, not while typing
                        q.length >= 2 -> this@MusicActivity.handler
                        .postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
                    }
                }
            })
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    handler.removeCallbacks(searchRunnable)
                    hideKeyboard()
                    runSearch(text.toString().trim(), fromTyping = false)
                    true
                } else false
            }
        }
        header.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.iconButton(this, android.R.drawable.ic_menu_preferences, dp(44), Ui.TEXT_2) {
            hideKeyboard()
            navigate { renderSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(header)

        // Page content
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply {
            setOnTouchListener { _, _ -> lyricTouchedAt = System.currentTimeMillis(); false }
            isFillViewport = true
            addView(page)
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        column.addView(buildMini())
        column.addView(buildTabs())
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(buildFull(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.padForSystemBars()
        return root
    }

    private fun buildMini(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Ui.SURFACE)
        }
        miniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SURFACE_2)
        }
        wrap.addView(miniProgress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setOnClickListener { showFull(true) }
        }
        Ui.selectable(mini)
        miniArt = Ui.cover(this, dp(44), dp(6).toFloat())
        mini.addView(miniArt, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        miniTitle = Ui.text(this, "", 15f, bold = true)
        miniSub = Ui.text(this, "", 13f, Ui.TEXT_2)
        texts.addView(miniTitle)
        texts.addView(miniSub)
        mini.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        miniToggle = Ui.iconButton(this, android.R.drawable.ic_media_play, dp(44)) { transport { player.togglePause() } }
        mini.addView(miniToggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini.addView(Ui.iconButton(this, android.R.drawable.ic_media_next, dp(44)) { transport { player.next() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        wrap.addView(mini)
        return wrap
    }

    private fun buildFull(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(32))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(Ui.iconButton(this, android.R.drawable.arrow_down_float, dp(44), Ui.TEXT) { showFull(false) },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        fullContext = Ui.text(this, "", 13f, Ui.TEXT_2).apply { gravity = Gravity.CENTER }
        top.addView(fullContext, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(View(this), LinearLayout.LayoutParams(dp(48), dp(48)))
        col.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val artSize = min(resources.displayMetrics.widthPixels - dp(48), dp(420))
        fullArt = Ui.cover(this, artSize, dp(12).toFloat())
        col.addView(fullArt, LinearLayout.LayoutParams(artSize, artSize).apply { topMargin = dp(16) })

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, dp(8))
        }
        fullTitle = Ui.text(this, "", 22f, bold = true, lines = 2)
        fullArtist = Ui.text(this, "", 17f, Ui.ACCENT)
        titles.addView(fullTitle)
        titles.addView(fullArtist)
        // Why the radio picked this one; empty for songs the listener chose.
        fullWhy = Ui.text(this, "", 12f, Ui.TEXT_2, lines = 2).apply { setPadding(0, dp(4), 0, 0) }
        titles.addView(fullWhy)
        col.addView(titles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fullSeek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SURFACE_2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) fullPos.text = Ui.mmss(p * 1000L)
                }
                override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    seeking = false
                    // A stream with no known duration has max=1 here; seeking would jump to 0.
                    if (player.durationMs > 0) player.seekTo(sb.progress * 1000L)
                }
            })
        }
        col.addView(fullSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fullPos = Ui.text(this, "0:00", 12f, Ui.TEXT_2)
        fullDur = Ui.text(this, "0:00", 12f, Ui.TEXT_2).apply { gravity = Gravity.END }
        times.addView(fullPos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(fullDur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16)
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        controls.addView(Ui.iconButton(this, android.R.drawable.ic_media_previous, dp(56)) { transport { player.previous() } },
            LinearLayout.LayoutParams(dp(64), dp(64)))
        fullToggle = Ui.iconButton(this, android.R.drawable.ic_media_play, dp(76), Color.WHITE, Ui.ACCENT) {
            transport { player.togglePause() }
        }
        controls.addView(fullToggle, LinearLayout.LayoutParams(dp(80), dp(80)).apply { leftMargin = dp(24); rightMargin = dp(24) })
        controls.addView(Ui.iconButton(this, android.R.drawable.ic_media_next, dp(56)) { transport { player.next() } },
            LinearLayout.LayoutParams(dp(64), dp(64)))
        col.addView(controls)

        // Explicit feedback: worth far more than any implicit signal (see Taste.kt).
        val feedback = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        fullLike = Ui.pill(this, "♡  Like", filled = false) {
            player.currentTrack?.let {
                // A toggle: liked songs live in the library, and a like can be taken back.
                if (Library.isLiked(this, it)) Taste.unlike(this, it) else Taste.like(this, it)
                shownVersion = -1
                refreshSoon()
            }
        }
        feedback.addView(fullLike, wrapLp().apply { rightMargin = dp(8) })
        feedback.addView(Ui.pill(this, "✕  Not for me", filled = false) {
            player.currentTrack?.let {
                Taste.dislike(this, it)
                toast("Less like this — skipping")
                transport { player.next() }
            }
        }, wrapLp().apply { rightMargin = dp(8) })
        feedback.addView(Ui.pill(this, "⋯", filled = false) {
            player.currentTrack?.let { showTrackMenu(it, nowPlaying = true) }
        }, wrapLp())
        col.addView(feedback)

        // Queue loop: off -> all -> one, persisted and also honoured by the car.
        val repeatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        fullRepeat = Ui.pill(this, repeatLabel(), filled = false) {
            player.cycleRepeat()
            fullRepeat.text = repeatLabel()
        }
        repeatRow.addView(fullRepeat)
        repeatRow.addView(Ui.pill(this, "Queue", filled = false) {
            showFull(false)
            navigate { renderQueue() }
        }, wrapLp().apply { leftMargin = dp(10) })
        repeatRow.addView(Ui.pill(this, "Lyrics", filled = false) {
            // Lyrics is a page of its own: the full player is an overlay on top of the stack.
            showFull(false)
            navigate { renderLyrics() }
        },
            wrapLp().apply { leftMargin = dp(10) })
        col.addView(repeatRow)

        fullInfo = Ui.text(this, "", 12f, Ui.TEXT_2, lines = 4).apply { gravity = Gravity.CENTER }
        fullNote = Ui.text(this, "", 13f, Ui.ERROR, lines = 4).apply { gravity = Gravity.CENTER }
        col.addView(fullNote)
        col.addView(fullInfo)

        full = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            visibility = View.GONE
            isClickable = true    // swallow touches meant for the page underneath
            addView(col)
        }
        return full
    }

    // ---------------------------------------------------------------------------------
    // Navigation

    private fun navigate(render: () -> Unit) {
        stack.addLast(render)
        searchPageOnTop = false
        op++
        render()
        scroll.scrollTo(0, 0)
    }

    private fun onBackPressedCompat() {
        @Suppress("DEPRECATION") onBackPressed()
    }

    private fun clearPage() {
        page.removeAllViews()
        lyricsOpen = false
        handler.removeCallbacks(lyricTicker)
        downloadRefresh = null
        libraryRefresh = null
        queueRefresh = null
        lyricsBlock = null
        lyricViews = ArrayList()
    }

    // ---------------------------------------------------------------------------------
    // Pages

    private fun renderHome() {
        clearPage()
        page.addView(Ui.text(this, "Music", 30f, bold = true).apply { setPadding(0, dp(8), 0, dp(8)) })

        val addonReady = settings.addonLink.isNotBlank()
        if (!addonReady) {
            page.addView(card(
                "Connect Nebula to search",
                "Paste the Nebula link you installed in Eclipse (Settings → Connections). Search uses Apple Music's catalog; audio streams from your TorBox.",
                "Connect",
            ) { navigate { renderSettings() } })
        }

        // Recently played is local, so the page is never blank while the network is thinking.
        val recent = MediaBrowse.recentTracks(this).take(24)
        if (recent.isNotEmpty()) {
            page.addView(sectionTitle("Recently played"))
            page.addView(trackCarousel(recent) { i -> play(recent, i, "Recently played") })
        }

        // The same content the car's home screen shows: more from the artist you play most,
        // plus their albums. Two addon calls, so it fills in when they arrive.
        if (addonReady) {
            val sections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            page.addView(sections)
            // One container each: the two background loads finish in either order, and the
            // shelves must not swap places between renders.
            val recommendedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val albumsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sections.addView(recommendedBox)
            sections.addView(albumsBox)
            val id = op
            // Song-level picks from the engine: mixed artists, ranked by what this phone has
            // learned (see Taste/Radio), refreshed whenever home is rendered.
            background(id, { Radio.recommend(this, 12) }) { picks ->
                if (id != op || picks.isEmpty()) return@background
                recommendedBox.addView(sectionTitle("Recommended for you"))
                recommendedBox.addView(trackCarousel(picks) { i -> play(picks, i, "Recommended") })
            }
            background(id, { MediaBrowse.forYouContent(this) }) { fy ->
                if (fy == null || id != op) return@background
                if (fy.albums.isNotEmpty()) {
                    albumsBox.addView(sectionTitle("Albums"))
                    albumsBox.addView(albumCarousel(fy.albums) { al ->
                        addon()?.let { a -> navigate { renderAlbum(a, al) } }
                    })
                }
            }
        }

        page.addView(Ui.text(this, "All playback runs through CabinEQ's DSP: your EQ, preamp, limiter and speed compensation.", 12f, Ui.TEXT_2, lines = 3)
            .apply { setPadding(0, dp(24), 0, 0) })
    }

    /** The Library tab: everything that is the listener's own. */
    private fun renderLibrary() {
        clearPage()
        page.addView(Ui.text(this, "Library", 30f, bold = true).apply { setPadding(0, dp(8), 0, dp(8)) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        val draw = {
            box.removeAllViews()
            val likedCount = Library.liked(this).size
            val playlists = Library.playlists(this)
            val albumCount = Library.albums(this).size
            box.addView(simpleRow("Liked songs", if (likedCount == 0) "Tap ♡ on a song to keep it here" else "$likedCount songs",
                android.R.drawable.btn_star_big_on) { navigate { renderLiked() } })
            box.addView(simpleRow("Playlists", if (playlists.isEmpty()) "Make your own, or import one" else "${playlists.size} playlists",
                android.R.drawable.ic_menu_sort_by_size) { navigate { renderPlaylists() } })
            box.addView(simpleRow("Albums", if (albumCount == 0) "Save an album from its page" else "$albumCount saved",
                android.R.drawable.ic_menu_gallery) { navigate { renderSavedAlbums() } })
            // Straight to the most recent playlists, without the extra tap.
            for (p in playlists.take(4)) {
                box.addView(simpleRow(p.name, "Playlist · ${p.tracks.size} songs", android.R.drawable.ic_menu_sort_by_size) {
                    navigate { renderUserPlaylist(p.id) }
                })
            }
            // On the phone: what works with no signal.
            val downloads = Downloads.collections(this)
            if (downloads.isNotEmpty()) {
                box.addView(sectionTitle("Downloads"))
                for (c in downloads) {
                    val st = Downloads.status(this, c)
                    val sub = if (st.complete) "${st.total} songs · on this phone" else "${st.done} of ${st.total} downloaded"
                    box.addView(simpleRow(c.title, sub, android.R.drawable.stat_sys_download_done) {
                        navigate { renderDownload(c.id) }
                    })
                }
            }
        }
        draw()
        libraryRefresh = { draw() }
        downloadRefresh = { draw() }
    }

    /** The Search tab before anything is typed: what worked before, one tap away. */
    private fun renderSearchHome() {
        clearPage()
        page.addView(Ui.text(this, "Search", 30f, bold = true).apply { setPadding(0, dp(8), 0, dp(8)) })
        val recents = settings.recentSearches
        if (recents.isEmpty()) {
            page.addView(Ui.text(this, "Songs, albums and artists — typos are fine, and a line of the lyrics works too.",
                14f, Ui.TEXT_2, lines = 3).apply { setPadding(0, dp(8), 0, 0) })
            return
        }
        page.addView(sectionTitle("Recent searches"))
        for (q in recents) {
            page.addView(simpleRow(q, null, android.R.drawable.ic_menu_recent_history) {
                searchInput.setText(q)
                searchInput.setSelection(q.length)
                handler.removeCallbacks(searchRunnable)
                runSearch(q, fromTyping = false)
            })
        }
    }

    // ---------------------------------------------------------------------------------
    // Tabs

    private enum class Tab { HOME, SEARCH, LIBRARY }
    private var tab = Tab.HOME
    private val tabViews = HashMap<Tab, TextView>()

    private fun buildTabs(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
        }
        for ((t, label) in listOf(Tab.HOME to "Home", Tab.SEARCH to "Search", Tab.LIBRARY to "Library")) {
            val v = Ui.text(this, label, 14f, Ui.TEXT_2, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { switchTab(t, focusSearch = t == Tab.SEARCH) }
            }
            Ui.selectable(v)
            tabViews[t] = v
            bar.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        markTab()
        return bar
    }

    private fun markTab() {
        for ((t, v) in tabViews) v.setTextColor(if (t == tab) Ui.ACCENT else Ui.TEXT_2)
    }

    /** Each tab is a fresh stack: tapping a tab always lands on its first page. */
    private fun switchTab(to: Tab, focusSearch: Boolean = false) {
        tab = to
        markTab()
        stack.clear()
        searchPageOnTop = false
        if (to != Tab.SEARCH && searchInput.text.isNotEmpty()) {
            handler.removeCallbacks(searchRunnable)
            searchInput.text.clear()
        }
        when (to) {
            Tab.HOME -> navigate { renderHome() }
            Tab.SEARCH -> navigate { renderSearchHome() }
            Tab.LIBRARY -> navigate { renderLibrary() }
        }
        if (focusSearch) {
            searchInput.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else hideKeyboard()
    }

    /** A list of queue entries with the controls a queue needs: jump, move, remove, clear. */
    private fun renderQueue() {
        clearPage()
        page.addView(backRow())
        page.addView(Ui.text(this, "Queue", 24f, bold = true).apply { setPadding(0, dp(4), 0, dp(8)) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        var drawn = -1
        val draw = draw@{
            val p = player
            if (p.version == drawn) return@draw
            drawn = p.version
            box.removeAllViews()
            val entries = p.queuedTracks()
            val current = p.currentIndex
            if (entries.isEmpty()) {
                box.addView(Ui.text(this, "Nothing queued. Play something, or hold a song and choose “Add to queue”.",
                    14f, Ui.TEXT_2, lines = 3))
                return@draw
            }
            entries.getOrNull(current)?.let { now ->
                box.addView(sectionTitle("Now playing"))
                box.addView(simpleRow(now.track.title, now.track.artist, android.R.drawable.ic_media_play) { showFull(true) })
            }
            val upcoming = entries.withIndex().filter { it.index > current }
            if (upcoming.isNotEmpty()) {
                box.addView(sectionTitle("Up next", "Clear") {
                    upcoming.forEach { p.removeUpcoming(it.value.key) }
                })
                upcoming.forEachIndexed { n, (index, entry) ->
                    box.addView(queueRow(entry.track,
                        onPlay = { transport { p.skipTo(index) } },
                        onUp = if (n > 0) ({ p.moveUpcoming(entry.key, index - 1) }) else null,
                        onDown = if (n < upcoming.lastIndex) ({ p.moveUpcoming(entry.key, index + 1) }) else null,
                        onRemove = { p.removeUpcoming(entry.key) }))
                }
            } else if (p.radioActive) {
                box.addView(Ui.text(this, "Radio is on: the next song is picked when this one is nearly done.",
                    13f, Ui.TEXT_2, lines = 2).apply { setPadding(0, dp(12), 0, 0) })
            }
            val played = entries.take(current.coerceAtLeast(0))
            if (played.isNotEmpty()) {
                box.addView(sectionTitle("Played"))
                played.asReversed().take(30).forEach { entry ->
                    val index = entries.indexOf(entry)
                    box.addView(simpleRow(entry.track.title, entry.track.artist, android.R.drawable.ic_menu_recent_history) {
                        transport { p.skipTo(index) }
                    })
                }
            }
        }
        draw()
        queueRefresh = { draw() }
    }

    private fun queueRow(t: Track, onPlay: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onRemove: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), dp(8), dp(6))
            setOnClickListener { onPlay() }
        }
        Ui.selectable(texts)
        texts.addView(Ui.text(this, t.title, 16f))
        if (!t.artist.isNullOrBlank()) texts.addView(Ui.text(this, t.artist, 13f, Ui.TEXT_2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Large, separate targets: moving a song is a tap, not a drag.
        fun button(label: String, action: (() -> Unit)?) {
            val b = Ui.text(this, label, 18f, if (action == null) Ui.SURFACE_2 else Ui.TEXT_2, bold = true).apply {
                gravity = Gravity.CENTER
                if (action != null) { setOnClickListener { action() }; Ui.selectable(this) }
            }
            row.addView(b, LinearLayout.LayoutParams(dp(44), dp(48)))
        }
        button("↑", onUp)
        button("↓", onDown)
        button("✕", onRemove)
        return row
    }

    /** A shelf of square song tiles, like the car's home screen. */
    private fun trackCarousel(tracks: List<Track>, onClick: (Int) -> Unit): View {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val size = dp(148)
        tracks.forEachIndexed { i, t ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { onClick(i) }
            }
            Ui.selectable(tile)
            val art = Ui.cover(this, size, dp(8).toFloat())
            Artwork.load(art, t.artworkUrl, size * 2, query(t.artist, t.title), Artwork.Kind.SONG)
            tile.addView(art, LinearLayout.LayoutParams(size, size))
            tile.addView(
                Ui.text(this, t.title, 14f, lines = 2).apply { setPadding(0, dp(8), 0, 0) },
                LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            val sub = listOfNotNull(t.artist, t.album).joinToString(" · ")
            if (sub.isNotBlank()) {
                tile.addView(
                    Ui.text(this, sub, 12f, Ui.TEXT_2, lines = 1).apply { setPadding(0, dp(2), 0, 0) },
                    LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            strip.addView(tile, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(14) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    // ---------------------------------------------------------------------------------
    // Rows and blocks

    private fun sectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(22), 0, dp(10))
        }
        row.addView(Ui.text(this, text, 20f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null && onAction != null) {
            row.addView(Ui.text(this, action, 14f, Ui.ACCENT, bold = true).apply {
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener { onAction() }
            })
        }
        return row
    }

    private fun songRow(t: EclipseAddon.Track, showArt: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { onClick() }
        }
        Ui.selectable(row)
        if (showArt) {
            val art = Ui.cover(this, dp(52), dp(6).toFloat())
            Artwork.load(art, t.artworkUrl, dp(52) * 2, query(t.artist, t.title), Artwork.Kind.SONG)
            row.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(Ui.text(this, t.title, 16f))
        val sub = listOfNotNull(t.artist.ifBlank { null }, t.album).joinToString(" · ")
        if (sub.isNotBlank()) texts.addView(Ui.text(this, sub, 13f, Ui.TEXT_2).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnLongClickListener { showTrackMenu(t.toTrack(), nowPlaying = false); true }
        // "⤓": already on the phone (downloaded or prefetched), so it starts instantly.
        val local = runCatching { MediaCache.fileFor(this, t.toTrack()) != null }.getOrDefault(false)
        val right = listOfNotNull("⤓".takeIf { local },
            t.format?.uppercase(Locale.ROOT)?.takeIf { it == "FLAC" || it == "WAV" || it == "ALAC" },
            t.durationSec?.let { Ui.mmss(it * 1000L) }).joinToString("  ")
        if (right.isNotBlank()) row.addView(Ui.text(this, right, 12f, Ui.TEXT_2))
        return row
    }

    private fun numberedRow(n: Int, title: String, sub: String?, right: String?, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
        }
        Ui.selectable(row)
        row.addView(Ui.text(this, n.toString(), 15f, Ui.TEXT_2).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        texts.addView(Ui.text(this, title, 16f))
        if (!sub.isNullOrBlank()) texts.addView(Ui.text(this, sub, 13f, Ui.TEXT_2).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!right.isNullOrBlank()) row.addView(Ui.text(this, right, 13f, Ui.TEXT_2))
        return row
    }

    private fun artistRow(ar: EclipseAddon.Artist, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { onClick() }
        }
        Ui.selectable(row)
        val art = Ui.cover(this, dp(56), 0f, circle = true)
        Artwork.load(art, ar.artworkUrl, dp(56) * 2, ar.name, Artwork.Kind.ARTIST)
        row.addView(art, LinearLayout.LayoutParams(dp(56), dp(56)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(Ui.text(this, ar.name, 16f))
        texts.addView(Ui.text(this, "Artist", 13f, Ui.TEXT_2).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun albumCarousel(albums: List<EclipseAddon.Album>, onClick: (EclipseAddon.Album) -> Unit): View {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val size = dp(148)
        for (al in albums) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { onClick(al) }
            }
            Ui.selectable(tile)
            val art = Ui.cover(this, size, dp(8).toFloat())
            Artwork.load(art, al.artworkUrl, size * 2, query(al.artist, al.title), Artwork.Kind.ALBUM)
            tile.addView(art, LinearLayout.LayoutParams(size, size))
            tile.addView(Ui.text(this, al.title, 14f).apply { setPadding(0, dp(8), 0, 0) }, LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT))
            tile.addView(Ui.text(this, listOfNotNull(al.artist.ifBlank { null }, al.year).joinToString(" · "), 12f, Ui.TEXT_2)
                .apply { setPadding(0, dp(2), 0, 0) }, LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT))
            strip.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(14) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    private fun playlistCarousel(playlists: List<EclipseAddon.Playlist>, onClick: (EclipseAddon.Playlist) -> Unit): View {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val size = dp(148)
        for (pl in playlists) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { onClick(pl) }
            }
            Ui.selectable(tile)
            val art = Ui.cover(this, size, dp(8).toFloat())
            Artwork.load(art, pl.artworkUrl, size * 2, pl.title, Artwork.Kind.ALBUM)
            tile.addView(art, LinearLayout.LayoutParams(size, size))
            tile.addView(Ui.text(this, pl.title, 14f).apply { setPadding(0, dp(8), 0, 0) }, LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT))
            val meta = listOfNotNull("Playlist", pl.trackCount?.let { "$it songs" }).joinToString(" · ")
            tile.addView(Ui.text(this, meta, 12f, Ui.TEXT_2)
                .apply { setPadding(0, dp(2), 0, 0) }, LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT))
            strip.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(14) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    private fun renderPlaylist(a: EclipseAddon, playlist: EclipseAddon.Playlist) {
        clearPage()
        page.addView(backRow())
        val id = op
        page.addView(
            albumHeader(
                playlist.title,
                "",
                listOfNotNull("Playlist", playlist.trackCount?.let { "$it songs" }).joinToString(" · "),
                playlist.artworkUrl,
                playlist.title,
            )
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        box.addView(Ui.text(this, "Loading tracks…", 14f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
        background(id, { a.playlist(playlist.id) }) { d ->
            box.removeAllViews()
            val queue = d.tracks.map { addonTrack(it) }
            val total = d.playlist.trackCount
            val count = when {
                total != null && total > d.tracks.size -> "${d.tracks.size} of $total songs"
                else -> "${d.tracks.size} songs"
            }
            addPlayShuffle(box, queue, d.playlist.title, count)
            addDownloadRow(box, d.playlist.title, d.playlist.artworkUrl ?: playlist.artworkUrl, queue)
            d.tracks.forEachIndexed { i, t ->
                box.addView(numberedRow(i + 1, t.title, t.artist.ifBlank { d.playlist.title }.takeIf { it.isNotBlank() }, t.durationSec?.let { Ui.mmss(it * 1000L) }) {
                    play(queue, i, d.playlist.title)
                })
            }
        }
    }

    private fun albumHeader(title: String, artist: String, meta: String, artworkUrl: String?, fallbackQuery: String?): View {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val size = min(resources.displayMetrics.widthPixels - dp(96), dp(280))
        val art = Ui.cover(this, size, dp(10).toFloat())
        Artwork.load(art, artworkUrl, size, fallbackQuery, Artwork.Kind.ALBUM)
        head.addView(art, LinearLayout.LayoutParams(size, size))
        head.addView(Ui.text(this, title, 22f, bold = true, lines = 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (artist.isNotBlank()) head.addView(Ui.text(this, artist, 17f, Ui.ACCENT).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (meta.isNotBlank()) head.addView(Ui.text(this, meta, 13f, Ui.TEXT_2).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return head
    }

    private fun addPlayShuffle(into: LinearLayout, queue: List<Track>, title: String, meta: String?) {
        if (queue.isEmpty()) return
        if (meta != null) into.addView(Ui.text(this, meta, 13f, Ui.TEXT_2).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        }
        row.addView(Ui.pill(this, "▶  Play", filled = true) { play(queue, 0, title) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        row.addView(Ui.pill(this, "⤮  Shuffle", filled = false) { play(queue.shuffled(), 0, title) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        into.addView(row)
    }

    /** "Download" under an album's or playlist's Play/Shuffle, with live progress once started. */
    private fun addDownloadRow(into: LinearLayout, title: String, artworkUrl: String?, queue: List<Track>) {
        if (queue.isEmpty()) return
        val id = Downloads.idFor(title)
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        into.addView(holder)
        val draw = {
            holder.removeAllViews()
            val c = Downloads.find(this, id)
            if (c == null) {
                holder.addView(Ui.pill(this, "⤓  Download", filled = false) {
                    Downloads.add(this, title, artworkUrl, queue)
                    android.widget.Toast.makeText(this, "Downloading — keep CabinEQ open or playing", android.widget.Toast.LENGTH_LONG).show()
                })
            } else {
                holder.addView(Ui.text(this, downloadLine(Downloads.status(this, c)), 13f, Ui.TEXT_2, lines = 2)
                    .apply { gravity = Gravity.CENTER })
                // The list grew since it was downloaded (new likes, songs added to a playlist).
                val have = c.tracks.map { it.mediaId }.toHashSet()
                val fresh = queue.count { it.mediaId !in have && (it.sourceId != null || it.source == Track.Source.TORBOX) }
                if (fresh > 0) {
                    holder.addView(Ui.pill(this, "⤓  Download $fresh new", filled = false) {
                        Downloads.add(this, title, artworkUrl, queue)
                    }, wrapLp(top = 8))
                }
            }
        }
        draw()
        downloadRefresh = { draw() }
    }

    private fun downloadLine(st: Downloads.Status): String = when {
        st.complete -> "✓  Downloaded · plays offline"
        st.problem != null -> "${st.done} of ${st.total} downloaded · ${st.problem}"
        st.active -> "Downloading… ${st.done} of ${st.total}"
        else -> "${st.done} of ${st.total} downloaded"
    }

    /** One downloaded album or playlist, from the phone's own index: works with no network. */
    private fun renderDownload(id: String) {
        clearPage()
        page.addView(backRow())
        val c = Downloads.find(this, id)
        if (c == null) {
            page.addView(Ui.text(this, "This download was removed.", 15f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
            return
        }
        page.addView(albumHeader(c.title, "", "Downloaded", c.artworkUrl, c.title))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        addPlayShuffle(box, c.tracks, c.title, "${c.tracks.size} songs")
        val status = Ui.text(this, "", 13f, Ui.TEXT_2, lines = 2).apply { gravity = Gravity.CENTER }
        box.addView(status)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        box.addView(actions)
        val draw = {
            val st = Downloads.status(this, c)
            status.text = downloadLine(st)
            actions.removeAllViews()
            if (!st.complete && !st.active) {
                actions.addView(Ui.pill(this, "Retry", filled = false) { Downloads.retry(this) },
                    wrapLp(top = 0).apply { rightMargin = dp(8) })
            }
            actions.addView(Ui.pill(this, "Remove download", filled = false) {
                Downloads.remove(this, c.id)
                onBackPressedCompat()
            }, wrapLp(top = 0))
        }
        draw()
        downloadRefresh = { draw() }
        c.tracks.forEachIndexed { i, t ->
            box.addView(numberedRow(i + 1, t.title, t.artist, if (t.durationMs > 0) Ui.mmss(t.durationMs) else null) {
                play(c.tracks, i, c.title)
            })
        }
    }

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------------------------
    // Library

    /** Actions for one song: from the player's "⋯" and from a long-press on a song row. */
    private fun showTrackMenu(track: Track, nowPlaying: Boolean) {
        val artist = track.artist?.split(',')?.first()?.trim()?.takeIf { it.isNotBlank() }
        val liked = Library.isLiked(this, track)
        val blocked = artist != null && Taste.isArtistBlocked(this, artist)
        val why = Radio.reasonFor(track)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(label: String, action: () -> Unit) { labels += label; actions += action }
        item("Add to playlist…") { choosePlaylist(track) }
        if (!nowPlaying) {
            item("Play next") { player.playNext(track); toast("Plays next") }
            item("Add to queue") { player.enqueue(listOf(track)); toast("Added to the queue") }
            item(if (liked) "Remove from Liked songs" else "Like") {
                if (liked) Taste.unlike(this, track) else Taste.like(this, track)
            }
        }
        if (track.sourceId != null) item("Start radio from this song") { play(listOf(track), 0, track.title, radio = track) }
        if (artist != null) {
            item(if (blocked) "Allow $artist in recommendations" else "Never recommend $artist") {
                Taste.setArtistBlocked(this, artist, !blocked)
                toast(if (blocked) "$artist can be recommended again" else "$artist won't be recommended")
                // Blocking the artist that is playing from the radio moves on at once.
                if (!blocked && nowPlaying && why != null) transport { player.next() }
            }
        }
        if (why != null) {
            item("Why this song?") {
                android.app.AlertDialog.Builder(this).setTitle(track.title)
                    .setMessage("The radio picked it: $why.\n\n♡ pulls recommendations towards songs like it; " +
                        "“Not for me” removes it for good and makes its artist come up less.")
                    .setPositiveButton("OK", null).show()
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(listOfNotNull(track.title, artist).joinToString(" — "))
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun choosePlaylist(track: Track) {
        val lists = Library.playlists(this)
        val labels = (listOf("＋  New playlist…") + lists.map { "${it.name}  (${it.tracks.size})" }).toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("Add to playlist")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    askPlaylistName { name ->
                        Library.createPlaylist(this, name, listOf(track))?.let { toast("Added to ${it.name}") }
                    }
                } else {
                    val p = lists[which - 1]
                    toast(if (Library.addToPlaylist(this, p.id, track)) "Added to ${p.name}" else "Already in ${p.name}")
                }
            }.show()
    }

    private fun askPlaylistName(onName: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Playlist name"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val frame = android.widget.FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(this).setTitle("New playlist").setView(frame)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** A list of songs from the library: play, shuffle, download, and per-song actions. */
    private fun addLibraryTracks(
        box: LinearLayout,
        title: String,
        artworkUrl: String?,
        tracks: List<Track>,
        onRemove: ((Track) -> Unit)?,
    ) {
        addPlayShuffle(box, tracks, title, "${tracks.size} songs")
        addDownloadRow(box, title, artworkUrl ?: tracks.firstNotNullOfOrNull { it.artworkUrl }, tracks)
        tracks.forEachIndexed { i, t ->
            val local = runCatching { MediaCache.fileFor(this, t) != null }.getOrDefault(false)
            val right = listOfNotNull("⤓".takeIf { local }, if (t.durationMs > 0) Ui.mmss(t.durationMs) else null).joinToString("  ")
            val row = numberedRow(i + 1, t.title, t.artist, right.ifBlank { null }) { play(tracks, i, title) }
            row.setOnLongClickListener {
                if (onRemove == null) showTrackMenu(t, nowPlaying = false) else {
                    android.app.AlertDialog.Builder(this).setTitle(t.title)
                        .setItems(arrayOf("Remove from “$title”", "More…")) { _, which ->
                            if (which == 0) onRemove(t) else showTrackMenu(t, nowPlaying = false)
                        }.show()
                }
                true
            }
            box.addView(row)
        }
    }

    private fun renderLiked() {
        clearPage()
        page.addView(backRow())
        page.addView(Ui.text(this, "Liked songs", 24f, bold = true).apply { setPadding(0, dp(4), 0, dp(8)) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        val draw = {
            box.removeAllViews()
            val tracks = Library.liked(this)
            if (tracks.isEmpty()) {
                box.addView(Ui.text(this, "Nothing here yet. Tap ♡ in the player (or in the car) and the song is kept here.",
                    14f, Ui.TEXT_2, lines = 3).apply { setPadding(0, dp(12), 0, 0) })
            } else {
                addLibraryTracks(box, "Liked songs", null, tracks) { Taste.unlike(this, it) }
                box.addView(Ui.text(this, "Hold a song to remove it or add it to a playlist.", 12f, Ui.TEXT_2)
                    .apply { setPadding(0, dp(12), 0, 0) })
            }
        }
        draw()
        libraryRefresh = { draw() }
    }

    private fun renderPlaylists() {
        clearPage()
        page.addView(backRow())
        page.addView(Ui.text(this, "Playlists", 24f, bold = true).apply { setPadding(0, dp(4), 0, dp(8)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        actions.addView(Ui.pill(this, "＋  New playlist", filled = true) {
            askPlaylistName { name -> Library.createPlaylist(this, name)?.let { p -> navigate { renderUserPlaylist(p.id) } } }
        }, wrapLp().apply { rightMargin = dp(8) })
        actions.addView(Ui.pill(this, "Import…", filled = false) { navigate { renderImport() } }, wrapLp())
        page.addView(actions)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        val draw = {
            box.removeAllViews()
            val lists = Library.playlists(this)
            if (lists.isEmpty()) {
                box.addView(Ui.text(this, "No playlists yet. Hold any song (or use ⋯ in the player) to add it to one.",
                    14f, Ui.TEXT_2, lines = 3).apply { setPadding(0, dp(12), 0, 0) })
            }
            for (p in lists) {
                box.addView(simpleRow(p.name, "${p.tracks.size} songs", android.R.drawable.ic_menu_sort_by_size) {
                    navigate { renderUserPlaylist(p.id) }
                })
            }
        }
        draw()
        libraryRefresh = { draw() }
    }

    private fun renderUserPlaylist(id: String) {
        clearPage()
        page.addView(backRow())
        val first = Library.playlist(this, id)
        if (first == null) {
            page.addView(Ui.text(this, "This playlist was deleted.", 15f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
            return
        }
        page.addView(Ui.text(this, first.name, 24f, bold = true, lines = 2).apply { setPadding(0, dp(4), 0, dp(8)) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        val draw = {
            box.removeAllViews()
            val p = Library.playlist(this, id)
            if (p != null) {
                if (p.tracks.isEmpty()) {
                    box.addView(Ui.text(this, "Empty. Hold any song (or use ⋯ in the player) and choose “Add to playlist”.",
                        14f, Ui.TEXT_2, lines = 3).apply { setPadding(0, dp(12), 0, dp(12)) })
                } else {
                    addLibraryTracks(box, p.name, null, p.tracks) { Library.removeFromPlaylist(this, id, it) }
                }
                box.addView(Ui.pill(this, "Delete playlist", filled = false) {
                    android.app.AlertDialog.Builder(this).setTitle("Delete “${p.name}”?")
                        .setPositiveButton("Delete") { _, _ ->
                            Library.deletePlaylist(this, id)
                            onBackPressedCompat()
                        }
                        .setNegativeButton("Cancel", null).show()
                }, wrapLp(top = 16))
            }
        }
        draw()
        libraryRefresh = { draw() }
    }

    private fun renderSavedAlbums() {
        clearPage()
        page.addView(backRow())
        page.addView(Ui.text(this, "Albums", 24f, bold = true).apply { setPadding(0, dp(4), 0, dp(8)) })
        val albums = Library.albums(this)
        if (albums.isEmpty()) {
            page.addView(Ui.text(this, "No saved albums. Open an album and tap “Save to library”.", 14f, Ui.TEXT_2, lines = 2))
            return
        }
        for (al in albums) {
            page.addView(simpleRow(al.title, listOfNotNull(al.artist.takeIf { it.isNotBlank() }, al.year?.take(4)).joinToString(" · "),
                android.R.drawable.ic_menu_gallery) {
                val a = addon()
                if (a == null) toast("Connect Nebula in Settings to open albums") else navigate { renderAlbum(a, al.toAddon()) }
            })
        }
    }

    private fun simpleRow(title: String, sub: String?, icon: Int, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
        }
        Ui.selectable(row)
        val tile = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT_2)
            background = Ui.rounded(Ui.SURFACE, dp(6).toFloat())
            scaleType = ImageView.ScaleType.CENTER
        }
        row.addView(tile, LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(Ui.text(this, title, 16f, lines = 2))
        if (!sub.isNullOrBlank()) texts.addView(Ui.text(this, sub, 13f, Ui.TEXT_2).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun card(title: String, body: String, action: String, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, dp(14).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        c.addView(Ui.text(this, title, 17f, bold = true))
        c.addView(Ui.text(this, body, 13f, Ui.TEXT_2, lines = 4).apply { setPadding(0, dp(6), 0, dp(12)) })
        c.addView(Ui.pill(this, action, filled = true, onClick = onClick), wrapLp())
        return c.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
        }
    }

    private fun backRow(): View = Ui.text(this, "‹  Back", 15f, Ui.ACCENT, bold = true).apply {
        setPadding(0, dp(8), dp(16), dp(8))
        setOnClickListener { onBackPressedCompat() }
    }

    private fun field(hint: String, value: String, secret: Boolean) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Ui.TEXT_2)
        setTextColor(Ui.TEXT)
        textSize = 15f
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        background = Ui.rounded(Ui.SURFACE, dp(10).toFloat())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setText(value)
    }

    private fun wrapLp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    // ---------------------------------------------------------------------------------
    // Player UI

    private fun refreshSoon() = handler.postDelayed({ refreshPlayer() }, 120)

    private fun repeatLabel(): String = when (player.repeat) {
        FilePlayer.Repeat.OFF -> "↻  Repeat: off"
        FilePlayer.Repeat.ALL -> "↻  Repeat: all"
        FilePlayer.Repeat.ONE -> "↻  Repeat: one"
    }

    /**
     * Transport buttons: act on the player and, if that started playback, make sure [EqService]
     * is attached. Otherwise resuming from the mini player (e.g. the queue restored at launch)
     * would play without the live EQ, the notification and the media session the car needs.
     */
    private fun transport(action: () -> Unit) {
        action()
        // Not gated on isActive: right after a resume the worker hasn't set the state yet, and
        // the service owns the notification, the session and the live EQ anyway.
        if (player.queue.isNotEmpty()) {
            startForegroundService(Intent(this, EqService::class.java).setAction(EqService.ACTION_PLAYER_ATTACH))
        }
        refreshSoon()
    }

    private fun refreshPlayer() {
        // get(), not peek(): opening the Music screen is what brings back the saved queue.
        val p = FilePlayer.get(this)
        val has = p.queue.isNotEmpty()
        (mini.parent as View).visibility = if (has) View.VISIBLE else View.GONE
        if (!has) {
            if (full.visibility == View.VISIBLE) showFull(false)
            return
        }
        val t = p.currentTrack
        val info = p.currentInfo   // tags/cover embedded in the file, when the source has none
        val playing = p.state == FilePlayer.State.PLAYING || p.state == FilePlayer.State.LOADING
        if (p.version != shownVersion) {
            shownVersion = p.version
            val title = (if (t?.artist == null) info?.title else null) ?: t?.title ?: "—"
            val artist = t?.artist ?: info?.artist ?: t?.album ?: p.queueTitle ?: ""
            miniTitle.text = title
            miniSub.text = when (p.state) {
                FilePlayer.State.LOADING -> p.note ?: "Loading…"
                FilePlayer.State.ERROR -> "Couldn't play"
                else -> artist
            }
            fullTitle.text = title
            fullArtist.text = artist
            fullContext.text = p.queueTitle?.let { "Playing from $it" } ?: "Now playing"
            val icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            miniToggle.setImageResource(icon)
            fullToggle.setImageResource(icon)
            val artQuery = trackQuery(t, info)
            val embedded = info?.art
            val artKey = "${t?.artworkUrl}|$artQuery|${embedded?.let { System.identityHashCode(it) }}|${info != null}"
            if (artKey != shownArt) {
                shownArt = artKey
                when {
                    // 1. Cover embedded in the file: exact for this song, even on compilations.
                    embedded != null -> for (v in listOf(miniArt, fullArt)) { v.tag = null; v.setImageBitmap(embedded) }
                    // 2. Cover from the source (Nebula), else an iTunes match for this song.
                    //    For files, wait until their tags are read so the search uses them.
                    t?.artworkUrl != null || info != null -> {
                        Artwork.load(miniArt, t?.artworkUrl, dp(44) * 2, artQuery, Artwork.Kind.SONG)
                        Artwork.load(fullArt, t?.artworkUrl, min(resources.displayMetrics.widthPixels, 1000), artQuery, Artwork.Kind.SONG)
                    }
                    else -> for (v in listOf(miniArt, fullArt)) { v.tag = null; v.setImageDrawable(null) }
                }
            }
        }
        val dur = p.durationMs
        miniProgress.progress = if (dur > 0) ((p.positionMs * 1000) / dur).toInt() else 0
        if (full.visibility == View.VISIBLE) {
            fullSeek.isEnabled = dur > 0
            if (!seeking && dur > 0) {
                fullSeek.max = (dur / 1000).toInt().coerceAtLeast(1)
                fullSeek.progress = (p.positionMs / 1000).toInt()
                fullPos.text = Ui.mmss(p.positionMs)
            }
            fullDur.text = if (dur > 0) Ui.mmss(dur) else "--:--"
            val info = StringBuilder()
            p.formatInfo?.let { info.append(it).append(" → CabinEQ DSP") }
            EngineState.playerRoute?.let { if (p.state == FilePlayer.State.PLAYING) info.append(" → ").append(it) }
            p.loudnessGainDb.takeIf { !it.isNaN() && p.isActive }?.let {
                info.append(String.format(Locale.US, "\nLoudness match %+.1f dB", it))
            }
            if (p.state == FilePlayer.State.PLAYING && EngineState.running) {
                info.append(String.format(Locale.US, "\nLevel in %.1f → out %.1f dBFS · limiter %.1f dB",
                    EngineState.playerInPeakDb, EngineState.playerOutPeakDb, EngineState.playerLimiterDb))
            }
            if (::fullRepeat.isInitialized) fullRepeat.text = repeatLabel()
            val now = p.currentTrack
            if (::fullLike.isInitialized) {
                fullLike.text = if (now != null && Library.isLiked(this, now)) "♥  Liked" else "♡  Like"
            }
            if (::fullWhy.isInitialized) {
                val why = now?.let { Radio.reasonFor(it) }
                fullWhy.text = why?.let { "Picked for you: $it" } ?: ""
                fullWhy.visibility = if (why == null) View.GONE else View.VISIBLE
            }
            fullInfo.text = info
            val note = p.note?.let { "⏳ $it" } ?: (EngineState.playerMessage ?: p.lastError)?.let { "⚠ $it" }
            fullNote.text = note ?: ""
            fullNote.setTextColor(if (p.note != null) Ui.TEXT_2 else Ui.ERROR)
            fullNote.visibility = if (note == null) View.GONE else View.VISIBLE
        }
        if (lyricsOpen) updateLyricsHighlight()
    }

    private fun showFull(show: Boolean) {
        if (show) hideKeyboard()
        full.visibility = if (show) View.VISIBLE else View.GONE
        shownVersion = -1
        refreshPlayer()
    }

    // ---------------------------------------------------------------------------------
    // Lyrics

    /**
     * Lyrics for what is playing, following the song line by line. Fetched in the background:
     * a cache hit paints immediately-on-arrival, a miss shows "looking…" for about a second.
     * Plain (untimed) lyrics are shown without highlight — better than nothing, never a lie.
     */
    private fun renderLyrics() {
        clearPage()
        lyricsOpen = true
        handler.removeCallbacks(lyricTicker)
        handler.post(lyricTicker)
        page.addView(backRow())
        page.addView(Ui.text(this, "Lyrics", 24f, bold = true).apply { setPadding(0, dp(4), 0, dp(4)) })
        val p = player
        val t = p.currentTrack
        if (t == null) {
            page.addView(Ui.text(this, "Nothing playing.", 15f, Ui.TEXT_2).apply { setPadding(0, dp(12), 0, 0) })
            return
        }
        val label = listOfNotNull(t.title, t.artist ?: p.currentInfo?.artist).joinToString(" — ")
        page.addView(Ui.text(this, label, 15f, Ui.TEXT_2, lines = 2).apply { setPadding(0, 0, 0, dp(8)) })

        // Timing nudge, for delay the app can't measure (Bluetooth, the car's screen).
        val timing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val timingLabel = Ui.text(this, "", 12f, Ui.TEXT_2)
        fun describe() {
            val ms = settings.lyricsOffsetMs
            timingLabel.text = when {
                ms == 0 -> "Lyrics timing: as is"
                ms > 0 -> String.format(Locale.US, "Lyrics %.2f s earlier", ms / 1000f)
                else -> String.format(Locale.US, "Lyrics %.2f s later", -ms / 1000f)
            }
        }
        describe()
        timing.addView(Ui.pill(this, "Later", filled = false) {
            settings.lyricsOffsetMs -= 250; describe(); updateLyricsHighlight(force = true)
        }, wrapLp().apply { rightMargin = dp(8) })
        timing.addView(Ui.pill(this, "Earlier", filled = false) {
            settings.lyricsOffsetMs += 250; describe(); updateLyricsHighlight(force = true)
        }, wrapLp().apply { rightMargin = dp(12) })
        timing.addView(timingLabel)
        page.addView(timing)

        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(Ui.text(this, "Looking for lyrics…", 15f, Ui.TEXT_2))
        page.addView(holder)
        lyricsBlock = holder
        lyricViews = ArrayList()
        lyricSheet = null
        lyricIndex = -1
        lyricScrolled = Int.MIN_VALUE

        val token = ++lyricToken
        lyricsExecutor.execute {
            val sheet = Lyrics.forPlayer(this, p)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // The song or the page may have changed while the lookup ran.
                if (token != lyricToken || !lyricsOpen || lyricsBlock !== holder) return@runOnUiThread
                showLyrics(holder, sheet)
            }
        }
    }

    private fun showLyrics(holder: LinearLayout, sheet: Lyrics.Sheet?) {
        holder.removeAllViews()
        lyricViews = ArrayList()
        lyricSheet = sheet
        lyricIndex = -1
        lyricScrolled = Int.MIN_VALUE
        if (sheet == null) {
            holder.addView(Ui.text(this, "Couldn't reach the lyrics service.", 15f, Ui.TEXT_2))
            return
        }
        if (sheet.empty) {
            holder.addView(Ui.text(this, "No lyrics found for this song.", 15f, Ui.TEXT_2))
            return
        }
        if (!sheet.synced) {
            holder.addView(Ui.text(this, "Plain lyrics — no timings for this one", 12f, Ui.TEXT_2)
                .apply { setPadding(0, 0, 0, dp(10)) })
        }
        for (line in sheet.lines) {
            if (line.text.isBlank()) continue
            val v = Ui.text(this, line.text, 16f, if (sheet.synced) Ui.TEXT_2 else Ui.TEXT, lines = 4)
            v.setPadding(0, dp(7), 0, dp(7))
            holder.addView(v)
            lyricViews.add(v)
        }
        updateLyricsHighlight(force = true)
    }

    /** Moves the highlight (and the scroll) to the line being sung; cheap enough for 2 Hz. */
    private fun updateLyricsHighlight(force: Boolean = false) {
        val sheet = lyricSheet ?: return
        if (!sheet.synced || lyricViews.isEmpty()) return
        val idx = sheet.indexAt(player.audiblePositionMs + Lyrics.LEAD_MS + settings.lyricsOffsetMs)
        if (idx != lyricIndex || force) {
            lyricIndex = idx
            lyricViews.forEachIndexed { i, v ->
                val current = i == idx
                v.setTextColor(if (current) Ui.TEXT else Ui.TEXT_2)
                v.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        if (idx < 0) return
        // Someone reading further ahead keeps their place for a few seconds.
        if (System.currentTimeMillis() - lyricTouchedAt < 6_000) return
        val target = lyricViews.getOrNull(idx) ?: return
        val block = lyricsBlock ?: return
        // top > 0 means the list has been laid out; before that this would scroll to nowhere.
        if (target.top > 0 && idx != lyricScrolled) {
            lyricScrolled = idx
            // Keep the current line a third of the way down so the next ones are visible.
            scroll.smoothScrollTo(0, (block.top + target.top - scroll.height / 3).coerceAtLeast(0))
        }
    }

    // ---------------------------------------------------------------------------------
    // Playlist import

    /**
     * Import a playlist exported from another service. The services themselves do not expose
     * their playlists to other apps, so this takes what their export tools produce: CSV from
     * Soundiiz/TuneMyMusic/Exportify, JSON from Spotify's data export, or a plain "Artist - Title"
     * list. Matching happens against the catalogue through the addon, one search per artist.
     */
    private fun renderImport() {
        clearPage()
        page.addView(backRow())
        page.addView(Ui.text(this, "Import playlists", 24f, bold = true).apply { setPadding(0, dp(4), 0, dp(6)) })
        page.addView(Ui.text(this,
            "YouTube Music has no public API for other apps, so use a transfer service: in " +
                "Soundiiz or TuneMyMusic connect YouTube Music, then export a playlist to CSV or JSON " +
                "and share the file here. Spotify users can use Exportify, which includes ISRCs and " +
                "therefore matches almost exactly. You can also paste a list of \"Artist - Title\" lines.",
            12f, Ui.TEXT_2, lines = 6).apply { setPadding(0, 0, 0, dp(12)) })

        importText = EditText(this).apply {
            hint = "Paste a track list, or pick a file below"
            setHintTextColor(Ui.TEXT_2)
            setTextColor(Ui.TEXT)
            textSize = 14f
            minLines = 4
            maxLines = 10
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            background = Ui.rounded(Ui.SURFACE, dp(12).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        page.addView(importText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        actions.addView(Ui.pill(this, "Pick a file", filled = false) { pickImportFile() }, wrapLp().apply { rightMargin = dp(10) })
        actions.addView(Ui.pill(this, "Import", filled = true) { runImport(importText.text.toString()) }, wrapLp())
        page.addView(actions)

        importStatus = Ui.text(this, lastImportStatus, 13f, Ui.TEXT_2, lines = 6)
        page.addView(importStatus)

        val known = settings.importedPlaylists()
        if (known.isNotEmpty()) {
            page.addView(Ui.text(this, "Imported: " + known.joinToString(", "), 12f, Ui.TEXT_2, lines = 4)
                .apply { setPadding(0, dp(10), 0, dp(6)) })
            page.addView(Ui.pill(this, "Forget imported playlists", filled = false) {
                val removed = Taste.forgetImports(this)
                showMessage("Removed $removed imported tracks")
                renderImport()
            }, wrapLp())
        }
    }

    private fun pickImportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "application/csv", "*/*"))
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT_FILE) }
            .onFailure { showMessage("No file picker available", isError = true) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = runCatching { uri.lastPathSegment ?: "Imported playlist" }.getOrDefault("Imported playlist")
        importExecutor.execute {
            val text = readImportFile(uri)
            runOnUiThread {
                if (text.isNullOrBlank()) {
                    importStatus.text = "Couldn't read that file."
                    return@runOnUiThread
                }
                runImport(text, name)
            }
        }
    }

    /** Shared text/links, shared playlist files and cabineq://import land here. */
    private fun handleImportIntent(intent: Intent?): Boolean {
        intent ?: return false
        val action = intent.action ?: return false
        val text = when (action) {
            ACTION_IMPORT -> intent.getStringExtra(EXTRA_IMPORT_TEXT)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.takeIf { it.isNotBlank() }
        if (text == null) {
            // A shared file (ImportActivity forwards its URI with a read grant): read it off the
            // main thread, then import as if it had been pasted.
            val uri = if (action == ACTION_IMPORT || action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            } else null
            uri ?: return false
            navigate { renderImport() }
            importStatus.text = "Reading the shared file…"
            importExecutor.execute {
                val read = readImportFile(uri)
                runOnUiThread {
                    if (read.isNullOrBlank()) {
                        importStatus.text = "Couldn't read that file."
                        return@runOnUiThread
                    }
                    importText.setText(read)
                    runImport(read, "Shared playlist")
                }
            }
            return true
        }
        navigate { renderImport() }
        importText.setText(text)
        // Sharing or opening an import link is an explicit "do it", unlike pasting.
        runImport(text, "Shared playlist")
        return true
    }

    /**
     * Reads a playlist file as text, capped so a stray multi-gigabyte share can't run the app out
     * of memory. A playlist is text; anything past the cap is not one.
     */
    private fun readImportFile(uri: android.net.Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_IMPORT_BYTES) return@runCatching null
            }
            out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

    private fun runImport(text: String, name: String = "Pasted list") {
        val raw = text.trim()
        if (raw.isBlank()) {
            importStatus.text = "Nothing to import."
            return
        }
        val link = settings.addonLink
        if (link.isBlank()) {
            importStatus.text = "Connect the search source first (Settings → Search source)."
            return
        }
        val token = ++importToken
        importStatus.text = "Parsing…"
        importExecutor.execute {
            val parsed = Playlists.parseFile(name, raw)
            val rows = parsed.rows
            if (rows.isEmpty()) {
                runOnUiThread {
                    if (token == importToken) importStatus.text = if (parsed.skipped > 0)
                        "No songs found: ${parsed.skipped} entries were channel subscriptions, which aren't music."
                    else "No tracks found in that text."
                }
                return@execute
            }
            val label = Playlists.sourceName(name)
            val result = runCatching {
                Playlists.match(EclipseAddon(link), rows, label, skipped = parsed.skipped, onProgress = { done, total ->
                    if (done % 5 == 0 || done == total) {
                        runOnUiThread {
                            if (token == importToken) importStatus.text = "Matching $done/$total…"
                        }
                    }
                })
            }.getOrElse { e ->
                runOnUiThread { if (token == importToken) importStatus.text = "Lookup failed: ${e.message}" }
                return@execute
            }
            val added = Taste.importPlaylist(this, result.matched, label)
            // The import is also a playlist you can open and play, not only a taste hint. A file
            // with several playlists in it becomes several playlists, named as they were.
            val groups = result.byPlaylist.ifEmpty { mapOf(label to result.matched) }
            for ((playlistName, tracks) in groups) {
                if (tracks.isNotEmpty()) runCatching { Library.createPlaylist(this, playlistName, tracks) }
            }
            runOnUiThread {
                if (token != importToken) return@runOnUiThread
                lastImportStatus = buildString {
                    append("${result.rows.size} rows")
                    append(" · ${result.matchedRows} matched")
                    if (result.unmatched > 0) append(" · ${result.unmatched} not in the catalogue")
                    if (result.skipped > 0) append("\n${result.skipped} channel subscriptions skipped (not music).")
                    if (result.withIsrc > 0) append(" · ${result.withIsrc} with ISRC")
                    append("\n$added new tracks added to your taste profile.")
                    if (result.matched.isNotEmpty()) {
                        val names = groups.filterValues { it.isNotEmpty() }.keys
                        append(if (names.size > 1) "\nSaved ${names.size} playlists in your library: ${names.joinToString(", ") { "“$it”" }}."
                        else "\nSaved as the playlist “${names.firstOrNull() ?: label}” in your library.")
                    }
                }
                importStatus.text = lastImportStatus
                showMessage("Imported $label")
                renderImport()
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Sources

    private fun addon(): EclipseAddon? {
        val link = settings.addonLink
        if (link.isBlank()) {
            showMessage("Connect Nebula first: Settings → Search source.")
            return null
        }
        return runCatching { EclipseAddon(link) }.getOrElse {
            showMessage("The saved addon link isn't valid: ${it.message}")
            null
        }
    }

    private fun torbox(): TorBoxClient? {
        val key = settings.torboxApiKey
        if (key.isBlank()) {
            showMessage("Add your TorBox API key in Settings for the library and magnet links.")
            return null
        }
        return TorBoxClient(key)
    }

    private fun saveAddon(link: String, status: TextView) {
        if (link.isBlank()) {
            settings.addonLink = ""
            settings.addonName = ""
            status.text = "Disconnected."
            return
        }
        val candidate = runCatching { EclipseAddon(link) }.getOrElse {
            status.text = "That link isn't valid: ${it.message}"
            return
        }
        status.text = "Checking…"
        background(op, { candidate.manifest() }) { m ->
            settings.addonLink = link
            settings.addonName = m.name
            val missing = listOfNotNull(if (!m.canSearch) "search" else null, if (!m.canStream) "stream" else null)
            status.text = "Connected to ${m.name} ${m.version}." +
                if (missing.isEmpty()) "" else " ⚠ It doesn't offer: ${missing.joinToString()}."
        }
    }

    /** A player track that asks the addon for its stream right before it plays. */
    private fun addonTrack(t: EclipseAddon.Track): Track = t.toTrack()

    /** Older call sites pass the addon too; the conversion no longer needs it. */
    private fun addonTrack(@Suppress("UNUSED_PARAMETER") a: EclipseAddon, t: EclipseAddon.Track): Track =
        t.toTrack()

    private fun addMagnet(magnet: String) {
        val c = torbox() ?: return
        navigate {
            clearPage()
            page.addView(backRow())
            page.addView(Ui.text(this, "Adding to TorBox…", 15f, Ui.TEXT_2))
        }
        background(op, {
            val id = try {
                c.createTorrentFromMagnet(magnet, allowZip = false).torrentId
            } catch (e: TorBoxException) {
                if (e.isAuthError || e.isRateLimited) throw e
                TorBoxClient.hashFromMagnet(magnet)?.let { c.findByHash(it)?.id } ?: throw e
            }
            c.getTorrent(id) ?: throw TorBoxException("TorBox didn't return the torrent", 200)
        }) { t ->
            stack.removeLast()
            navigate { renderTorrent(c, t) }
        }
    }

    // ---------------------------------------------------------------------------------
    // Playback

    /**
     * [FilePlayer.play] joins the previous worker (up to 2 s while it is still resolving a URL),
     * so it must never run on the main thread — a tap on the next song would freeze the UI.
     */
    private fun play(tracks: List<Track>, index: Int, title: String, radio: Track? = null) {
        EngineState.playerMessage = null
        if (!askedForNotifications && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
        // Keeps playback alive in the background and feeds the live EQ + speed compensation.
        runCatching {
            startForegroundService(Intent(this, EqService::class.java).setAction(EqService.ACTION_PLAYER_ATTACH))
        }.onFailure { showMessage("Couldn't start playback service: ${it.javaClass.simpleName}", isError = true) }
        playExecutor.execute { player.play(tracks, index, title, radio = radio) }
        shownVersion = -1
        refreshSoon()
    }

    private val lyricsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Lyrics").apply { isDaemon = true }
    }

    // Importing a playlist is minutes of network work; it must never queue behind playback.
    private val importExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Import").apply { isDaemon = true }
    }
    private var importToken = 0
    private var lastImportStatus = ""
    private lateinit var importStatus: TextView
    private lateinit var importText: EditText

    private val playExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Play").apply { isDaemon = true }
    }


    private fun renderSearchLoading(query: String) {
        clearPage()
        // Instant, offline: what you've played that matches, while the real search runs.
        val local = dev.alamo3.cabineq.music.SmartSearch.suggest(query, settings.playHistory())
        if (local.isNotEmpty()) {
            page.addView(sectionTitle("From your history"))
            for (t in local) {
                page.addView(simpleRow(t.title, t.artist, android.R.drawable.ic_menu_recent_history) {
                    play(listOf(t), 0, "Search: $query", radio = t)
                })
            }
        }
        page.addView(Ui.text(this, "Searching “$query”…", 14f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
    }

    private fun runSearch(query: String, fromTyping: Boolean) {
        if (query.isEmpty()) return
        if (query.startsWith("magnet:?")) {
            if (!fromTyping) addMagnet(query)
            return
        }
        val a = addon() ?: return
        if (tab != Tab.SEARCH) { tab = Tab.SEARCH; markTab() }
        if (!fromTyping) settings.addRecentSearch(query)
        // Typing refines the search page in place instead of stacking a page per keystroke.
        if (searchPageOnTop) stack.removeLast()
        val id = ++op
        stack.addLast { renderSearchLoading(query) }
        searchPageOnTop = true
        renderSearchLoading(query)
        background(id, { dev.alamo3.cabineq.music.SmartSearch.search(applicationContext, a, query, typing = fromTyping) }) { r ->
            val render = { renderSearch(a, query, r) }
            stack.removeLast()
            stack.addLast(render)
            searchPageOnTop = true
            render()
        }
    }

    private fun renderSearch(a: EclipseAddon, query: String, found: dev.alamo3.cabineq.music.SmartSearch.Result) {
        clearPage()
        val r = found.results
        if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty()) {
            page.addView(Ui.text(this, "No results for “$query”.", 15f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
            return
        }
        found.showingFor?.let {
            page.addView(Ui.text(this, "Showing results for $it", 13f, Ui.TEXT_2, lines = 2).apply { setPadding(0, dp(8), 0, 0) })
        }
        val lead = found.lead
        fun songs(title: String) {
            if (r.tracks.isEmpty()) return
            val songs = r.tracks.take(MAX_TRACKS)
            page.addView(sectionTitle(title, if (songs.size > 5) "See all" else null) {
                navigate { renderSongList("Songs · $query", a, songs, "Search: $query") }
            })
            songs.take(5).forEachIndexed { i, t ->
                page.addView(songRow(t, showArt = true) {
                    settings.addRecentSearch(query)
                    playSearchResult(a, songs, i, "Search: $query")
                })
            }
        }
        fun albums() {
            if (r.albums.isEmpty()) return
            page.addView(sectionTitle("Albums"))
            page.addView(albumCarousel(r.albums.take(MAX_ALBUMS)) { al ->
                settings.addRecentSearch(query)
                navigate { renderAlbum(a, al) }
            })
        }
        fun artists(list: List<EclipseAddon.Artist>, title: String?) {
            if (list.isEmpty()) return
            title?.let { page.addView(sectionTitle(it)) }
            for (ar in list.take(MAX_ARTISTS)) {
                page.addView(artistRow(ar) {
                    settings.addRecentSearch(query)
                    navigate { renderArtist(a, ar) }
                })
            }
        }
        // What the query asked for goes first: an artist's name opens with the artist, an
        // album's title with the album, anything else with songs.
        when (lead) {
            dev.alamo3.cabineq.music.SearchRank.Lead.ARTIST -> {
                artists(r.artists.take(1), "Artist")
                songs("Top songs")
                albums()
            }
            dev.alamo3.cabineq.music.SearchRank.Lead.ALBUM -> {
                albums()
                songs("Songs")
            }
            else -> {
                songs(if (found.fromLyrics) "Songs · matched by lyrics" else "Songs")
                albums()
            }
        }
        if (r.playlists.isNotEmpty()) {
            page.addView(sectionTitle("Playlists"))
            page.addView(playlistCarousel(r.playlists.take(MAX_ALBUMS)) { pl ->
                settings.addRecentSearch(query)
                navigate { renderPlaylist(a, pl) }
            })
        }
        if (lead == dev.alamo3.cabineq.music.SearchRank.Lead.ARTIST) artists(r.artists.drop(1), "Other artists")
        else artists(r.artists, "Artists")
    }

    private fun renderSongList(title: String, a: EclipseAddon, songs: List<EclipseAddon.Track>, queueTitle: String) {
        clearPage()
        page.addView(backRow())
        page.addView(Ui.text(this, title, 24f, bold = true, lines = 2).apply { setPadding(0, dp(4), 0, dp(12)) })
        songs.forEachIndexed { i, t ->
            page.addView(songRow(t, showArt = true) { playSearchResult(a, songs, i, queueTitle) })
        }
    }

    /**
     * A search result is not a queue: playing the whole result list queued a dozen unrelated
     * songs that merely shared the words. Play the one song that was asked for; the player asks
     * for one more from that artist only when the queue drains, so it never grows by 20.
     */
    private fun playSearchResult(a: EclipseAddon, songs: List<EclipseAddon.Track>, index: Int, title: String) {
        val tapped = addonTrack(a, songs[index])
        play(listOf(tapped), 0, title, radio = tapped)
    }

    private fun renderAlbum(a: EclipseAddon, album: EclipseAddon.Album) {
        clearPage()
        page.addView(backRow())
        val id = op
        // Header right away from what the search gave us; tracks fill in when they arrive.
        val header = albumHeader(album.title, album.artist, listOfNotNull("Album", album.year).joinToString(" · "), album.artworkUrl,
            query(album.artist, album.title))
        page.addView(header)
        val tracksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(tracksBox)
        tracksBox.addView(Ui.text(this, "Loading tracks…", 14f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
        background(id, { a.album(album.id) }) { d ->
            tracksBox.removeAllViews()
            val al = d.album
            val queueTitle = listOf(al.artist, al.title).filter { it.isNotBlank() }.joinToString(" — ")
            val queue = d.tracks.map { addonTrack(a, it) }
            addPlayShuffle(tracksBox, queue, queueTitle, "${d.tracks.size} songs" + (al.year?.let { " · $it" } ?: ""))
            addDownloadRow(tracksBox, queueTitle, al.artworkUrl ?: album.artworkUrl, queue)
            lateinit var savePill: TextView
            savePill = Ui.pill(this, if (Library.isAlbumSaved(this, album.id)) "✓  In your library" else "＋  Save to library", filled = false) {
                val saved = !Library.isAlbumSaved(this, album.id)
                Library.setAlbumSaved(this, if (al.artworkUrl != null) al else al.copy(artworkUrl = album.artworkUrl), saved)
                savePill.text = if (saved) "✓  In your library" else "＋  Save to library"
            }
            tracksBox.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
                addView(savePill)
            })
            d.tracks.forEachIndexed { i, t ->
                tracksBox.addView(numberedRow(i + 1, t.title, t.artist.takeIf { it != al.artist }, t.durationSec?.let { Ui.mmss(it * 1000L) }) {
                    play(queue, i, queueTitle)
                })
            }
        }
    }

    private fun renderArtist(a: EclipseAddon, artist: EclipseAddon.Artist) {
        clearPage()
        page.addView(backRow())
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val art = Ui.cover(this, dp(160), 0f, circle = true)
        Artwork.load(art, artist.artworkUrl, dp(160) * 2, artist.name, Artwork.Kind.ARTIST)
        head.addView(art, LinearLayout.LayoutParams(dp(160), dp(160)))
        head.addView(Ui.text(this, artist.name, 26f, bold = true, lines = 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        if (artist.genres.isNotEmpty()) head.addView(Ui.text(this, artist.genres.take(3).joinToString(" · "), 13f, Ui.TEXT_2).apply { gravity = Gravity.CENTER })
        page.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(body)
        body.addView(Ui.text(this, "Loading…", 14f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })

        background(op, {
            // Use /artist/{id} when the addon has it; otherwise search by name.
            a.artist(artist.id) ?: a.search(artist.name).let { r ->
                val name = artist.name.lowercase(Locale.ROOT)
                EclipseAddon.ArtistDetail(
                    artist,
                    r.tracks.filter { name in it.artist.lowercase(Locale.ROOT) },
                    r.albums.filter { name in it.artist.lowercase(Locale.ROOT) },
                )
            }
        }) { d ->
            body.removeAllViews()
            val top = d.topTracks.take(MAX_TRACKS)
            if (top.isNotEmpty()) {
                addPlayShuffle(body, top.map { addonTrack(a, it) }, artist.name, null)
                body.addView(sectionTitle("Top songs", if (top.size > 5) "See all" else null) {
                    navigate { renderSongList("${artist.name} · Top songs", a, top, artist.name) }
                })
                top.take(5).forEachIndexed { i, t ->
                    body.addView(songRow(t, showArt = true) { play(top.map { addonTrack(a, it) }, i, artist.name) })
                }
            }
            if (d.albums.isNotEmpty()) {
                body.addView(sectionTitle("Albums"))
                body.addView(albumCarousel(d.albums) { al -> navigate { renderAlbum(a, al) } })
            }
            if (top.isEmpty() && d.albums.isEmpty()) {
                body.addView(Ui.text(this, "Nothing found for ${artist.name}.", 14f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
            }
        }
    }

    private fun renderTorrent(c: TorBoxClient, initial: TorBoxTorrent) {
        clearPage()
        page.addView(backRow())
        val title = cleanTorrentName(initial.name)
        page.addView(albumHeader(title, "", "From your TorBox", null, coverQuery(initial.name)))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        val id = op

        fun show(t: TorBoxTorrent) {
            box.removeAllViews()
            if (!t.isReady) {
                val speed = if (t.downloadSpeed > 0) " · ${humanBytes(t.downloadSpeed)}/s" else ""
                box.addView(Ui.text(this, "Downloading on TorBox: ${(t.progress * 100).roundToInt()}%$speed\nIt will be playable when it's finished.", 14f, Ui.TEXT_2, lines = 3))
                handler.postDelayed({
                    if (id == op) background(id, { c.getTorrent(t.id) }) { fresh -> if (fresh != null) show(fresh) }
                }, POLL_MS)
                return
            }
            val pick = AudioPicker.pick(t.files, settings.torboxFormat).takeIf { it.tracks.isNotEmpty() }
                ?: AudioPicker.pick(t.files)
            if (pick.tracks.isEmpty()) {
                box.addView(Ui.text(this,
                    if (AudioPicker.isVideoTorrent(t.files)) "This is a video torrent — CabinEQ only plays audio."
                    else "No playable audio" + if (pick.unsupportedCount > 0) " (${pick.unsupportedCount} files in formats Android can't decode)." else ".",
                    14f, Ui.TEXT_2, lines = 3))
                return
            }
            val queue = pick.tracks.map { f ->
                Track(
                    title = AudioPicker.trackTitle(f),
                    album = title,
                    source = Track.Source.TORBOX,
                    torrentId = t.id,
                    fileId = f.id,
                )
            }
            addPlayShuffle(box, queue, title, "${pick.tracks.size} songs · ${pick.format?.uppercase(Locale.ROOT)}")
            pick.tracks.forEachIndexed { i, f ->
                box.addView(numberedRow(i + 1, AudioPicker.trackTitle(f), null, humanBytes(f.size)) { play(queue, i, title) })
            }
        }
        show(initial)
    }

    private fun renderSettings() {
        clearPage()
        page.addView(backRow())

        page.addView(sectionTitle("Import playlists"))
        page.addView(Ui.text(this,
            "Bring your taste over from another service: export a playlist (Soundiiz, TuneMyMusic or " +
                "Exportify all produce CSV or JSON) and import it here. Matched tracks steer the " +
                "recommendations at a deliberately low weight.",
            12f, Ui.TEXT_2, lines = 4).apply { setPadding(0, 0, 0, dp(6)) })
        val imported = settings.importedPlaylists()
        page.addView(Ui.pill(this, if (imported.isEmpty()) "Import a playlist" else "Import a playlist (${imported.size} imported)", filled = true) {
            navigate { renderImport() }
        }.apply { setPadding(dp(18), dp(12), dp(18), dp(12)) }, wrapLp().apply { bottomMargin = dp(14) })

        // Playback cache: the next queued track is downloaded so track changes are instant.
        page.addView(sectionTitle("Playback cache"))
        page.addView(Ui.text(this,
            "The next track in the queue is downloaded while the current one plays, so it starts " +
                "instantly and survives a network blip. Nothing leaves this phone.",
            12f, Ui.TEXT_2, lines = 3).apply { setPadding(0, 0, 0, dp(10)) })
        val cacheToggles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun refreshCacheToggles() {
            cacheToggles.removeAllViews()
            cacheToggles.addView(Ui.pill(this, "Cache: ${if (settings.cacheEnabled) "on" else "off"}", filled = true) {
                settings.cacheEnabled = !settings.cacheEnabled
                if (!settings.cacheEnabled) MediaCache.clear(this)
                renderSettings()
            }.apply { textSize = 13f; setPadding(dp(14), dp(8), dp(14), dp(8)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            cacheToggles.addView(Ui.pill(this, "Only on Wi-Fi: ${if (settings.cacheWifiOnly) "on" else "off"}", filled = false) {
                settings.cacheWifiOnly = !settings.cacheWifiOnly
                renderSettings()
            }.apply { textSize = 13f; setPadding(dp(14), dp(8), dp(14), dp(8)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refreshCacheToggles()
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(cacheToggles) }, wrapLp(top = 4))

        val sizes = listOf(512, 1024, 2048, 4096, 8192)
        val sizeChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun refreshSizeChips() {
            sizeChips.removeAllViews()
            for (mb in sizes) {
                val label = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
                sizeChips.addView(Ui.pill(this, label, filled = settings.cacheMaxMb == mb) {
                    settings.cacheMaxMb = mb
                    MediaCache.applyLimit(this)
                    renderSettings()
                }.apply { textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
        }
        refreshSizeChips()
        page.addView(Ui.text(this, "Maximum size", 13f, Ui.TEXT_2).apply { setPadding(0, dp(14), 0, dp(6)) })
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sizeChips) })

        val usedMb = (MediaCache.usedBytes(this) / (1024 * 1024)).toInt()
        page.addView(Ui.text(this,
            "Using $usedMb MB of ${settings.cacheMaxMb} MB.",
            12f, Ui.TEXT_2, lines = 2).apply { setPadding(0, dp(10), 0, 0) })
        page.addView(Ui.pill(this, "Clear cache", filled = false) {
            MediaCache.clear(this)
            renderSettings()
        }, wrapLp(top = 10))
        val downloaded = Downloads.collections(this)
        if (downloaded.isNotEmpty()) {
            page.addView(Ui.text(this,
                "Downloads: ${downloaded.size} albums and playlists, ${Downloads.usedBytes(this) / (1024 * 1024)} MB. " +
                    "These are never removed automatically and don't count towards the cache size.",
                12f, Ui.TEXT_2, lines = 3).apply { setPadding(0, dp(14), 0, 0) })
            page.addView(Ui.pill(this, "Remove all downloads", filled = false) {
                Downloads.removeAll(this)
                renderSettings()
            }, wrapLp(top = 10))
        }
        // The EQ/DSP screen is the secondary surface now that Music is the app.
        page.addView(sectionTitle("Equalizer & DSP"))
        page.addView(card(
            "Equalizer, preamp, limiter, speed compensation",
            "Also where the capture engine is started for system-wide EQ.",
            "Open",
        ) {
            startActivity(Intent(this, MainActivity::class.java))
        })
        page.addView(Ui.text(this, "Settings", 26f, bold = true).apply { setPadding(0, dp(4), 0, dp(16)) })

        // Nebula
        page.addView(sectionTitle("Search source"))
        val link = settings.addonLink
        page.addView(Ui.text(this,
            if (link.isBlank()) "Not connected"
            else "${settings.addonName.ifBlank { "Addon" }} · ${EclipseAddon.displayHost(link)}",
            15f, if (link.isBlank()) Ui.TEXT_2 else Ui.TEXT))
        page.addView(Ui.text(this, "Paste your Nebula link from Eclipse (Settings → Connections). It contains your TorBox key, so it stays hidden.", 12f, Ui.TEXT_2, lines = 3)
            .apply { setPadding(0, dp(4), 0, dp(8)) })
        val linkInput = field("https://nebula.eclipsemusic.app/…/manifest.json", link, secret = true)
        page.addView(linkInput)
        val linkStatus = Ui.text(this, "", 13f, Ui.TEXT_2, lines = 6)
        page.addView(Ui.pill(this, "Save link", filled = true) {
            hideKeyboard()
            saveAddon(linkInput.text.toString().trim(), linkStatus)
        }.apply { (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(8) }, wrapLp(top = 8))
        page.addView(linkStatus)

        // TorBox
        page.addView(sectionTitle("TorBox"))
        page.addView(Ui.text(this, "API key for your library and magnet links (TorBox → Settings → API).", 12f, Ui.TEXT_2, lines = 2)
            .apply { setPadding(0, 0, 0, dp(8)) })
        val keyInput = field("TorBox API key", settings.torboxApiKey, secret = true)
        page.addView(keyInput)
        val keyStatus = Ui.text(this, "", 13f, Ui.TEXT_2)
        page.addView(Ui.pill(this, "Save key", filled = false) {
            hideKeyboard()
            settings.torboxApiKey = keyInput.text.toString()
            keyStatus.text = if (settings.torboxApiKey.isBlank()) "Key cleared." else "Saved."
        }, wrapLp(top = 8))
        page.addView(keyStatus)

        // Library format
        page.addView(sectionTitle("Library format"))
        page.addView(Ui.text(this, "Which files to play when a library torrent has several formats.", 12f, Ui.TEXT_2, lines = 2)
            .apply { setPadding(0, 0, 0, dp(8)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun refreshChips() {
            chips.removeAllViews()
            for (f in AudioPicker.FormatFilter.values()) {
                chips.addView(Ui.pill(this, f.label, filled = f == settings.torboxFormat) {
                    settings.torboxFormat = f
                    refreshChips()
                }.apply { textSize = 13f; setPadding(dp(14), dp(8), dp(14), dp(8)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
        }
        refreshChips()
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        page.addView(sectionTitle("Album art"))
        page.addView(Ui.text(this, Artwork.summary(), 13f, Ui.TEXT_2, lines = 6))

        if (settings.recentSearches.isNotEmpty()) {
            page.addView(Ui.pill(this, "Clear recent searches", filled = false) {
                settings.clearRecentSearches()
                renderSettings()
            }, wrapLp(top = 24))
        }

        page.addView(sectionTitle("Playback"))
        page.addView(Ui.pill(this, "Play while downloading: ${if (settings.progressivePlayback) "on" else "off"}", filled = false) {
            settings.progressivePlayback = !settings.progressivePlayback
            renderSettings()
        }, wrapLp(top = 4))
        page.addView(Ui.text(this,
            "The song plays from its own download as it arrives: it carries on after a signal drop and is kept for next time. " +
                "Needs the song cache switched on. Turn this off to go back to plain streaming if a song won't start. Applies from the next song.",
            12f, Ui.TEXT_2, lines = 5).apply { setPadding(0, dp(8), 0, dp(12)) })
        page.addView(Ui.pill(this, "Match loudness between songs: ${if (settings.loudnessMatch) "on" else "off"}", filled = false) {
            settings.loudnessMatch = !settings.loudnessMatch
            renderSettings()
        }, wrapLp(top = 4))
        page.addView(Ui.text(this,
            "One volume setting per song, so a quiet track and a loud one play at the same level. " +
                "It does not compress: the quiet and loud parts inside a song stay as they were made. " +
                "A song is measured the first time it plays; albums keep their own balance. Applies from the next song.",
            12f, Ui.TEXT_2, lines = 5).apply { setPadding(0, dp(8), 0, 0) })

        // What the recommender has learned, in plain words, with a way to wipe it.
        page.addView(sectionTitle("Taste"))
        val profile = Taste.profile(this)
        val top = profile.topArtists(4)
        val signals = Taste.eventCount(this)
        if (top.isEmpty()) {
            page.addView(Ui.text(this,
                "Nothing learned yet. Play, finish, skip and ♡ a few songs — recommendations start " +
                    "working after a handful of tracks.",
                13f, Ui.TEXT_2, lines = 3))
        } else {
            page.addView(Ui.text(this,
                top.joinToString("\n") { (artist, score) -> "• $artist   ${String.format(Locale.US, "%.1f", score)}" },
                13f, Ui.TEXT_2, lines = 6))
            page.addView(Ui.text(this,
                "$signals signals, decayed over 14 days. " +
                    "Recommendations follow what you are playing now, then what you finish.",
                12f, Ui.TEXT_2, lines = 3).apply { setPadding(0, dp(6), 0, 0) })
            Radio.lastReason?.let {
                page.addView(Ui.text(this, "Last pick: $it", 12f, Ui.ACCENT, lines = 2)
                    .apply { setPadding(0, dp(6), 0, 0) })
            }
        }
        val blocked = Taste.blockedArtists(this)
        if (blocked.isNotEmpty()) {
            page.addView(Ui.text(this, "Never recommended — tap to allow again", 13f, Ui.TEXT_2).apply { setPadding(0, dp(12), 0, dp(4)) })
            for (name in blocked) {
                page.addView(simpleRow(name, "Blocked artist", android.R.drawable.ic_menu_close_clear_cancel) {
                    Taste.setArtistBlocked(this, name, false)
                    renderSettings()
                })
            }
        }
        page.addView(Ui.pill(this, "Forget what CabinEQ learned", filled = false) {
            Taste.reset(this)
            renderSettings()
        }, wrapLp(top = 10))

        // Jam: guests add songs from their phones over a relay; nothing is exposed but the queue.
        page.addView(sectionTitle("Jam"))
        page.addView(Ui.text(this,
            "Let people add songs from their phones. They open a link — no app, no account. " +
                "Your TorBox key and the audio stay on this phone.",
            12f, Ui.TEXT_2, lines = 3))
        page.addView(Ui.pill(this, if (JamHost.running) "Stop Jam" else "Start Jam", filled = true) {
            if (JamHost.running) JamHost.stop() else JamHost.start(this)
            renderSettings()
        }, wrapLp(top = 12))
        val sid = JamHost.sessionId
        if (JamHost.running && sid != null) {
            val url = JamHost.sessionUrl(sid)
            page.addView(Ui.text(this, url, 13f, Ui.ACCENT, lines = 2).apply {
                setTextIsSelectable(true)
                setPadding(0, dp(12), 0, 0)
            })
            // Same QR the car shows, for when you are not in the car.
            // Rendered at physical pixels so the modules stay crisp on a 2-3x density screen.
            val qrSize = dp(200)
            dev.alamo3.cabineq.jam.QrCodes.bitmap(url, (qrSize * resources.displayMetrics.density).toInt())?.let { qr ->
                page.addView(
                    ImageView(this).apply {
                        setImageBitmap(qr)
                        contentDescription = "Jam QR code"
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    wrapLp(top = 12).also { lp -> lp.width = qrSize; lp.height = qrSize },
                )
            }
            page.addView(Ui.pill(this, "Share link", filled = false) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Join my CabinEQ jam: $url")
                startActivity(Intent.createChooser(send, "Share jam link"))
            }, wrapLp(top = 8))
            page.addView(Ui.pill(this,
                if (JamHost.canSkip) "Guests can skip: on" else "Guests can skip: off", filled = false) {
                JamHost.canSkip = !JamHost.canSkip
                renderSettings()
            }, wrapLp(top = 8))
        }
    }

    // ---------------------------------------------------------------------------------
    // Helpers

    /** Runs [work] off the main thread and hands the result to [done] if [id] is still current. */
    private fun <T> background(id: Int, work: () -> T, done: (T) -> Unit) {
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                if (id != op || isDestroyed) return@runOnUiThread
                result.fold(done) { e -> showMessage(describe(e), isError = true) }
            }
        }.start()
    }

    private fun showMessage(text: String, isError: Boolean = false) {
        page.addView(Ui.text(this, text, 14f, if (isError) Ui.ERROR else Ui.TEXT_2, lines = 12).apply {
            setPadding(0, dp(16), 0, dp(8))
        })
    }

    /** User-facing error, ending with the underlying detail so failures can be diagnosed. */
    private fun describe(e: Throwable): String {
        val detail = "\n\nDetail: ${e.message ?: e.javaClass.simpleName}"
        return when {
            e is AddonException && (e.status == 401 || e.status == 403) ->
                "Nebula refused the saved link. Copy a fresh install link from Nebula into Settings.$detail"
            e is AddonException && e.status == 404 -> "Nebula doesn't have that.$detail"
            e is AddonException && e.status == 0 -> "Can't reach Nebula. Check your connection or VPN.$detail"
            e is AddonException -> "Nebula error.$detail"
            e is TorBoxException && e.isAuthError -> "TorBox rejected the API key. Check it in Settings.$detail"
            e is TorBoxException && e.isRateLimited -> "TorBox rate limit hit — wait a minute and try again.$detail"
            e is TorBoxException && e.status == 0 -> "Can't reach TorBox. Check your connection or VPN.$detail"
            else -> "Something went wrong.$detail"
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        searchInput.clearFocus()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun query(vararg parts: String?): String? =
        parts.filterNot { it.isNullOrBlank() }.joinToString(" ").ifBlank { null }

    /**
     * Cover search for the playing song: "artist title" from the source or the file's tags,
     * else just the song title. Never the album/queue name: on a compilation that finds the
     * wrong cover.
     */
    private fun trackQuery(t: Track?, info: FilePlayer.FileInfo?): String? = when {
        t == null -> null
        t.artist != null -> query(t.artist, t.title)
        info != null && info.artist != null -> query(info.artist, info.title ?: t.title)
        else -> query(info?.title ?: t.title)
    }

    companion object {
        const val ACTION_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

        /** A playlist handed over by the share sheet or a cabineq://import link. */
        const val ACTION_IMPORT = "dev.alamo3.cabineq.action.IMPORT"
        const val EXTRA_IMPORT_TEXT = "import_text"
        private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

        /** SAF request for the playlist file picker. */
        const val REQ_IMPORT_FILE = 4101


        private const val REQ_NOTIFICATIONS = 11
        private const val POLL_MS = 3000L
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val MAX_ALBUMS = 12
        private const val MAX_TRACKS = 50
        private const val MAX_ARTISTS = 5

        fun humanBytes(bytes: Long): String {
            if (bytes <= 0) return "?"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var v = bytes.toDouble()
            var u = 0
            while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
            return String.format(Locale.US, if (u == 0) "%.0f %s" else "%.1f %s", v, units[u])
        }

        /** Search text for a torrent/album name: tags, years and punctuation removed. */
        fun coverQuery(name: String): String =
            cleanTorrentName(name).replace(Regex("""\(\d{4}\)|\b(19|20)\d{2}\b"""), " ")
                .replace(Regex("""[-–—_.]+"""), " ").replace(Regex("""\s+"""), " ").trim()

        /** "Artist - Album (2020) [FLAC 24bit]" → "Artist - Album (2020)" for display. */
        fun cleanTorrentName(name: String): String =
            name.replace(Regex("""\s*[\[{][^\]}]*[\]}]"""), "").replace('_', ' ').trim().ifBlank { name }
    }
}
