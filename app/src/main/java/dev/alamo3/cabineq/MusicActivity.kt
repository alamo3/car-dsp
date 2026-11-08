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
 *  - Album and artist pages; My TorBox library; settings for the sources.
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
    private lateinit var fullSeek: SeekBar
    private lateinit var fullPos: TextView
    private lateinit var fullDur: TextView
    private lateinit var fullToggle: ImageView
    private lateinit var fullInfo: TextView
    private lateinit var fullNote: TextView
    private var seeking = false

    @Volatile private var op = 0
    private var shownVersion = -1
    private var shownArt: String? = "\u0000"
    private val stack = ArrayDeque<() -> Unit>()
    private var searchPageOnTop = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshPlayer()
            handler.postDelayed(this, 500)
        }
    }

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
                    handler.removeCallbacks(searchRunnable)
                    val q = s?.toString()?.trim().orEmpty()
                    when {
                        q.isEmpty() -> if (searchPageOnTop) onBackPressedCompat()
                        q.startsWith("magnet:") -> Unit       // added on the Search key, not while typing
                        q.length >= 2 -> handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
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
            isFillViewport = true
            addView(page)
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        column.addView(buildMini())
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
        miniToggle = Ui.iconButton(this, android.R.drawable.ic_media_play, dp(44)) { player.togglePause(); refreshSoon() }
        mini.addView(miniToggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini.addView(Ui.iconButton(this, android.R.drawable.ic_media_next, dp(44)) { player.next(); refreshSoon() },
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
                    player.seekTo(sb.progress * 1000L)
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
        controls.addView(Ui.iconButton(this, android.R.drawable.ic_media_previous, dp(56)) { player.previous(); refreshSoon() },
            LinearLayout.LayoutParams(dp(64), dp(64)))
        fullToggle = Ui.iconButton(this, android.R.drawable.ic_media_play, dp(76), Color.WHITE, Ui.ACCENT) {
            player.togglePause(); refreshSoon()
        }
        controls.addView(fullToggle, LinearLayout.LayoutParams(dp(80), dp(80)).apply { leftMargin = dp(24); rightMargin = dp(24) })
        controls.addView(Ui.iconButton(this, android.R.drawable.ic_media_next, dp(56)) { player.next(); refreshSoon() },
            LinearLayout.LayoutParams(dp(64), dp(64)))
        col.addView(controls)

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

    private fun clearPage() = page.removeAllViews()

    // ---------------------------------------------------------------------------------
    // Pages

    private fun renderHome() {
        clearPage()
        page.addView(Ui.text(this, "Music", 30f, bold = true).apply { setPadding(0, dp(8), 0, dp(16)) })

        if (settings.addonLink.isBlank()) {
            page.addView(card(
                "Connect Nebula to search",
                "Paste the Nebula link you installed in Eclipse (Settings → Connections). Search uses Apple Music's catalog; audio streams from your TorBox.",
                "Connect",
            ) { navigate { renderSettings() } })
        }

        page.addView(card("My TorBox library", "Albums already on your TorBox account", "Open") {
            navigate { renderLibrary() }
        })

        val recents = settings.recentSearches
        if (recents.isNotEmpty()) {
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
        page.addView(Ui.text(this, "All playback runs through CabinEQ's DSP: your EQ, preamp, limiter and speed compensation.", 12f, Ui.TEXT_2, lines = 3)
            .apply { setPadding(0, dp(24), 0, 0) })
    }

    private fun runSearch(query: String, fromTyping: Boolean) {
        if (query.isEmpty()) return
        if (query.startsWith("magnet:?")) {
            if (!fromTyping) addMagnet(query)
            return
        }
        val a = addon() ?: return
        if (!fromTyping) settings.addRecentSearch(query)
        // Typing refines the search page in place instead of stacking a page per keystroke.
        if (searchPageOnTop) stack.removeLast()
        val id = ++op
        stack.addLast { renderSearchLoading(query) }
        searchPageOnTop = true
        renderSearchLoading(query)
        background(id, { a.search(query) }) { r ->
            val render = { renderSearch(a, query, r) }
            stack.removeLast()
            stack.addLast(render)
            searchPageOnTop = true
            render()
        }
    }

    private fun renderSearchLoading(query: String) {
        clearPage()
        page.addView(Ui.text(this, "Searching “$query”…", 14f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
    }

    private fun renderSearch(a: EclipseAddon, query: String, r: EclipseAddon.SearchResults) {
        clearPage()
        if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty()) {
            page.addView(Ui.text(this, "No results for “$query”.", 15f, Ui.TEXT_2).apply { setPadding(0, dp(16), 0, 0) })
            return
        }
        if (r.tracks.isNotEmpty()) {
            val songs = r.tracks.take(MAX_TRACKS)
            page.addView(sectionTitle("Songs", if (songs.size > 5) "See all" else null) {
                navigate { renderSongList("Songs · $query", a, songs, "Search: $query") }
            })
            songs.take(5).forEachIndexed { i, t ->
                page.addView(songRow(t, showArt = true) {
                    settings.addRecentSearch(query)
                    play(songs.map { addonTrack(a, it) }, i, "Search: $query")
                })
            }
        }
        if (r.albums.isNotEmpty()) {
            page.addView(sectionTitle("Albums"))
            page.addView(albumCarousel(r.albums.take(MAX_ALBUMS)) { al ->
                settings.addRecentSearch(query)
                navigate { renderAlbum(a, al) }
            })
        }
        if (r.artists.isNotEmpty()) {
            page.addView(sectionTitle("Artists"))
            for (ar in r.artists.take(MAX_ARTISTS)) {
                page.addView(artistRow(ar) {
                    settings.addRecentSearch(query)
                    navigate { renderArtist(a, ar) }
                })
            }
        }
    }

    private fun renderSongList(title: String, a: EclipseAddon, songs: List<EclipseAddon.Track>, queueTitle: String) {
        clearPage()
        page.addView(backRow())
        page.addView(Ui.text(this, title, 24f, bold = true, lines = 2).apply { setPadding(0, dp(4), 0, dp(12)) })
        songs.forEachIndexed { i, t ->
            page.addView(songRow(t, showArt = true) { play(songs.map { addonTrack(a, it) }, i, queueTitle) })
        }
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

    private fun renderSettings() {
        clearPage()
        page.addView(backRow())
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
    }

    private fun renderLibrary() {
        clearPage()
        page.addView(backRow())
        page.addView(Ui.text(this, "My TorBox", 26f, bold = true).apply { setPadding(0, dp(4), 0, dp(12)) })
        val c = torbox() ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(box)
        box.addView(Ui.text(this, "Loading…", 14f, Ui.TEXT_2))
        background(op, { c.listTorrents(bypassCache = true) }) { everything ->
            box.removeAllViews()
            // Audio only: hide finished torrents that are video (video files, no playable audio).
            val torrents = everything.filterNot { it.isReady && AudioPicker.isVideoTorrent(it.files) }
            if (torrents.isEmpty()) {
                box.addView(Ui.text(this, "No audio on your TorBox yet.", 14f, Ui.TEXT_2))
                return@background
            }
            for (t in torrents) {
                val pick = AudioPicker.pick(t.files)
                val detail = when {
                    !t.isReady -> "${t.downloadState} · ${(t.progress * 100).roundToInt()}%"
                    pick.tracks.isNotEmpty() -> "${pick.tracks.size} songs · ${pick.format?.uppercase(Locale.ROOT)}"
                    else -> "No playable audio"
                }
                box.addView(coverRow(cleanTorrentName(t.name), detail, coverQuery(t.name)) {
                    navigate { renderTorrent(c, t) }
                })
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
            val pick = AudioPicker.pick(t.files, settings.torboxFormat).takeIf { it.tracks.isNotEmpty() } ?: AudioPicker.pick(t.files)
            if (pick.tracks.isEmpty()) {
                box.addView(Ui.text(this,
                    if (AudioPicker.isVideoTorrent(t.files)) "This is a video torrent — CabinEQ only plays audio."
                    else "No playable audio" + if (pick.unsupportedCount > 0) " (${pick.unsupportedCount} files in formats Android can't decode)." else ".",
                    14f, Ui.TEXT_2, lines = 3))
                return
            }
            val queue = pick.tracks.map { f ->
                FilePlayer.Track(AudioPicker.trackTitle(f), album = title, artworkUrl = null) { c.requestDownloadUrl(t.id, f.id) }
            }
            addPlayShuffle(box, queue, title, "${pick.tracks.size} songs · ${pick.format?.uppercase(Locale.ROOT)}")
            pick.tracks.forEachIndexed { i, f ->
                box.addView(numberedRow(i + 1, AudioPicker.trackTitle(f), null, humanBytes(f.size)) { play(queue, i, title) })
            }
        }
        show(initial)
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
        val right = listOfNotNull(t.format?.uppercase(Locale.ROOT)?.takeIf { it == "FLAC" || it == "WAV" || it == "ALAC" },
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

    private fun addPlayShuffle(into: LinearLayout, queue: List<FilePlayer.Track>, title: String, meta: String?) {
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

    private fun coverRow(title: String, sub: String?, fallbackQuery: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { onClick() }
        }
        Ui.selectable(row)
        val art = Ui.cover(this, dp(52), dp(6).toFloat())
        Artwork.load(art, null, dp(52) * 2, fallbackQuery, Artwork.Kind.ALBUM)
        row.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)))
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

    private fun refreshPlayer() {
        val p = FilePlayer.peek()
        val has = p != null && p.queue.isNotEmpty()
        (mini.parent as View).visibility = if (has) View.VISIBLE else View.GONE
        if (!has || p == null) {
            if (full.visibility == View.VISIBLE) showFull(false)
            return
        }
        val t = p.currentTrack
        val playing = p.state == FilePlayer.State.PLAYING || p.state == FilePlayer.State.LOADING
        if (p.version != shownVersion) {
            shownVersion = p.version
            val title = t?.title ?: "—"
            val artist = t?.artist ?: t?.album ?: p.queueTitle ?: ""
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
            val artQuery = trackQuery(t, p.queueTitle)
            val artKey = "${t?.artworkUrl}|$artQuery"
            if (artKey != shownArt) {
                shownArt = artKey
                val kind = if (t?.artist != null) Artwork.Kind.SONG else Artwork.Kind.ALBUM
                Artwork.load(miniArt, t?.artworkUrl, dp(44) * 2, artQuery, kind)
                Artwork.load(fullArt, t?.artworkUrl, min(resources.displayMetrics.widthPixels, 1000), artQuery, kind)
            }
        }
        val dur = p.durationMs
        miniProgress.progress = if (dur > 0) ((p.positionMs * 1000) / dur).toInt() else 0
        if (full.visibility == View.VISIBLE) {
            if (!seeking) {
                fullSeek.max = (dur / 1000).toInt().coerceAtLeast(1)
                fullSeek.progress = (p.positionMs / 1000).toInt()
                fullPos.text = Ui.mmss(p.positionMs)
            }
            fullDur.text = if (dur > 0) Ui.mmss(dur) else "--:--"
            val info = StringBuilder()
            p.formatInfo?.let { info.append(it).append(" → CabinEQ DSP") }
            EngineState.playerRoute?.let { if (p.state == FilePlayer.State.PLAYING) info.append(" → ").append(it) }
            if (p.state == FilePlayer.State.PLAYING && EngineState.running) {
                info.append(String.format(Locale.US, "\nLevel in %.1f → out %.1f dBFS · limiter %.1f dB",
                    EngineState.playerInPeakDb, EngineState.playerOutPeakDb, EngineState.playerLimiterDb))
            }
            fullInfo.text = info
            val note = p.note?.let { "⏳ $it" } ?: (EngineState.playerMessage ?: p.lastError)?.let { "⚠ $it" }
            fullNote.text = note ?: ""
            fullNote.setTextColor(if (p.note != null) Ui.TEXT_2 else Ui.ERROR)
            fullNote.visibility = if (note == null) View.GONE else View.VISIBLE
        }
    }

    private fun showFull(show: Boolean) {
        if (show) hideKeyboard()
        full.visibility = if (show) View.VISIBLE else View.GONE
        shownVersion = -1
        refreshPlayer()
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
    private fun addonTrack(a: EclipseAddon, t: EclipseAddon.Track): FilePlayer.Track =
        FilePlayer.Track(t.title, artist = t.artist.ifBlank { null }, album = t.album, artworkUrl = t.artworkUrl) {
            t.streamUrl ?: run {
                val ticket = player.ticket
                a.resolveStream(t.id, isCancelled = { player.isStale(ticket) }) { notice ->
                    player.setNote(
                        notice?.let { n ->
                            listOfNotNull(n.title, n.message).joinToString(": ") +
                                (n.progress?.let { pr -> " (${(if (pr <= 1.0) pr * 100 else pr).roundToInt()}%)" } ?: "")
                        } ?: "Preparing…"
                    )
                }.url
            }
        }

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

    private fun play(tracks: List<FilePlayer.Track>, index: Int, title: String) {
        EngineState.playerMessage = null
        player.play(tracks, index, title)
        // Keeps playback alive in the background and feeds the live EQ + speed compensation.
        startForegroundService(Intent(this, EqService::class.java).setAction(EqService.ACTION_PLAYER_ATTACH))
        shownVersion = -1
        refreshPlayer()
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

    /** Fallback cover search for whatever is playing: "artist title", or the album/queue name. */
    private fun trackQuery(t: FilePlayer.Track?, queueTitle: String?): String? = when {
        t == null -> null
        t.artist != null -> query(t.artist, t.title)
        t.album != null -> coverQuery(t.album)
        else -> queueTitle?.let { coverQuery(it) }
    }

    companion object {
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
