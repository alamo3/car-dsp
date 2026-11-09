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

    @Volatile private var recent: List<Track>? = null
    @Volatile private var recommended: List<Track>? = null
    @Volatile private var forYou: MediaBrowse.ForYou? = null
    private var loading = false
    private var loadStarted = false
    private var tokenTries = 0

    override fun onGetTemplate(): Template {
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

        val rows = recent
        val you = forYou
        val picks = recommended
        if (rows == null || you == null || picks == null) {
            return MessageTemplate.Builder("Loading your music…")
                .setHeaderAction(Action.APP_ICON)
                .setLoading(true)
                .build()
        }

        val builder = SectionedItemTemplate.Builder()
            .setSearchHeader(
                SearchHeader.Builder(object : SearchTemplate.SearchCallback {
                    override fun onSearchSubmitted(searchText: String) {
                        if (searchText.isBlank()) return
                        screenManager.push(SearchScreen(carContext, searchText))
                    }
                })
                    .setSearchHint("Songs, albums, artists")
                    .setShowKeyboardByDefault(false)
                    .setStartHeaderAction(Action.APP_ICON)
                    .setEndHeaderActions(
                        listOf(
                            Action.Builder()
                                .setTitle(if (JamHost.running) "Jam ●" else "Jam")
                                .setOnClickListener { screenManager.push(JamScreen(carContext)) }
                                .build()
                        )
                    )
                    .build()
            )

        if (rows.isNotEmpty()) builder.addSection(recentSection(rows))
        if (picks.isNotEmpty()) builder.addSection(recommendedSection(picks))
        if (you.albums.isNotEmpty()) builder.addSection(albumSection(you))

        // A SectionedItemTemplate must have at least one section.
        if (rows.isEmpty() && picks.isEmpty() && you.albums.isEmpty()) {
            return MessageTemplate.Builder("Nothing to play yet — search for something, or start a song on your phone.")
                .setHeaderAction(searchAction())
                .build()
        }
        // Required by the MFT-1 quality guideline: a way back to the playback screen from every
        // browse screen. Hosts render this as the minimised control panel (bottom right).
        builder.addAction(Action.MEDIA_PLAYBACK)
        return builder.build()
    }

    private fun load() {
        val ctx = carContext.applicationContext
        uiIo.execute {
            val recentRaw = runCatching { MediaBrowse.recentTracks(ctx) }.getOrDefault(emptyList())
            val content = runCatching { MediaBrowse.forYouContent(ctx) }.getOrNull()
            val picks = runCatching { Radio.recommend(ctx, 12) }.getOrDefault(emptyList())

            // The same song can arrive from different places: a played track may only have a
            // stream URL (no cover), while the addon's copy of it carries the real artwork.
            // Reuse the known cover so a song looks the same in every section.
            val artBySong = HashMap<String, String>()
            content?.tracks?.forEach { t ->
                t.artworkUrl?.let { artBySong[songKey(t.artist, t.title)] = it }
            }
            content?.albums?.forEach { a ->
                a.artworkUrl?.let { artBySong[songKey(a.artist, a.title)] = it }
            }
            picks.forEach { t ->
                t.artworkUrl?.let { artBySong[songKey(t.artist, t.title)] = it }
            }
            val recent = recentRaw.map { t ->
                if (t.artworkUrl != null) t
                else artBySong[songKey(t.artist, t.title)]?.let { t.copy(artworkUrl = it) } ?: t
            }

            // Warm the art cache for everything we are about to show, then publish.
            val art = ArrayList<Pair<String?, String?>>(32)
            recent.take(10).forEach { art += it.artworkUrl to artQuery(it.artist, it.album, it.title) }
            picks.take(10).forEach { art += it.artworkUrl to artQuery(it.artist, it.album, it.title) }
            content?.albums?.take(10)?.forEach { art += it.artworkUrl to artQuery(it.artist, it.title, null) }
            warmArt(ctx, art) {
                this.recent = recent
                this.recommended = picks
                forYou = content
                loading = false
                runCatching { invalidate() }
            }
        }
    }

    /** Same song = same art, whatever section it shows up in. */
    private fun songKey(artist: String?, title: String?): String =
        "${title?.trim()?.lowercase().orEmpty()}|${artist?.trim()?.lowercase().orEmpty()}"

    private fun searchAction(): Action = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_search)).build())
        .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
        .build()

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
                        runCatching { EclipseAddon(link).search(text) }
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
                        runCatching { EclipseAddon(link).search(searchText) }
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
        return playbackTemplate()
    }

    private fun playbackTemplate(): Template =
        androidx.car.app.media.model.MediaPlaybackTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Queue")
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
            // The media playback template is host-drawn, so these live here, one tap away.
            header.addEndHeaderAction(
                Action.Builder().setTitle("♡")
                    .setOnClickListener { Taste.like(app, now); runCatching { invalidate() } }
                    .build()
            )
            header.addEndHeaderAction(
                Action.Builder().setTitle("✕")
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
        queue.forEachIndexed { i, track ->
            items.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(listOfNotNull(track.artist, track.album).joinToString(" · "))
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
