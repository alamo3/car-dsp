package dev.alamo3.cabineq.car

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SearchHeader
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import dev.alamo3.cabineq.ArtworkProvider
import dev.alamo3.cabineq.MediaBrowse
import dev.alamo3.cabineq.Radio
import dev.alamo3.cabineq.Taste
import dev.alamo3.cabineq.PlaybackTokenHolder
import dev.alamo3.cabineq.R
import dev.alamo3.cabineq.Settings
import dev.alamo3.cabineq.Track
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.jam.JamHost
import dev.alamo3.cabineq.toTrack
import dev.alamo3.cabineq.music.Artwork
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val uiIo = Executors.newSingleThreadExecutor { r ->
    Thread(r, "CabinEQ-CarUI").apply { isDaemon = true }
}
// Home's two network lookups (artist shelf, recommendations) run side by side, not one after
// the other, and apart from uiIo so another screen's load never waits behind them.
private val homeIo = Executors.newFixedThreadPool(2) { r ->
    Thread(r, "CabinEQ-CarHome").apply { isDaemon = true }
}
private val artIo = Executors.newFixedThreadPool(4) { r ->
    Thread(r, "CabinEQ-CarArt").apply { isDaemon = true }
}
private val main = Handler(Looper.getMainLooper())

/**
 * Hand the host our MediaSession once it exists. Without this the car's "now playing" card and
 * media controls don't know about us (it looks like nothing is playing).
 */
object CarMediaBridge {
    @Volatile private var manager: MediaPlaybackManager? = null
    @Volatile private var registered = false

    fun register(carContext: CarContext): Boolean {
        if (registered) return true
        val token = PlaybackTokenHolder.token ?: return false
        val m = manager ?: runCatching {
            carContext.getCarService(MediaPlaybackManager::class.java)
        }.getOrNull()?.also { manager = it } ?: return false
        return runCatching {
            m.registerMediaPlaybackToken(token)
            registered = true
            true
        }.getOrDefault(false)
    }
}

/**
 * Full-size album art as a content:// URI. The host loads it in its own process, which is why
 * these go through [ArtworkProvider]; pre-warming the cache (below) keeps that fetch instant.
 */
private fun carIcon(context: Context, url: String?, query: String?): CarIcon =
    ArtworkProvider.uriFor(url = url, query = query, sizePx = 512)
        ?.let { CarIcon.Builder(IconCompat.createWithContentUri(it)).build() }
    // No art anywhere: our EQ glyph instead of the host's blank block.
        ?: CarIcon.Builder(IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground)).build()

/** A header or strip action must carry an icon: a custom action may not be text-only. */
private fun headerIcon(context: Context, res: Int): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(context, res)).build()

private fun Track.subtitle(): String =
    listOfNotNull(artist, album).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * Art search text. The album is a better seed than a long track title (and yields the album
 * cover), so prefer it; fall back to the title when the source has no album.
 */
private fun artQuery(artist: String?, album: String?, title: String?): String? =
    listOfNotNull(artist, album?.takeIf { it.isNotBlank() } ?: title)
        .joinToString(" ").trim().take(60).ifBlank { null }

/** Downloads art for the visible items before the host asks for it. */
private fun warmArt(context: Context, urls: List<Pair<String?, String?>>, done: () -> Unit) {
    if (urls.isEmpty()) {
        done()
        return
    }
    val remaining = AtomicInteger(urls.size)
    for ((url, query) in urls) {
        artIo.execute {
            runCatching { Artwork.fileFor(context, url, query, 512) }
            if (remaining.decrementAndGet() == 0) main.post(done)
        }
    }
}

/**
 * Home mirroring what a music app looks like in the car: shelves of what you played last, then
 * tile rows for the current recommendation seed. Same media ids as the browser tree, so playback
 * goes through the exact same queue-building code.
 */
@androidx.annotation.OptIn(markerClass = [androidx.car.app.annotations.ExperimentalCarApi::class])
class HomeScreen(carContext: CarContext) : Screen(carContext) {
    private var lastLoggedAt = 0L

    @Volatile private var recent: List<Track>? = null
    @Volatile private var recommended: List<Track>? = null
    @Volatile private var forYou: MediaBrowse.ForYou? = null
    /** Counts the network lookups still running; the page is drawn from local data meanwhile. */
    private val remoteLeft = AtomicInteger(0)
    private var loading = false
    private var loadStarted = false
    private var tokenTries = 0
    private var emptyRetries = 0
    private var retryScheduled = false

    init {
        // The page used to be built once, when the car screen opened, and never again: a link
        // added or a song started afterwards stayed invisible. Coming back to the front (from
        // search, the player, another app) now reads everything again.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                emptyRetries = 0
                reloadIfIdle()
            }
        })
    }

    /** Re-reads history, library and recommendations, unless a read is already running. */
    private fun reloadIfIdle() {
        if (!loadStarted || remoteLeft.get() > 0) return
        load()
    }

    /**
     * An empty home can change under us (a song started on the phone, a Nebula link saved
     * there). Look again for about a minute; coming back to the front restarts this.
     */
    private fun lookAgainSoon() {
        if (retryScheduled || emptyRetries >= 8) return
        retryScheduled = true
        main.postDelayed({
            retryScheduled = false
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                emptyRetries++
                reloadIfIdle()
            }
        }, 8000)
    }

    override fun onGetTemplate(): Template {
        // Logged once per render (throttled): if the car shows something that is not this
        // template, the log says whether we were asked at all.
        if (System.currentTimeMillis() - lastLoggedAt > 2000) {
            lastLoggedAt = System.currentTimeMillis()
            android.util.Log.i("CabinEQ/CarApp", "HomeScreen.onGetTemplate")
        }
        CarMediaBridge.register(carContext)
        // The session is created asynchronously by EqService; retry a few times, then stop.
        if (!CarMediaBridge.register(carContext) && tokenTries < 6) {
            tokenTries++
            main.postDelayed({ runCatching { invalidate() } }, 700)
        }

        if (!loadStarted) {
            loadStarted = true
            load()
        }

        // Local data (history, library, downloads) draws at once; the artist shelf and the
        // recommendations need the network and are added when they arrive. Waiting for all of
        // it before drawing anything was the slow first launch.
        val rows = recent
        val you = forYou
        val picks = recommended.orEmpty()
        val stillLoading = remoteLeft.get() > 0
        if (rows == null) {
            return MessageTemplate.Builder("Loading your music…")
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()
        }

        // The manifest admits hosts from car API level 7, but the sectioned home needs 8 and its
        // search header 9. Sending a template the host doesn't know fails on the host side, so
        // older hosts get the closest thing they can draw.
        val api = carContext.carAppApiLevel
        if (api < 8) return legacyHome(rows, picks, stillLoading)

        val jamAction = Action.Builder()
            .setTitle(if (JamHost.running) "Jam ●" else "Jam")
            .setIcon(headerIcon(carContext, R.drawable.ic_jam))
            .setOnClickListener { screenManager.push(JamScreen(carContext)) }
            .build()
        val builder = SectionedItemTemplate.Builder()
        if (api >= 9) {
            builder.setSearchHeader(
                SearchHeader.Builder(object : SearchTemplate.SearchCallback {
                    override fun onSearchSubmitted(searchText: String) {
                        if (searchText.isBlank()) return
                        screenManager.push(SearchScreen(carContext, searchText))
                    }
                })
                    .setSearchHint("Songs, albums, artists")
                    .setShowKeyboardByDefault(false)
                    .setStartHeaderAction(Action.APP_ICON)
                    // Action.MEDIA_PLAYBACK below is the host's minimised panel; it is enough
                    // once playback is live (bottom right), so the header keeps a single action
                    // and the search field its full width.
                    .setEndHeaderActions(listOf(jamAction))
                    .build()
            )
        } else {
            builder.setHeader(
                Header.Builder()
                    .setTitle("CabinEQ")
                    .setStartHeaderAction(Action.APP_ICON)
                    .addEndHeaderAction(searchAction())
                    .addEndHeaderAction(jamAction)
                    .build()
            )
        }

        val downloads = dev.alamo3.cabineq.Downloads.collections(carContext)
        val liked = dev.alamo3.cabineq.Library.liked(carContext)
        val playlists = dev.alamo3.cabineq.Library.playlists(carContext).filter { it.tracks.isNotEmpty() }
        // The listener's own things lead: liked songs, playlists, and downloads (which are what
        // plays when the signal drops).
        if (downloads.isNotEmpty() || liked.isNotEmpty() || playlists.isNotEmpty()) {
            builder.addSection(librarySection(liked, playlists, downloads))
        }
        if (rows.isNotEmpty()) builder.addSection(recentSection(rows))
        if (picks.isNotEmpty()) builder.addSection(recommendedSection(picks))
        if (you != null && you.albums.isNotEmpty()) builder.addSection(albumSection(you))

        // A SectionedItemTemplate must have at least one section.
        if (rows.isEmpty() && picks.isEmpty() && (you == null || you.albums.isEmpty()) && downloads.isEmpty() &&
            liked.isEmpty() && playlists.isEmpty()
        ) {
            if (stillLoading) {
                return MessageTemplate.Builder("Loading your music…")
                    .setHeaderAction(Action.APP_ICON)
                    .setLoading(true)
                    .build()
            }
            lookAgainSoon()
            return MessageTemplate.Builder("Nothing to play yet — search for something, or start a song on your phone.")
                .setHeaderAction(Action.APP_ICON)
                .addAction(searchButton())
                .build()
        }
        emptyRetries = 0
        // Required by the MFT-1 quality guideline: a way back to the playback screen from every
        // browse screen. Hosts render this as the minimised control panel (bottom right).
        builder.addAction(Action.MEDIA_PLAYBACK)
        return builder.build()
    }

    /** Car API 7 hosts: one list of recent songs and picks, search and Jam in the header. */
    private fun legacyHome(rows: List<Track>, picks: List<Track>, stillLoading: Boolean = false): Template {
        val items = ItemList.Builder()
        fun add(tracks: List<Track>, label: String) {
            tracks.take(10).forEachIndexed { i, track ->
                items.addItem(
                    Row.Builder()
                        .setTitle(track.title)
                        .addText(listOf(label, track.subtitle()).filter { it.isNotBlank() }.joinToString(" · "))
                        .setImage(carIcon(carContext, track.artworkUrl, artQuery(track.artist, track.album, track.title)))
                        .setOnClickListener {
                            CarPlayback.playTracks(carContext, tracks, i, label, ::openPlayer)
                        }
                        .build()
                )
            }
        }
        add(rows, "Recently played")
        add(picks, "For you")
        if (rows.isEmpty() && picks.isEmpty() && stillLoading) {
            return MessageTemplate.Builder("Loading your music…")
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()
        }
        if (rows.isEmpty() && picks.isEmpty()) {
            lookAgainSoon()
            return MessageTemplate.Builder("Nothing to play yet — search for something, or start a song on your phone.")
                .setHeaderAction(Action.APP_ICON)
                .addAction(searchButton())
                .build()
        }
        return ListTemplate.Builder()
            .setTitle("CabinEQ")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(searchAction())
                    .addAction(
                        Action.Builder()
                            .setTitle(if (JamHost.running) "Jam ●" else "Jam")
                            .setOnClickListener { screenManager.push(JamScreen(carContext)) }
                            .build()
                    )
                    .build()
            )
            .setSingleList(items.build())
            .build()
    }

    private fun load() {
        val ctx = carContext.applicationContext
        remoteLeft.set(2)

        // 1. Local history: a disk read. The page can draw as soon as this is in.
        uiIo.execute {
            val local = runCatching { MediaBrowse.recentTracks(ctx) }.getOrDefault(emptyList())
            recent = local
            refresh()
            // What was played last is what gets tapped next: have its links ready, so it starts at once.
            dev.alamo3.cabineq.TrackResolver.warm(ctx, local.take(3))
            warmArt(ctx, local.take(10).map { it.artworkUrl to artQuery(it.artist, it.album, it.title) }) { refresh() }
        }

        // 2. The artist shelf and 3. the recommendations: network lookups, in parallel. Each one
        // adds its section when it lands; a slow or failing one no longer holds the page back.
        homeIo.execute {
            val content = runCatching { MediaBrowse.forYouContent(ctx) }.getOrNull()
            forYou = content ?: MediaBrowse.ForYou("", emptyList(), emptyList())
            remoteLeft.decrementAndGet()
            enrichRecent()
            refresh()
            val art = ArrayList<Pair<String?, String?>>()
            content?.albums?.take(10)?.forEach { art += it.artworkUrl to artQuery(it.artist, it.title, null) }
            warmArt(ctx, art) { refresh() }
        }
        homeIo.execute {
            val picks = runCatching { Radio.recommend(ctx, 12) }.getOrDefault(emptyList())
            recommended = picks
            remoteLeft.decrementAndGet()
            enrichRecent()
            refresh()
            warmArt(ctx, picks.take(10).map { it.artworkUrl to artQuery(it.artist, it.album, it.title) }) { refresh() }
        }
    }

    private fun refresh() {
        main.post { runCatching { invalidate() } }
    }

    /**
     * The same song can arrive from different places: a played track may only have a stream URL
     * (no cover), while the addon's copy of it carries the real artwork. Reuse the known cover so
     * a song looks the same in every section.
     */
    private fun enrichRecent() {
        val rows = recent ?: return
        val artBySong = HashMap<String, String>()
        forYou?.tracks?.forEach { t -> t.artworkUrl?.let { artBySong[songKey(t.artist, t.title)] = it } }
        forYou?.albums?.forEach { a -> a.artworkUrl?.let { artBySong[songKey(a.artist, a.title)] = it } }
        recommended?.forEach { t -> t.artworkUrl?.let { artBySong[songKey(t.artist, t.title)] = it } }
        if (artBySong.isEmpty()) return
        recent = rows.map { t ->
            if (t.artworkUrl != null) t
            else artBySong[songKey(t.artist, t.title)]?.let { t.copy(artworkUrl = it) } ?: t
        }
    }

    /** Same song = same art, whatever section it shows up in. */
    private fun songKey(artist: String?, title: String?): String =
        "${title?.trim()?.lowercase().orEmpty()}|${artist?.trim()?.lowercase().orEmpty()}"

    /** A titled search button, for places where an icon-only action isn't allowed (message bodies). */
    private fun searchButton(): Action = Action.Builder()
        .setTitle("Search")
        .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
        .build()

    private fun searchAction(): Action = Action.Builder()
        .setIcon(headerIcon(carContext, R.drawable.ic_search))
        .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
        .build()

    private fun librarySection(
        liked: List<Track>,
        playlists: List<dev.alamo3.cabineq.Library.Playlist>,
        downloads: List<dev.alamo3.cabineq.Downloads.Collection>,
    ): GridSection {
        val section = GridSection.Builder()
        section.setTitle("Library")
        section.setItemSize(GridSection.ITEM_SIZE_LARGE)
        fun tile(title: String, count: Int, art: String?, tracks: () -> List<Track>) {
            section.addItem(
                GridItem.Builder()
                    .setTitle(title)
                    .setText("$count songs")
                    .setImage(carIcon(carContext, art, title))
                    .setOnClickListener { screenManager.push(TrackListScreen(carContext, title, art, tracks)) }
                    .build()
            )
        }
        if (liked.isNotEmpty()) {
            tile("Liked songs", liked.size, liked.firstNotNullOfOrNull { it.artworkUrl }) {
                dev.alamo3.cabineq.Library.liked(carContext)
            }
        }
        playlists.take(4).forEach { p ->
            tile(p.name, p.tracks.size, p.tracks.firstNotNullOfOrNull { it.artworkUrl }) {
                dev.alamo3.cabineq.Library.playlist(carContext, p.id)?.tracks.orEmpty()
            }
        }
        downloads.take(5).forEach { c ->
            tile(c.title, c.tracks.size, c.artworkUrl) {
                dev.alamo3.cabineq.Downloads.find(carContext, c.id)?.tracks.orEmpty()
            }
        }
        return section.build()
    }

    private fun recentSection(tracks: List<Track>): GridSection {
        val section = GridSection.Builder()
        section.setTitle("Recently played")
        section.setItemSize(GridSection.ITEM_SIZE_LARGE)
        tracks.take(10).forEachIndexed { i, track ->
            section.addItem(
                GridItem.Builder()
                    .setTitle(track.title)
                    .setText(track.subtitle().ifBlank { "Track" })
                    .setImage(carIcon(carContext, track.artworkUrl, artQuery(track.artist, track.album, track.title)))
                    .setOnClickListener {
                        CarPlayback.playTracks(carContext, tracks, i, "Recently played", ::openPlayer)
                    }
                    .build()
            )
        }
        return section.build()
    }

    /** Song-level picks from the engine: mixed artists, not "more of the same artist". */
    private fun recommendedSection(picks: List<Track>): GridSection {
        val section = GridSection.Builder()
        section.setTitle("For you")
        section.setItemSize(GridSection.ITEM_SIZE_LARGE)
        picks.take(10).forEachIndexed { i, track ->
            section.addItem(
                GridItem.Builder()
                    .setTitle(track.title)
                    .setText(track.subtitle().ifBlank { "Track" })
                    .setImage(carIcon(carContext, track.artworkUrl, artQuery(track.artist, track.album, track.title)))
                    .setOnClickListener {
                        CarPlayback.playTracks(carContext, picks, i, "For you", ::openPlayer)
                    }
                    .build()
            )
        }
        return section.build()
    }

    private fun artistSection(you: MediaBrowse.ForYou): GridSection {
        val section = GridSection.Builder()
        section.setTitle("More from ${you.artist}")
        section.setItemSize(GridSection.ITEM_SIZE_LARGE)
        you.tracks.take(10).forEachIndexed { i, track ->
            section.addItem(
                GridItem.Builder()
                    .setTitle(track.title)
                    .setText(listOfNotNull(track.artist, track.album).joinToString(" · "))
                    .setImage(carIcon(carContext, track.artworkUrl, artQuery(track.artist, track.album, track.title)))
                    .setOnClickListener {
                        CarPlayback.playTracks(
                            carContext,
                            you.tracks.map { it.toTrack() },
                            i,
                            "More from ${you.artist}",
                            ::openPlayer,
                        )
                    }
                    .build()
            )
        }
        return section.build()
    }

    private fun albumSection(you: MediaBrowse.ForYou): GridSection {
        val section = GridSection.Builder()
        section.setTitle("Albums · ${you.artist}")
        section.setItemSize(GridSection.ITEM_SIZE_LARGE)
        you.albums.take(10).forEach { album ->
            section.addItem(
                GridItem.Builder()
                    .setTitle(album.title)
                    .setText(album.artist)
                    .setImage(carIcon(carContext, album.artworkUrl, artQuery(album.artist, album.title, null)))
                    .setOnClickListener {
                        screenManager.push(AlbumScreen(carContext, album.id, album.title))
                    }
                    .build()
            )
        }
        return section.build()
    }
}

/** Liked songs, a playlist or a download, read from the phone: no network involved. */
class TrackListScreen(
    carContext: CarContext,
    private val title: String,
    private val artworkUrl: String?,
    private val load: () -> List<Track>,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val tracks = load()
        if (tracks.isEmpty()) {
            return MessageTemplate.Builder("Nothing here any more.")
                .setHeaderAction(Action.BACK)
                .build()
        }
        val items = ItemList.Builder()
        tracks.take(100).forEachIndexed { i, track ->
            items.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(track.subtitle().ifBlank { title })
                    .setImage(carIcon(carContext, track.artworkUrl ?: artworkUrl, artQuery(track.artist, track.album, track.title)))
                    .setOnClickListener { CarPlayback.playTracks(carContext, tracks, i, title, ::openPlayer) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}

/** One album's tracks; playing a row queues the whole album and starts there. */
class AlbumScreen(
    carContext: CarContext,
    private val albumId: String,
    private val albumTitle: String,
) : Screen(carContext) {

    @Volatile private var tracks: List<EclipseAddon.Track>? = null
    private var loadStarted = false

    override fun onGetTemplate(): Template {
        val list = tracks
        if (!loadStarted) {
            loadStarted = true
            val ctx = carContext.applicationContext
            uiIo.execute {
                val link = Settings(ctx).addonLink
                val loaded = if (link.isBlank()) emptyList() else
                    runCatching { EclipseAddon(link).album(albumId).tracks }.getOrDefault(emptyList())
                warmArt(ctx, loaded.take(30).map { it.artworkUrl to artQuery(it.artist, it.album, it.title) }) {
                    tracks = loaded
                    runCatching { invalidate() }
                }
            }
        }

        // Car App Library rejects an empty ItemList / section, so never build one.
        if (list == null) {
            return MessageTemplate.Builder("Loading $albumTitle…")
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }
        if (list.isEmpty()) {
            return MessageTemplate.Builder("Couldn't load $albumTitle — check the search source in CabinEQ settings.")
                .setHeaderAction(Action.BACK)
                .build()
        }

        val items = ItemList.Builder()
        val queue = list.map { it.toTrack() }
        list.forEachIndexed { i, track ->
            items.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(track.artist)
                    .setImage(carIcon(carContext, track.artworkUrl, artQuery(track.artist, track.album, track.title)))
                    .setOnClickListener {
                        CarPlayback.playTracks(carContext, queue, i, albumTitle, ::openPlayer)
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle(albumTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}

/** In-car search: the host provides the keyboard, we provide results from the addon. */
class SearchScreen(carContext: CarContext, initialQuery: String? = null) : Screen(carContext) {

    private var query: String = initialQuery.orEmpty()
    private var autoSearched = initialQuery.isNullOrBlank()
    @Volatile private var results: EclipseAddon.SearchResults? = null
    @Volatile private var searching = false
    private var lastSubmitted: String? = null

    override fun onGetTemplate(): Template {
        if (!autoSearched) {
            autoSearched = true
            val text = query
            if (text.isNotBlank()) {
                searching = true
                lastSubmitted = text
                val ctx = carContext.applicationContext
                uiIo.execute {
                    val link = Settings(ctx).addonLink
                    val found = if (link.isBlank()) EclipseAddon.SearchResults(emptyList(), emptyList()) else
                        runCatching { dev.alamo3.cabineq.music.SmartSearch.search(ctx, EclipseAddon(link), text).results }
                            .getOrDefault(EclipseAddon.SearchResults(emptyList(), emptyList()))
                    warmArt(ctx, found.tracks.take(20).map { it.artworkUrl to artQuery(it.artist, it.album, it.title) }) {
                        results = found
                        searching = false
                        runCatching { invalidate() }
                    }
                }
            }
        }
        val list = results?.tracks.orEmpty()
        val playlists = results?.playlists.orEmpty()
        val items = ItemList.Builder()
        if (list.isEmpty()) {
            items.addItem(
                Row.Builder()
                    .setTitle(if (searching) "Searching…" else "Search for songs, albums, artists")
                    .build()
            )
        }
        list.take(20).forEachIndexed { i, track ->
            items.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(listOfNotNull(track.artist, track.album).joinToString(" · "))
                    .setImage(carIcon(carContext, track.artworkUrl, artQuery(track.artist, track.album, track.title)))
                    .setOnClickListener {
                        CarPlayback.playSearch(
                            carContext,
                            track.toTrack(),
                            "Search: $query",
                            ::openPlayer,
                        )
                    }
                    .build()
            )
        }
        playlists.take(10).forEach { playlist ->
            items.addItem(
                Row.Builder()
                    .setTitle(playlist.title)
                    .addText(listOfNotNull("Playlist", playlist.trackCount?.let { "$it songs" }).joinToString(" · "))
                    .setImage(carIcon(carContext, playlist.artworkUrl, playlist.title))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(PlaylistScreen(carContext, playlist.id, playlist.title))
                    }
                    .build()
            )
        }

        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isBlank() || searchText == lastSubmitted) return
                lastSubmitted = searchText
                query = searchText
                searching = true
                results = null
                runCatching { invalidate() }
                val ctx = carContext.applicationContext
                uiIo.execute {
                    val link = Settings(ctx).addonLink
                    val found = if (link.isBlank()) EclipseAddon.SearchResults(emptyList(), emptyList()) else
                        runCatching { dev.alamo3.cabineq.music.SmartSearch.search(ctx, EclipseAddon(link), searchText).results }
                            .getOrDefault(EclipseAddon.SearchResults(emptyList(), emptyList()))
                    warmArt(ctx, found.tracks.take(20).map { it.artworkUrl to artQuery(it.artist, it.album, it.title) }) {
                        results = found
                        searching = false
                        runCatching { invalidate() }
                    }
                }
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Songs, albums, artists")
            .setInitialSearchText(query)
            .setShowKeyboardByDefault(results == null)
            .setItemList(items.build())
            .build()
    }
}


/** Open the playback template after a tap, like every other music app in the car. */
private fun Screen.openPlayer() {
    runCatching { screenManager.push(NowPlayingScreen(carContext)) }
}

/** The host-populated now-playing template: media info and controls come from our MediaSession. */
class NowPlayingScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        // A silent failure is the worst outcome in a car: if the last attempt broke, say why
        // instead of showing a media panel that refuses to start.
        val player = dev.alamo3.cabineq.FilePlayer.peek()
        // A pause with a reason (stalled output, focus refused) used to look like a dead panel.
        val reason = dev.alamo3.cabineq.EngineState.playerMessage
        if (player != null && player.state == dev.alamo3.cabineq.FilePlayer.State.PAUSED && reason != null) {
            return MessageTemplate.Builder(reason)
                .setTitle("Playback paused")
                .setHeaderAction(Action.BACK)
                .addAction(
                    Action.Builder()
                        .setTitle("Play")
                        .setOnClickListener {
                            player.resume()
                            runCatching { invalidate() }
                        }
                        .build()
                )
                .build()
        }
        val error = player?.takeIf { it.state == dev.alamo3.cabineq.FilePlayer.State.ERROR }?.lastError
        if (error != null) {
            return MessageTemplate.Builder(error.ifBlank { "Can't play this right now." })
                .setHeaderAction(Action.BACK)
                .setTitle("Playback problem")
                .build()
        }
        // MediaPlaybackTemplate is car API 8; older hosts get a pane with the basic controls.
        if (carContext.carAppApiLevel < 8) return legacyPlaybackTemplate(player)
        return playbackTemplate()
    }

    private fun legacyPlaybackTemplate(player: dev.alamo3.cabineq.FilePlayer?): Template {
        val track = player?.currentTrack
        val playing = player?.state == dev.alamo3.cabineq.FilePlayer.State.PLAYING
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(track?.title ?: "Nothing playing")
                    .apply { track?.subtitle()?.takeIf { it.isNotBlank() }?.let { addText(it) } }
                    .apply {
                        track?.let {
                            setImage(carIcon(carContext, it.artworkUrl, artQuery(it.artist, it.album, it.title)))
                        }
                    }
                    .build()
            )
        if (player != null && track != null) {
            pane.addAction(
                Action.Builder()
                    .setTitle(if (playing) "Pause" else "Play")
                    .setOnClickListener {
                        if (playing) player.pause() else player.resume()
                        runCatching { invalidate() }
                    }
                    .build()
            )
            pane.addAction(
                Action.Builder()
                    .setTitle("Next")
                    .setOnClickListener {
                        player.next()
                        runCatching { invalidate() }
                    }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Now playing")
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Queue")
                            .setOnClickListener { screenManager.push(QueueScreen(carContext)) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun playbackTemplate(): Template =
        androidx.car.app.media.model.MediaPlaybackTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    // Header slots require an icon on every custom action, and allow two of
                    // them: these are the only way into lyrics and the queue from here.
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Lyrics")
                            .setIcon(headerIcon(carContext, R.drawable.ic_lyrics))
                            .setOnClickListener { screenManager.push(LyricsScreen(carContext)) }
                            .build()
                    )
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Queue")
                            .setIcon(headerIcon(carContext, R.drawable.ic_queue))
                            .setOnClickListener { screenManager.push(QueueScreen(carContext)) }
                            .build()
                    )
                    .build()
            )
            .build()
}

/** A playlist from search: same list/play behaviour as an album. */
class PlaylistScreen(
    carContext: CarContext,
    private val playlistId: String,
    private val playlistTitle: String,
) : Screen(carContext) {

    @Volatile private var tracks: List<EclipseAddon.Track>? = null
    private var loadStarted = false

    override fun onGetTemplate(): Template {
        val list = tracks
        if (!loadStarted) {
            loadStarted = true
            val ctx = carContext.applicationContext
            uiIo.execute {
                val link = Settings(ctx).addonLink
                val loaded = if (link.isBlank()) emptyList() else
                    runCatching { EclipseAddon(link).playlist(playlistId).tracks }.getOrDefault(emptyList())
                warmArt(ctx, loaded.take(30).map { it.artworkUrl to artQuery(it.artist, it.album, it.title) }) {
                    tracks = loaded
                    runCatching { invalidate() }
                }
            }
        }
        if (list == null) {
            return MessageTemplate.Builder("Loading $playlistTitle…")
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }
        if (list.isEmpty()) {
            return MessageTemplate.Builder("Couldn't load this playlist — check the search source in CabinEQ settings.")
                .setHeaderAction(Action.BACK)
                .build()
        }
        val items = ItemList.Builder()
        val queue = list.map { it.toTrack() }
        list.forEachIndexed { i, track ->
            items.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(
                        listOfNotNull(track.artist.ifBlank { null }, track.album)
                            .joinToString(" · ").ifBlank { playlistTitle }
                    )
                    .setImage(carIcon(carContext, track.artworkUrl, artQuery(track.artist, track.album, track.title)))
                    .setOnClickListener {
                        CarPlayback.playTracks(carContext, queue, i, playlistTitle, ::openPlayer)
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle(playlistTitle)
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}

/**
 * The jam session as a QR code: guests scan it and add songs from their phones. Starting the
 * session from here is the only way to do it without touching the phone.
 */
class JamScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // A fresh screen means a fresh intent to host, so allow starting again after an end.
        ending = false
    }

    override fun onGetTemplate(): Template {
        val app = carContext.applicationContext
        // Don't resurrect a session we are in the middle of ending (the wipe is async).
        if (!JamHost.running && !ending) JamHost.start(app)
        val sid = JamHost.sessionId
            ?: return MessageTemplate.Builder("Couldn't start a jam session.")
                .setHeaderAction(Action.BACK)
                .build()
        val url = JamHost.sessionUrl(sid)
        val pane = Pane.Builder()
            .setImage(
                CarIcon.Builder(
                    IconCompat.createWithContentUri(ArtworkProvider.qrUri(url, 720))
                ).build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Scan to join the jam")
                    .addText("Friends add songs from their phones — no app, no account")
                    .build()
            )
            // "Done" only leaves the screen: the session is a background loop and the code
            // people already scanned must keep working.
            .addAction(
                Action.Builder()
                    .setTitle("Done")
                    .setOnClickListener { runCatching { screenManager.pop() } }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("End jam")
                    .setOnClickListener { screenManager.push(EndJamScreen(carContext)) }
                    .build()
            )
            .build()
        return PaneTemplate.Builder(pane)
            .setHeaderAction(Action.BACK)
            .build()
    }

    companion object {
        /** Set while the session is being torn down, so a re-render can't start a new one. */
        @Volatile private var ending = false

        fun markEnding() {
            ending = true
        }
    }
}

/** Ending a jam disconnects everyone who scanned it, so it takes a deliberate second tap. */
class EndJamScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("Guests will lose the session and this QR code will stop working.")
            .setTitle("End the jam?")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Keep jam")
                    .setOnClickListener { runCatching { screenManager.pop() } }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("End jam")
                    .setOnClickListener {
                        JamScreen.markEnding()
                        JamHost.stop()
                        // Skip past the QR screen: re-rendering it would start a new session.
                        runCatching { screenManager.popToRoot() }
                    }
                    .build()
            )
            .build()
}

/** Up next, from the player's real queue. */
class QueueScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val player = dev.alamo3.cabineq.FilePlayer.peek()
        val queue = player?.queue.orEmpty()
        val current = player?.currentIndex ?: -1
        if (queue.isEmpty()) {
            return MessageTemplate.Builder("Nothing queued yet.")
                .setHeaderAction(Action.BACK)
                .build()
        }
        val app = carContext.applicationContext
        val now = player?.currentTrack
        val header = Header.Builder().setStartHeaderAction(Action.BACK)
        if (now != null) {
            // The header's end actions are moved into an action strip, which allows two actions
            // but only one with a title: keep the words on the like, use a glyph for the dislike.
            header.addEndHeaderAction(
                Action.Builder().setTitle("Like")
                    .setIcon(headerIcon(carContext, R.drawable.ic_heart))
                    .setOnClickListener { Taste.like(app, now); runCatching { invalidate() } }
                    .build()
            )
            header.addEndHeaderAction(
                Action.Builder()
                    .setIcon(headerIcon(carContext, R.drawable.ic_close))
                    .setOnClickListener { Taste.dislike(app, now); runCatching { invalidate() } }
                    .build()
            )
        }
        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle("Repeat: ${repeatLabel(player)}")
                .addText("Tap to change")
                .setOnClickListener { player?.cycleRepeat(); runCatching { invalidate() } }
                .build()
        )
        val waiting = player?.waitingKeys.orEmpty()
        val keys = player?.queuedTracks()?.map { it.key }.orEmpty()
        queue.forEachIndexed { i, track ->
            val caching = keys.getOrNull(i)?.let { it in waiting } == true
            items.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(listOfNotNull(track.artist, track.album).joinToString(" · "))
                    // Moved down while its source caches it: say so, or it looks lost.
                    .apply { if (caching) addText("⏳ Still being prepared — other songs go first") }
                    .setImage(carIcon(carContext, track.artworkUrl, artQuery(track.artist, track.album, track.title)))
                    .setBrowsable(i == current)
                    .setOnClickListener { CarPlayback.playTracks(carContext, queue, i, "Up next") }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Up next")
            .setHeader(header.build())
            .setSingleList(items.build())
            .build()
    }

    private fun repeatLabel(player: dev.alamo3.cabineq.FilePlayer?): String = when (player?.repeat) {
        dev.alamo3.cabineq.FilePlayer.Repeat.ALL -> "all"
        dev.alamo3.cabineq.FilePlayer.Repeat.ONE -> "one"
        else -> "off"
    }
}
