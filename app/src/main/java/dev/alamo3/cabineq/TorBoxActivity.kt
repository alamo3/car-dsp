package dev.alamo3.cabineq

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.alamo3.cabineq.addon.AddonException
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.torbox.AudioPicker
import dev.alamo3.cabineq.torbox.TorBoxClient
import dev.alamo3.cabineq.torbox.TorBoxException
import dev.alamo3.cabineq.torbox.TorBoxTorrent
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Music front end. Everything plays through [FilePlayer] → native DSP.
 *
 *  - **Search** goes through an Eclipse addon such as Nebula (Apple Music catalog, audio served
 *    from the user's TorBox). TorBox retired its own search API, and its main API has none.
 *  - **My TorBox** lists torrents already on the account and auto-picks their audio files.
 *  - A **magnet link** typed into the search box is added to TorBox and played.
 *
 * Framework-only like the rest of the app. Network calls run on a background thread; each
 * new action bumps [op] so stale results and polling loops from an earlier action are dropped.
 */
class TorBoxActivity : Activity() {

    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var addonRow: LinearLayout
    private lateinit var addonInput: EditText
    private lateinit var addonSummary: TextView
    private lateinit var keyRow: LinearLayout
    private lateinit var keyInput: EditText
    private lateinit var keySummary: TextView
    private lateinit var searchInput: EditText
    private lateinit var statusText: TextView
    private lateinit var list: LinearLayout

    private lateinit var nowPlaying: LinearLayout
    private lateinit var npTitle: TextView
    private lateinit var npDetail: TextView
    private lateinit var npToggle: Button

    @Volatile private var op = 0
    private val formatButtons = LinkedHashMap<AudioPicker.FormatFilter, Button>()
    private var shownPlayerVersion = -1

    private val playerUpdater = object : Runnable {
        override fun run() {
            refreshNowPlaying()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        title = "Music"
        setContentView(buildUi())
        refreshAddonUi(editing = settings.addonLink.isBlank())
        refreshKeyUi(editing = false)
        setStatus(
            if (settings.addonLink.isBlank())
                "Paste your Nebula addon link above (the same link you installed in Eclipse) to search music."
            else "Search for a song, album or artist."
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(playerUpdater)
    }

    override fun onPause() {
        handler.removeCallbacks(playerUpdater)
        super.onPause()
    }

    override fun onDestroy() {
        op++  // stops any polling loop
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------
    // UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "Music"
            textSize = 26f
            setPadding(0, dp(4), 0, dp(12))
        })

        // --- Now playing ---
        nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            visibility = View.GONE
        }
        npTitle = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        npDetail = TextView(this).apply { textSize = 13f; alpha = 0.8f }
        nowPlaying.addView(npTitle)
        nowPlaying.addView(npDetail)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val player = FilePlayer.get(this)
        controls.addView(bigButton("⏮") { player.previous() }, weight())
        npToggle = bigButton("⏯") { player.togglePause() }
        controls.addView(npToggle, weight())
        controls.addView(bigButton("⏭") { player.next() }, weight())
        controls.addView(bigButton("⏹") { player.stop() }, weight())
        nowPlaying.addView(controls)
        root.addView(nowPlaying)

        // --- Search ---
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchInput = EditText(this).apply {
            hint = "Song, album or artist — or paste a magnet link"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) { runSearch(); true } else false
            }
        }
        searchRow.addView(searchInput, weight())
        searchRow.addView(Button(this).apply { text = "Search"; setOnClickListener { runSearch() } })
        root.addView(searchRow)

        root.addView(Button(this).apply {
            text = "My TorBox library"
            setOnClickListener { loadLibrary() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        // --- Settings ---
        root.addView(TextView(this).apply {
            text = "Sources"
            textSize = 18f
            setPadding(0, dp(24), 0, dp(4))
        })

        // Nebula / Eclipse addon link
        addonSummary = TextView(this).apply {
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { refreshAddonUi(editing = true) }
        }
        root.addView(addonSummary)
        addonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addonInput = EditText(this).apply {
            hint = "https://nebula.eclipsemusic.app/…/manifest.json"
            isSingleLine = true
            // Hidden like a password: the link contains your TorBox key.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.addonLink)
        }
        addonRow.addView(addonInput, weight())
        addonRow.addView(Button(this).apply { text = "Save"; setOnClickListener { saveAddon() } })
        root.addView(addonRow)

        // TorBox key (library + magnet links)
        keySummary = TextView(this).apply {
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { refreshKeyUi(editing = true) }
        }
        root.addView(keySummary)
        keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keyInput = EditText(this).apply {
            hint = "TorBox API key"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.torboxApiKey)
        }
        keyRow.addView(keyInput, weight())
        keyRow.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                settings.torboxApiKey = keyInput.text.toString()
                hideKeyboard()
                refreshKeyUi(editing = settings.torboxApiKey.isBlank())
            }
        })
        root.addView(keyRow)

        // Which format to play from library torrents that ship several (e.g. FLAC + MP3).
        root.addView(TextView(this).apply {
            text = "Library format"
            textSize = 13f
            alpha = 0.8f
            setPadding(0, dp(8), 0, 0)
        })
        val formatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in AudioPicker.FormatFilter.values()) {
            val b = Button(this).apply {
                textSize = 13f
                setOnClickListener {
                    settings.torboxFormat = f
                    refreshFormatButtons()
                }
            }
            formatButtons[f] = b
            formatRow.addView(b, weight())
        }
        root.addView(formatRow)
        refreshFormatButtons()

        return ScrollView(this).apply {
            addView(root)
            padForSystemBars()
        }
    }

    private fun refreshAddonUi(editing: Boolean) {
        val link = settings.addonLink
        addonRow.visibility = if (editing) View.VISIBLE else View.GONE
        addonSummary.text = when {
            editing -> "Search source: paste your Nebula link from Eclipse (Settings → Connections), then Save."
            link.isNotBlank() -> "Search: ${settings.addonName.ifBlank { "addon" }} (${EclipseAddon.displayHost(link)}) · tap to change"
            else -> "Search: not set · tap to add your Nebula link"
        }
    }

    private fun refreshKeyUi(editing: Boolean) {
        keyRow.visibility = if (editing) View.VISIBLE else View.GONE
        keySummary.text = when {
            editing -> "TorBox API key (for your library and magnet links): TorBox → Settings → API."
            settings.torboxApiKey.isNotBlank() -> "TorBox key saved (library + magnets) · tap to change"
            else -> "TorBox key: not set (only needed for your library and magnet links) · tap to add"
        }
    }

    private fun refreshFormatButtons() {
        val selected = settings.torboxFormat
        for ((f, b) in formatButtons) b.text = if (f == selected) "● ${f.label}" else f.label
    }

    private fun refreshNowPlaying() {
        val p = FilePlayer.peek()
        if (p == null || p.queue.isEmpty()) {
            nowPlaying.visibility = View.GONE
            return
        }
        nowPlaying.visibility = View.VISIBLE
        val stateLabel = when (p.state) {
            FilePlayer.State.LOADING -> "Loading…"
            FilePlayer.State.PLAYING -> "Playing"
            FilePlayer.State.PAUSED -> "Paused"
            FilePlayer.State.IDLE -> "Stopped"
            FilePlayer.State.ERROR -> "Error"
        }
        if (p.version != shownPlayerVersion) {
            shownPlayerVersion = p.version
            npTitle.text = p.currentTitle ?: "—"
            npToggle.text = if (p.state == FilePlayer.State.PLAYING || p.state == FilePlayer.State.LOADING) "⏸" else "▶"
        }
        val sb = StringBuilder()
        sb.append(stateLabel)
        sb.append("  ").append(mmss(p.positionMs))
        if (p.durationMs > 0) sb.append(" / ").append(mmss(p.durationMs))
        sb.append("  ·  track ${p.currentIndex + 1}/${p.queue.size}")
        p.note?.let { sb.append("\n⏳ ").append(it) }
        p.formatInfo?.let { sb.append('\n').append(it).append(" → CabinEQ DSP") }
        EngineState.playerRoute?.let { if (p.state == FilePlayer.State.PLAYING) sb.append(" → ").append(it) }
        if (p.state == FilePlayer.State.PLAYING && EngineState.running) {
            sb.append(String.format(Locale.US, "\nLevel in %.1f → out %.1f dBFS · limiter %.1f dB",
                EngineState.playerInPeakDb, EngineState.playerOutPeakDb, EngineState.playerLimiterDb))
        }
        p.queueTitle?.let { sb.append('\n').append(it) }
        (EngineState.playerMessage ?: p.lastError)?.let { sb.append("\n⚠ ").append(it) }
        npDetail.text = sb.toString()
    }

    // ---------------------------------------------------------------------------------
    // Addon search (Nebula)

    private fun addon(): EclipseAddon? {
        val link = settings.addonLink
        if (link.isBlank()) {
            refreshAddonUi(editing = true)
            setStatus("Paste your Nebula addon link first (Eclipse → Settings → Connections).")
            return null
        }
        return runCatching { EclipseAddon(link) }.getOrElse {
            refreshAddonUi(editing = true)
            setStatus("That addon link isn't valid: ${it.message}")
            null
        }
    }

    private fun saveAddon() {
        val link = addonInput.text.toString().trim()
        hideKeyboard()
        if (link.isBlank()) {
            settings.addonLink = ""
            settings.addonName = ""
            refreshAddonUi(editing = true)
            return
        }
        val candidate = runCatching { EclipseAddon(link) }.getOrElse {
            setStatus("That addon link isn't valid: ${it.message}")
            return
        }
        val id = ++op
        setStatus("Checking addon…")
        background(id, { candidate.manifest() }) { m ->
            settings.addonLink = link
            settings.addonName = m.name
            refreshAddonUi(editing = false)
            val missing = listOfNotNull(
                if (!m.canSearch) "search" else null,
                if (!m.canStream) "stream" else null,
            )
            setStatus(
                "Connected to ${m.name} ${m.version}." +
                    if (missing.isEmpty()) " Search away." else " ⚠ It doesn't offer: ${missing.joinToString()}."
            )
        }
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()
        if (query.startsWith("magnet:?")) {
            addMagnet(query)
            return
        }
        val a = addon() ?: return
        val id = ++op
        setStatus("Searching “$query”…")
        list.removeAllViews()
        background(id, { a.search(query) }) { results -> showResults(a, query, results) }
    }

    private fun showResults(a: EclipseAddon, query: String, r: EclipseAddon.SearchResults) {
        list.removeAllViews()
        if (r.tracks.isEmpty() && r.albums.isEmpty()) {
            setStatus("No results for “$query”.")
            return
        }
        setStatus("Results for “$query” — tap a song to play, or an album to open it.")
        if (r.albums.isNotEmpty()) {
            list.addView(sectionLabel("Albums"))
            for (al in r.albums.take(MAX_ALBUMS)) {
                val detail = listOfNotNull(al.artist.ifBlank { null }, al.year, al.trackCount?.let { "$it tracks" })
                    .joinToString(" · ")
                list.addView(row(al.title, detail) { openAlbum(a, al) })
            }
        }
        if (r.tracks.isNotEmpty()) {
            list.addView(sectionLabel("Songs"))
            val tracks = r.tracks.take(MAX_TRACKS)
            tracks.forEachIndexed { i, t ->
                list.addView(row(t.title, trackDetail(t)) {
                    // Play from the tapped song and keep going through the results.
                    play(tracks.map { addonTrack(a, it) }, i, "Search: $query")
                })
            }
        }
    }

    private fun openAlbum(a: EclipseAddon, album: EclipseAddon.Album) {
        val id = ++op
        setStatus("Opening “${album.title}”…")
        background(id, { a.album(album.id) }) { detail ->
            list.removeAllViews()
            val al = detail.album
            setStatus(listOfNotNull(al.title, al.artist.ifBlank { null }, al.year).joinToString(" · ") +
                "\n${detail.tracks.size} tracks")
            if (detail.tracks.isEmpty()) return@background
            val queue = detail.tracks.map { addonTrack(a, it) }
            val queueTitle = "${al.artist} — ${al.title}".trimStart(' ', '—')
            list.addView(Button(this).apply {
                text = "▶ Play album"
                setOnClickListener { play(queue, 0, queueTitle) }
            })
            detail.tracks.forEachIndexed { i, t ->
                list.addView(row("${i + 1}. ${t.title}", trackDetail(t)) { play(queue, i, queueTitle) })
            }
            play(queue, 0, queueTitle)
        }
    }

    /** A player track that asks the addon for its stream right before it plays. */
    private fun addonTrack(a: EclipseAddon, t: EclipseAddon.Track): FilePlayer.Track {
        val label = if (t.artist.isBlank()) t.title else "${t.title} — ${t.artist}"
        return FilePlayer.Track(label) {
            t.streamUrl ?: run {
                val player = FilePlayer.get(this)
                val ticket = player.ticket
                a.resolveStream(t.id, isCancelled = { player.isStale(ticket) }) { notice ->
                    player.setNote(
                        notice?.let { n ->
                            listOfNotNull(n.title, n.message).joinToString(": ") +
                                (n.progress?.let { p -> " (${(if (p <= 1.0) p * 100 else p).roundToInt()}%)" } ?: "")
                        } ?: "Preparing…"
                    )
                }.url
            }
        }
    }

    private fun trackDetail(t: EclipseAddon.Track): String = listOfNotNull(
        t.artist.ifBlank { null },
        t.album,
        t.durationSec?.let { mmss(it * 1000L) },
        t.format?.uppercase(Locale.ROOT),
    ).joinToString(" · ")

    // ---------------------------------------------------------------------------------
    // TorBox: library and magnet links

    private fun torbox(): TorBoxClient? {
        val key = settings.torboxApiKey
        if (key.isBlank()) {
            refreshKeyUi(editing = true)
            setStatus("Add your TorBox API key under Sources for your library and magnet links.")
            return null
        }
        return TorBoxClient(key)
    }

    private fun addMagnet(magnet: String) {
        val c = torbox() ?: return
        val id = ++op
        list.removeAllViews()
        setStatus("Adding magnet to TorBox…")
        background(id, {
            try {
                c.createTorrentFromMagnet(magnet, allowZip = false).torrentId
            } catch (e: TorBoxException) {
                // Already on the account: find it by hash instead.
                if (e.isAuthError || e.isRateLimited) throw e
                TorBoxClient.hashFromMagnet(magnet)?.let { c.findByHash(it)?.id } ?: throw e
            }
        }) { torrentId -> awaitReady(c, torrentId, id) }
    }

    private fun loadLibrary() {
        val c = torbox() ?: return
        val id = ++op
        setStatus("Loading your TorBox torrents…")
        list.removeAllViews()
        background(id, { c.listTorrents(bypassCache = true) }) { everything ->
            list.removeAllViews()
            // Audio only: hide finished torrents that are video (video files, no playable audio).
            val torrents = everything.filterNot { it.isReady && AudioPicker.isVideoTorrent(it.files) }
            val hidden = everything.size - torrents.size
            val hiddenNote = if (hidden > 0) " · $hidden video torrents hidden" else ""
            setStatus(if (torrents.isEmpty()) "No audio torrents on your TorBox.$hiddenNote" else "My TorBox — audio (${torrents.size})$hiddenNote")
            for (t in torrents) {
                val pick = AudioPicker.pick(t.files)
                val detail = mutableListOf(humanBytes(t.size))
                detail.add(if (t.isReady) "ready" else "${t.downloadState} ${(t.progress * 100).roundToInt()}%")
                if (pick.tracks.isNotEmpty()) detail.add("${pick.tracks.size} ${pick.format?.uppercase(Locale.ROOT)}")
                else if (t.files.isNotEmpty()) detail.add("no playable audio")
                list.addView(row(t.name, detail.joinToString(" · ")) {
                    if (t.isReady) showTorrent(c, t, autoplay = false) else awaitReady(c, t.id, ++op)
                })
            }
        }
    }

    /** Polls the torrent until TorBox has it, then shows its tracks and starts playback. */
    private fun awaitReady(c: TorBoxClient, torrentId: Int, id: Int) {
        if (id != op) return
        background(id, { c.getTorrent(torrentId) }) { t ->
            when {
                t == null -> setStatus("TorBox doesn't know torrent #$torrentId.")
                t.isReady -> showTorrent(c, t, autoplay = true)
                else -> {
                    val speed = if (t.downloadSpeed > 0) " · ${humanBytes(t.downloadSpeed)}/s" else ""
                    setStatus(
                        "Downloading on TorBox: ${(t.progress * 100).roundToInt()}%$speed · ${t.seeds} seeds · ${t.downloadState}\n" +
                            "It will start playing when it's ready. ${t.name}"
                    )
                    handler.postDelayed({ awaitReady(c, torrentId, id) }, POLL_MS)
                }
            }
        }
    }

    private fun showTorrent(c: TorBoxClient, t: TorBoxTorrent, autoplay: Boolean) {
        // Honour the format choice (e.g. MP3 from an album that ships FLAC + MP3), but don't
        // come up empty just because it doesn't match this torrent.
        val pick = AudioPicker.pick(t.files, settings.torboxFormat).takeIf { it.tracks.isNotEmpty() }
            ?: AudioPicker.pick(t.files)
        list.removeAllViews()
        if (pick.tracks.isEmpty()) {
            if (AudioPicker.isVideoTorrent(t.files)) {
                setStatus("“${t.name}” is a video torrent — CabinEQ only plays audio.")
                return
            }
            setStatus(
                "No playable audio in “${t.name}”." +
                    if (pick.unsupportedCount > 0) " (${pick.unsupportedCount} files in formats Android can't decode, e.g. APE/WavPack/DSD.)" else ""
            )
            return
        }
        val kind = if (pick.lossless) "lossless" else "lossy"
        val skipped = if (pick.unsupportedCount > 0) " · skipped ${pick.unsupportedCount} unsupported" else ""
        setStatus("${t.name}\n${pick.tracks.size} ${pick.format?.uppercase(Locale.ROOT)} tracks auto-selected ($kind)$skipped")

        val tracks = pick.tracks.map { f ->
            FilePlayer.Track(AudioPicker.trackTitle(f)) { c.requestDownloadUrl(t.id, f.id) }
        }
        list.addView(Button(this).apply {
            text = "▶ Play all"
            setOnClickListener { play(tracks, 0, t.name) }
        })
        pick.tracks.forEachIndexed { i, f ->
            list.addView(row("${i + 1}. ${AudioPicker.trackTitle(f)}", humanBytes(f.size)) { play(tracks, i, t.name) })
        }
        if (autoplay) play(tracks, 0, t.name)
    }

    // ---------------------------------------------------------------------------------
    // Playback

    private fun play(tracks: List<FilePlayer.Track>, index: Int, title: String) {
        EngineState.playerMessage = null
        FilePlayer.get(this).play(tracks, index, title)
        // Keeps playback alive in the background and feeds the live EQ + speed compensation.
        startForegroundService(Intent(this, EqService::class.java).setAction(EqService.ACTION_PLAYER_ATTACH))
        shownPlayerVersion = -1
        refreshNowPlaying()
    }

    // ---------------------------------------------------------------------------------
    // Helpers

    /** Runs [work] off the main thread and hands the result to [done] if [id] is still current. */
    private fun <T> background(id: Int, work: () -> T, done: (T) -> Unit) {
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                if (id != op || isDestroyed) return@runOnUiThread
                result.fold(done) { e -> setStatus(describe(e)) }
            }
        }.start()
    }

    /** User-facing error, always ending with the underlying detail so failures can be diagnosed. */
    private fun describe(e: Throwable): String {
        val detail = "\n\nDetail: ${e.message ?: e.javaClass.simpleName}" +
            (e.cause?.let { "\nCause: ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        return when {
            e is AddonException && (e.status == 401 || e.status == 403) -> {
                refreshAddonUi(editing = true)
                "The addon refused this link. Copy a fresh install link from Nebula.$detail"
            }
            e is AddonException && e.status == 404 -> "The addon doesn't have that.$detail"
            e is AddonException && e.status == 0 -> "Can't reach the addon. Check your connection or VPN.$detail"
            e is AddonException -> "Addon error.$detail"
            e is TorBoxException && e.isAuthError -> {
                refreshKeyUi(editing = true)
                "TorBox rejected the API key. Check it and save again.$detail"
            }
            e is TorBoxException && e.isRateLimited -> "TorBox rate limit hit — wait a minute and try again.$detail"
            e is TorBoxException && e.code == "TIMEOUT" -> "TorBox timed out. Try again.$detail"
            e is TorBoxException && e.status == 0 -> "Can't reach TorBox. Check your connection or VPN.$detail"
            else -> "Error.$detail"
        }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun row(title: String, detail: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(10), dp(4), dp(10))
        minimumHeight = dp(56)
        isClickable = true
        val tv = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = title
            textSize = 15f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })
        if (detail.isNotBlank()) addView(TextView(context).apply {
            text = detail
            textSize = 12f
            alpha = 0.7f
        })
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        minHeight = dp(56)
        setOnClickListener {
            onClick()
            handler.postDelayed({ refreshNowPlaying() }, 100)
        }
    }

    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    companion object {
        private const val POLL_MS = 3000L
        private const val MAX_ALBUMS = 10
        private const val MAX_TRACKS = 50

        fun humanBytes(bytes: Long): String {
            if (bytes <= 0) return "?"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var v = bytes.toDouble()
            var u = 0
            while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
            return String.format(Locale.US, if (u == 0) "%.0f %s" else "%.1f %s", v, units[u])
        }

        fun mmss(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }
}
