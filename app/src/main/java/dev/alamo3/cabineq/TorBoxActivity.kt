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
import android.widget.Switch
import android.widget.TextView
import dev.alamo3.cabineq.torbox.AudioPicker
import dev.alamo3.cabineq.torbox.SearchResult
import dev.alamo3.cabineq.torbox.TorBoxClient
import dev.alamo3.cabineq.torbox.TorBoxException
import dev.alamo3.cabineq.torbox.TorBoxTorrent
import java.util.Locale
import kotlin.math.roundToInt

/**
 * TorBox front end: search → add to TorBox → wait until it's on TorBox's servers → pick the
 * audio files automatically → stream them through [FilePlayer] (native EQ).
 *
 * Framework-only like the rest of the app. Network calls run on a background thread; each
 * new action bumps [op] so stale results and polling loops from an earlier action are dropped.
 */
class TorBoxActivity : Activity() {

    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())

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
        title = "TorBox"
        setContentView(buildUi())
        refreshKeyUi(editing = settings.torboxApiKey.isBlank())
        if (settings.torboxApiKey.isNotBlank()) loadLibrary()
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

        // --- API key ---
        keySummary = TextView(this).apply {
            textSize = 13f
            alpha = 0.8f
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
                if (settings.torboxApiKey.isNotBlank()) loadLibrary()
            }
        })
        root.addView(keyRow)

        // --- Search ---
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        searchInput = EditText(this).apply {
            hint = "Artist / album (add \"flac\" for lossless)"
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

        root.addView(Switch(this).apply {
            text = "Cached only (plays instantly)"
            textSize = 15f
            minHeight = dp(44)
            isChecked = settings.torboxCachedOnly
            setOnCheckedChangeListener { _, checked -> settings.torboxCachedOnly = checked }
        })

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(Button(this).apply { text = "My TorBox"; setOnClickListener { loadLibrary() } }, weight())
        root.addView(tabs)

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshKeyUi(editing: Boolean) {
        val hasKey = settings.torboxApiKey.isNotBlank()
        keyRow.visibility = if (editing) View.VISIBLE else View.GONE
        keySummary.text = when {
            editing -> "Paste your API key from TorBox → Settings → API."
            hasKey -> "TorBox API key saved · tap to change"
            else -> ""
        }
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
        p.formatInfo?.let { sb.append('\n').append(it).append(" → CabinEQ DSP") }
        EngineState.playerRoute?.let { if (p.state == FilePlayer.State.PLAYING) sb.append(" → ").append(it) }
        if (p.state == FilePlayer.State.PLAYING && EngineState.running) {
            sb.append(String.format(Locale.US, "\nOut peak %.1f dBFS · limiter %.1f dB", EngineState.playerOutPeakDb, EngineState.playerLimiterDb))
        }
        p.queueTitle?.let { sb.append('\n').append(it) }
        (EngineState.playerMessage ?: p.lastError)?.let { sb.append("\n⚠ ").append(it) }
        npDetail.text = sb.toString()
    }

    // ---------------------------------------------------------------------------------
    // Actions

    private fun client(): TorBoxClient? {
        val key = settings.torboxApiKey
        if (key.isBlank()) {
            refreshKeyUi(editing = true)
            setStatus("Add your TorBox API key first.")
            return null
        }
        return TorBoxClient(key)
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return
        val c = client() ?: return
        hideKeyboard()
        val id = ++op
        setStatus("Searching “$query”…")
        list.removeAllViews()
        background(id, {
            AudioPicker.rank(c.search(query, checkCache = true))
        }) { results -> showResults(query, results) }
    }

    private fun showResults(query: String, all: List<SearchResult>) {
        list.removeAllViews()
        val cacheKnown = all.any { it.cached != null }
        val results = if (settings.torboxCachedOnly && cacheKnown) all.filter { it.cached == true } else all
        setStatus(
            when {
                all.isEmpty() -> "No results for “$query”."
                results.isEmpty() -> "${all.size} results, none cached. Turn off “Cached only” to see them."
                settings.torboxCachedOnly && !cacheKnown -> "${results.size} results (TorBox didn't report cache state)."
                else -> "${results.size} results — tap one to add it and play."
            }
        )
        for (r in results) {
            val tags = mutableListOf(humanBytes(r.size), "${r.seeders} seeders")
            if (r.cached == true) tags.add("⚡ cached")
            if (AudioPicker.looksLossless(r.title)) tags.add("lossless")
            r.tracker?.let { tags.add(it) }
            list.addView(row(r.title, tags.joinToString(" · ")) { openSearchResult(r) })
        }
    }

    private fun openSearchResult(r: SearchResult) {
        val c = client() ?: return
        val id = ++op
        setStatus("Adding to TorBox…")
        background(id, {
            val torrentId = try {
                c.createTorrentFromMagnet(r.magnet, allowZip = false).torrentId
            } catch (e: TorBoxException) {
                // Already on the account (or a duplicate error): find it by hash instead.
                if (e.isAuthError || e.isRateLimited) throw e
                c.findByHash(r.hash)?.id ?: throw e
            }
            torrentId
        }) { torrentId -> awaitReady(c, torrentId, id) }
    }

    private fun loadLibrary() {
        val c = client() ?: return
        val id = ++op
        setStatus("Loading your TorBox torrents…")
        list.removeAllViews()
        background(id, { c.listTorrents(bypassCache = true) }) { torrents ->
            list.removeAllViews()
            setStatus(if (torrents.isEmpty()) "No torrents on your TorBox yet. Search above." else "My TorBox (${torrents.size})")
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
        val pick = AudioPicker.pick(t.files)
        list.removeAllViews()
        if (pick.tracks.isEmpty()) {
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

    private fun describe(e: Throwable): String = when {
        e is TorBoxException && e.isAuthError -> {
            refreshKeyUi(editing = true)
            "TorBox rejected the API key. Check it and save again."
        }
        e is TorBoxException && e.isRateLimited -> "TorBox rate limit hit — wait a minute and try again."
        e is TorBoxException && e.status == 0 -> "Can't reach TorBox (${e.code}). Check your connection."
        else -> "TorBox error: ${e.message}"
    }

    private fun setStatus(text: String) {
        statusText.text = text
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
        addView(TextView(context).apply {
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
