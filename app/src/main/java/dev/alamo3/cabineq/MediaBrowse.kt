package dev.alamo3.cabineq

import android.content.Context
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media.utils.MediaConstants
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.torbox.AudioPicker
import dev.alamo3.cabineq.torbox.TorBoxClient

/**
 * Browse tree for Android Auto and Android Automotive.
 *
 * Every media id carries what is needed to rebuild its queue, so a client that reconnects (or a
 * process that was restarted) does not depend on a cache:
 *
 *   root                 · My TorBox and Recently played
 *   trb:<torrentId>      · the tracks of one TorBox torrent      (playable children)
 *   alb:<albumId>        · the tracks of one addon album         (playable children)
 *   q:<index>            · an entry of the last queue             (playable, no children)
 *
 * A playable child's id is "<parent>#<child>", e.g. "trb:42#9137", so selecting a track queues
 * its whole parent and starts there — what a car expects when you tap a song on an album.
 * Without the parent, the car could only ever play a queue of one.
 */
object MediaBrowse {
    private const val TAG = "CabinEQ/Browse"

    const val ROOT = "root"
    private const val LIBRARY = "library"
    private const val RECENT = "recent"
    private const val FOR_YOU = "foryou"
    private const val TORRENT = "trb:"
    private const val ALBUM = "alb:"
    private const val PLAYLIST = "pl:"
    private const val SEARCH = "src:"
    private const val SEARCH_NODE = "searches"
    private const val SAVED_SEARCH = "srch:"
    private const val RECENT_ITEM = "q:"
    private const val DOWNLOADS = "downloads"
    private const val DOWNLOAD = "dl:"

    private const val ART_PX = 512
    private class Memo(val at: Long, val items: List<MediaBrowser.MediaItem>)
    private val memo = HashMap<String, Memo>()
    private const val FOR_YOU_TTL_MS = 60_000L
    /** The car re-binds tabs often; re-fetching the TorBox/addon lists each time is slow. */
    private const val MEMO_TTL_MS = 45_000L
    private const val MAX_ROWS = 100

    /** 2 == MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM. */
    private val GRID = MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM

    /**
     * What a browse selection should play: a queue and where in it to start. [radio], when set,
     * lets the player keep that artist going one track at a time.
     */
    class Playback(
        val title: String?,
        val tracks: List<Track>,
        val index: Int,
        val radio: Track? = null,
    )

    // ------------------------------------------------------------------ tree

    fun children(context: Context, parentId: String): List<MediaBrowser.MediaItem> = when {
        parentId == ROOT -> rootChildren(context)
        parentId == LIBRARY -> memoized(parentId) { torrents(context) }
        parentId == RECENT -> recent(context)
        parentId == FOR_YOU -> forYou(context)
        parentId == SEARCH_NODE -> savedSearches(context)
        parentId == DOWNLOADS -> downloads(context)
        parentId.startsWith(DOWNLOAD) -> downloadTracks(context, parentId)
        parentId.startsWith(TORRENT) -> memoized(parentId) { torrentTracks(context, parentId) }
        parentId.startsWith(ALBUM) -> memoized(parentId) { albumTracks(context, parentId) }
        parentId.startsWith(PLAYLIST) -> memoized(parentId) { playlistTracks(context, parentId) }
        else -> emptyList()
    }

    /** Only the network-backed lists are memoised: they change rarely and cost 1–2 requests. */
    private fun memoized(
        parentId: String,
        compute: () -> List<MediaBrowser.MediaItem>,
    ): List<MediaBrowser.MediaItem> {
        val now = SystemClock.elapsedRealtime()
        synchronized(memo) {
            memo[parentId]?.let { if (now - it.at < MEMO_TTL_MS) return it.items }
        }
        val items = compute()
        synchronized(memo) { memo[parentId] = Memo(now, items) }
        return items
    }

    /**
     * Car search (Android Auto and Android Automotive both call the media browser's search).
     * Same catalog as the phone's search box: songs directly, albums to browse into.
     */
    fun search(context: Context, query: String): List<MediaBrowser.MediaItem> {
        if (query.isBlank()) return emptyList()
        val addon = addon(context) ?: return emptyList()
        val results = runCatching { smartSearch(context, addon, query).results }.getOrElse {
            Log.w(TAG, "Search \"$query\" failed: ${it.message}")
            return emptyList()
        }
        val parentId = SEARCH + Uri.encode(query)
        val albums = results.albums.take(20).map { albumItem(it) }
        val albumsFirst = smartSearch(context, addon, query).lead == dev.alamo3.cabineq.music.SearchRank.Lead.ALBUM
        val songs = results.tracks.take(MAX_ROWS).map { track ->
            item(
                // The id, not the position: results are re-fetched when the car asks again and a
                // shifted list used to play whatever now sat at that index.
                id = "$parentId#id:" + Uri.encode(track.id),
                title = track.title,
                subtitle = listOfNotNull(track.artist, track.album).filter { it.isNotBlank() }
                    .joinToString(" · "),
                iconUrl = track.artworkUrl,
                fallbackQuery = "${track.artist} ${track.title}".trim(),
                durationMs = (track.durationSec ?: 0) * 1000L,
                playable = true,
                style = GRID,
            )
        }
        val playlists = results.playlists.take(20).map { playlistItem(it) }
        // Songs first unless the query named an album: the top row is what was asked for.
        return if (albumsFirst) albums + songs + playlists else songs + albums + playlists
    }

    /**
     * Only sources that actually exist. An empty "My TorBox" tab on a phone with no API key is
     * worse than no tab at all — and in the car every root child is a tab the driver can hit.
     */
    private fun rootChildren(context: Context): List<MediaBrowser.MediaItem> {
        val settings = Settings(context)
        val hasAddon = settings.addonLink.isNotBlank()
        val hasHistory = settings.playHistory().isNotEmpty()
        val nodes = ArrayList<MediaBrowser.MediaItem>(4)
        // First: the one tab that works in a dead zone.
        if (Downloads.collections(context).isNotEmpty()) {
            nodes += item(DOWNLOADS, "Downloads", "On this phone — plays offline", browsable = true)
        }
        if (hasAddon && hasHistory) {
            nodes += item(FOR_YOU, "For you", "More from the artists you play", browsable = true)
        }
        if (hasHistory || settings.queueSnapshot != null) {
            nodes += item(RECENT, "Recently played", "Pick up where you left off", browsable = true)
        }
        // A car has no keyboard: give it the searches that already worked, one tap away.
        if (hasAddon && settings.recentSearches.isNotEmpty()) {
            nodes += item(SEARCH_NODE, "Search", "Your recent searches", browsable = true)
        }
        if (settings.torboxApiKey.isNotBlank()) {
            nodes += item(LIBRARY, "My TorBox", "Albums on your TorBox account", browsable = true)
        }
        return nodes
    }

    /**
     * Resolves a selection (and a "play this" from the voice assistant) to a queue. Returns null
     * when the id is unknown or its source is no longer configured.
     */
    fun playback(context: Context, mediaId: String): Playback? {
        if (mediaId.startsWith(RECENT_ITEM)) {
            val child = mediaId.removePrefix(RECENT_ITEM)
            val tracks = recentTracks(context)
            if (tracks.isEmpty()) return null
            val index = child.toIntOrNull()
                ?: tracks.indexOfFirst { it.mediaId == Uri.decode(child) }.takeIf { it >= 0 }
                ?: return null
            return Playback(FilePlayer.peek()?.queueTitle, tracks, index.coerceIn(0, tracks.lastIndex))
        }
        val parent = mediaId.substringBefore('#')
        val child = mediaId.substringAfter('#', "")
        return when {
            parent == FOR_YOU -> forYouPlayback(context, child)
            parent.startsWith(SAVED_SEARCH) ->
                searchPlayback(context, Uri.decode(parent.removePrefix(SAVED_SEARCH)), "s0")
            parent.startsWith(SEARCH) -> searchPlayback(context, Uri.decode(parent.removePrefix(SEARCH)), child)
            parent.startsWith(TORRENT) -> torrentPlayback(context, parent, child)
            parent.startsWith(ALBUM) -> albumPlayback(context, parent, child)
            parent.startsWith(PLAYLIST) -> playlistPlayback(context, parent, child)
            parent.startsWith(DOWNLOAD) -> {
                val c = Downloads.find(context, parent.removePrefix(DOWNLOAD)) ?: return null
                val index = c.tracks.indexOfFirst { it.mediaId == Uri.decode(child) }
                Playback(c.title, c.tracks, index.coerceAtLeast(0))
            }
            else -> null
        }
    }

    /** "play <something>" from the car's assistant: queue all matches, start at the first. */
    fun searchPlayback(context: Context, query: String): Playback? {
        val addon = addon(context) ?: return null
        val found = smartSearch(context, addon, query)
        val tracks = found.results.tracks.take(MAX_ROWS).map { it.toTrack() }
        if (tracks.isEmpty()) return null
        return when (found.lead) {
            // "Play Daft Punk": their top songs, in order.
            dev.alamo3.cabineq.music.SearchRank.Lead.ARTIST -> {
                val artist = found.results.artists.first().name
                val theirs = tracks.filter {
                    dev.alamo3.cabineq.music.SongKey.artist(it.artist) == dev.alamo3.cabineq.music.SongKey.artist(artist)
                }.ifEmpty { tracks }
                Playback(artist, theirs, 0)
            }
            // "Play the album …": the album itself.
            dev.alamo3.cabineq.music.SearchRank.Lead.ALBUM -> {
                val album = found.results.albums.first()
                val detail = runCatching { addon.album(album.id) }.getOrNull()
                val albumTracks = detail?.tracks?.map { it.toTrack() }.orEmpty()
                if (albumTracks.isNotEmpty()) Playback(album.title, albumTracks, 0)
                else Playback("Search: $query", listOf(tracks[0]), 0, radio = tracks[0])
            }
            // "Play <song>": that song, then radio from it — not twenty lookalikes.
            else -> Playback("Search: $query", listOf(tracks[0]), 0, radio = tracks[0])
        }
    }

    // ------------------------------------------------------------------ children

    private fun torrents(context: Context): List<MediaBrowser.MediaItem> {
        val client = torbox(context) ?: return emptyList()
        val format = Settings(context).torboxFormat
        return client.listTorrents()
            .filterNot { it.isReady && AudioPicker.isVideoTorrent(it.files) }
            .take(MAX_ROWS)
            .mapNotNull { torrent ->
                val pick = AudioPicker.pick(torrent.files, format).takeIf { it.tracks.isNotEmpty() }
                    ?: AudioPicker.pick(torrent.files)
                if (pick.tracks.isEmpty()) return@mapNotNull null
                val name = MusicActivity.cleanTorrentName(torrent.name)
                val meta = if (!torrent.isReady) "${(torrent.progress * 100).toInt()}% on TorBox"
                else "${pick.tracks.size} songs · ${pick.format?.uppercase()}"
                item(
                    id = TORRENT + torrent.id,
                    title = name,
                    subtitle = meta,
                    fallbackQuery = MusicActivity.coverQuery(torrent.name),
                    browsable = true,
                    style = GRID,
                )
            }
    }

    private fun torrentTracks(context: Context, parentId: String): List<MediaBrowser.MediaItem> {
        val torrentId = parentId.removePrefix(TORRENT).toIntOrNull() ?: return emptyList()
        val torrent = torbox(context)?.getTorrent(torrentId) ?: return emptyList()
        val pick = AudioPicker.pick(torrent.files, Settings(context).torboxFormat).takeIf { it.tracks.isNotEmpty() }
            ?: AudioPicker.pick(torrent.files)
        val title = MusicActivity.cleanTorrentName(torrent.name)
        return pick.tracks.take(MAX_ROWS).map { file ->
            item(
                id = "$parentId#${file.id}",
                title = AudioPicker.trackTitle(file),
                subtitle = title,
                fallbackQuery = MusicActivity.coverQuery(torrent.name),
                playable = true,
            )
        }
    }

    private fun albumTracks(context: Context, parentId: String): List<MediaBrowser.MediaItem> {
        val addon = addon(context) ?: return emptyList()
        val albumId = parentId.removePrefix(ALBUM)
        val detail = addon.album(albumId)
        val album = detail.album
        val title = listOf(album.artist, album.title).filter { it.isNotBlank() }.joinToString(" — ")
        return detail.tracks.take(MAX_ROWS).map { track ->
            item(
                id = "$parentId#${track.id}",
                title = track.title,
                subtitle = track.artist.ifBlank { title },
                iconUrl = track.artworkUrl ?: album.artworkUrl,
                fallbackQuery = "${track.artist} ${track.title}".trim().ifBlank { title },
                durationMs = (track.durationSec ?: 0) * 1000L,
                playable = true,
            )
        }
    }

    private fun recent(context: Context): List<MediaBrowser.MediaItem> {
        val tracks = recentTracks(context)
        if (tracks.isEmpty()) return emptyList()
        return tracks.take(MAX_ROWS).map { track ->
            item(
                // Content id, not position: history changes between listing and tapping.
                id = RECENT_ITEM + Uri.encode(track.mediaId),
                title = track.title,
                subtitle = track.artist ?: track.album,
                iconUrl = track.artworkUrl,
                fallbackQuery = listOfNotNull(track.artist, track.title).joinToString(" "),
                durationMs = track.durationMs,
                playable = true,
                style = GRID,
            )
        }
    }

    // ------------------------------------------------------------------ for you

    /** What "For you" is built from: the last thing that played, turned into a queue. */
    internal class ForYou(
        val artist: String,
        val tracks: List<EclipseAddon.Track>,
        val albums: List<EclipseAddon.Album>,
    )

    /**
     * The car's home screen. There is no server-side recommendation feed (the addon's catalog
     * list is empty), so this is "more from the artist you last played": their top tracks plus
     * their albums. It is the row you actually want to tap while driving.
     */
    private fun forYou(context: Context): List<MediaBrowser.MediaItem> {
        val content = forYouContent(context) ?: return emptyList()
        val rows = ArrayList<MediaBrowser.MediaItem>(content.tracks.size + content.albums.size)
        content.tracks.forEach { track ->
            rows += item(
                id = "$FOR_YOU#id:" + Uri.encode(track.id),
                title = track.title,
                subtitle = listOfNotNull(track.artist, track.album).filter { it.isNotBlank() }
                    .joinToString(" · "),
                iconUrl = track.artworkUrl,
                fallbackQuery = "${track.artist} ${track.title}".trim(),
                durationMs = (track.durationSec ?: 0) * 1000L,
                playable = true,
                style = GRID,
            )
        }
        content.albums.forEach { rows += albumItem(it) }
        return rows
    }

    private fun forYouPlayback(context: Context, child: String): Playback? {
        val content = forYouContent(context) ?: return null
        if (content.tracks.isEmpty()) return null
        val tracks = content.tracks.map { it.toTrack() }
        val index = indexOfChild(tracks, child)
        if (index < 0) return null
        return Playback("For you · ${content.artist}", tracks, index)
    }

    @Volatile private var forYouCache: ForYou? = null
    private var forYouCacheAt = 0L

    /**
     * Two network calls (search + artist) per evaluation, and the car asks for this tab every
     * time the app opens — so memoise briefly instead of hitting the addon on every refresh.
     */
    internal fun forYouContent(context: Context): ForYou? {
        val now = SystemClock.elapsedRealtime()
        forYouCache?.let { if (now - forYouCacheAt < FOR_YOU_TTL_MS) return it }
        val built = buildForYou(context) ?: return null
        forYouCache = built
        forYouCacheAt = now
        return built
    }

    private fun buildForYou(context: Context): ForYou? {
        val history = Settings(context).playHistory()
        // Artists come from the catalog as "Daft Punk, Julian Casablancas" or "X feat. Y";
        // search only works well with the primary one, and the most-played artist lately is a
        // better seed than whatever played 30 seconds ago.
        val artist = history.mapNotNull { primaryArtist(it.artist) }
            .groupingBy { it }.eachCount()
            .entries.maxByOrNull { it.value }?.key ?: return null
        val addon = addon(context) ?: return null
        val results = runCatching { addon.search(artist) }.getOrNull() ?: return null
        val match = results.artists.firstOrNull { it.name.equals(artist, ignoreCase = true) }
        val detail = match?.let { runCatching { addon.artist(it.id) }.getOrNull() }
        val tracks = (detail?.topTracks?.takeIf { it.isNotEmpty() }
            ?: results.tracks.filter { it.artist.contains(artist, ignoreCase = true) }).take(30)
        val albums = (detail?.albums
            ?: results.albums.filter { it.artist.contains(artist, ignoreCase = true) }).take(12)
        if (tracks.isEmpty() && albums.isEmpty()) return null
        // Order the rows by what this listener actually finishes, not by the addon's popularity.
        val profile = Taste.profile(context)
        val ranked = tracks.sortedByDescending { profile.score(it.toTrack()) }
        return ForYou(artist, ranked, albums)
    }

    /** "Daft Punk, Julian Casablancas" / "Daft Punk feat. X" -> "Daft Punk". */
    private fun primaryArtist(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val head = raw
            .split(',').first()
            .split(Regex("(?i)\\s+feat\\.?\\s+")).first()
            .split('&').first()
            .trim()
        return head.ifBlank { raw.trim() }
    }

    private fun playlistItem(playlist: EclipseAddon.Playlist): MediaBrowser.MediaItem = item(
        id = PLAYLIST + playlist.id,
        title = playlist.title,
        subtitle = listOfNotNull(
            "Playlist",
            playlist.trackCount?.let { "$it songs" },
        ).joinToString(" · "),
        iconUrl = playlist.artworkUrl,
        fallbackQuery = playlist.title,
        browsable = true,
        style = GRID,
    )

    private fun albumItem(album: EclipseAddon.Album): MediaBrowser.MediaItem = item(
        id = ALBUM + album.id,
        title = album.title,
        subtitle = album.artist,
        iconUrl = album.artworkUrl,
        fallbackQuery = "${album.artist} ${album.title}".trim(),
        browsable = true,
        style = GRID,
    )

    /** Past searches as one-tap queues — the closest a car gets to a search box. */
    private fun savedSearches(context: Context): List<MediaBrowser.MediaItem> {
        return Settings(context).recentSearches.take(8).map { query ->
            item(
                id = SAVED_SEARCH + Uri.encode(query),
                title = query,
                subtitle = "Search · plays the top result",
                playable = true,
            )
        }
    }

    // ------------------------------------------------------------------ playback

    private fun torrentPlayback(context: Context, parentId: String, child: String): Playback? {
        val torrentId = parentId.removePrefix(TORRENT).toIntOrNull() ?: return null
        val torrent = torbox(context)?.getTorrent(torrentId) ?: return null
        val fileId = child.toIntOrNull() ?: return null
        val title = MusicActivity.cleanTorrentName(torrent.name)

        fun queueOf(pick: AudioPicker.Pick) =
            pick.tracks.map { fileTrack(title, torrentId, it.id, AudioPicker.trackTitle(it)) }

        val filtered = AudioPicker.pick(torrent.files, Settings(context).torboxFormat)
            .takeIf { it.tracks.isNotEmpty() } ?: AudioPicker.pick(torrent.files)
        var tracks = queueOf(filtered)
        var index = tracks.indexOfFirst { it.fileId == fileId }
        if (index < 0) {
            // The row was rendered under a different format filter (or the file list changed):
            // play the file that was actually tapped rather than the first one we happen to see.
            val all = AudioPicker.pick(torrent.files)
            tracks = queueOf(all)
            index = tracks.indexOfFirst { it.fileId == fileId }
        }
        if (index < 0) return null   // unknown file: better silence than the wrong song
        return Playback(title, tracks, index)
    }

    private fun albumPlayback(context: Context, parentId: String, child: String): Playback? {
        val addon = addon(context) ?: return null
        val detail = addon.album(parentId.removePrefix(ALBUM))
        val tracks = detail.tracks.map { it.toTrack() }
        if (tracks.isEmpty()) return null
        val title = listOf(detail.album.artist, detail.album.title)
            .filter { it.isNotBlank() }.joinToString(" — ")
        val index = tracks.indexOfFirst { it.sourceId == child }
        if (child.isNotBlank() && index < 0) return null
        return Playback(title, tracks, index.coerceAtLeast(0))
    }

    private fun playlistTracks(context: Context, parentId: String): List<MediaBrowser.MediaItem> {
        val addon = addon(context) ?: return emptyList()
        val detail = runCatching { addon.playlist(parentId.removePrefix(PLAYLIST)) }.getOrNull()
            ?: return emptyList()
        return detail.tracks.take(MAX_ROWS).map { track ->
            item(
                id = "$parentId#${track.id}",
                title = track.title,
                subtitle = listOfNotNull(track.artist.ifBlank { null }, track.album)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { detail.playlist.title },
                iconUrl = track.artworkUrl,
                fallbackQuery = "${track.artist} ${track.title}".trim(),
                durationMs = (track.durationSec ?: 0) * 1000L,
                playable = true,
            )
        }
    }

    private fun playlistPlayback(context: Context, parentId: String, child: String): Playback? {
        val addon = addon(context) ?: return null
        val detail = runCatching { addon.playlist(parentId.removePrefix(PLAYLIST)) }.getOrNull() ?: return null
        val tracks = detail.tracks.map { it.toTrack() }
        if (tracks.isEmpty()) return null
        val index = tracks.indexOfFirst { it.sourceId == child }
        if (child.isNotBlank() && index < 0) return null
        return Playback(detail.playlist.title, tracks, index.coerceAtLeast(0))
    }

    private fun searchPlayback(context: Context, query: String, child: String): Playback? {
        val addon = addon(context) ?: return null
        val tracks = smartSearch(context, addon, query).results.tracks.take(MAX_ROWS).map { it.toTrack() }
        if (tracks.isEmpty()) return null
        val index = indexOfChild(tracks, child)
        if (index < 0) return null
        // A result list is not a queue: play the one song that was asked for and let the radio
        // top the queue up one track at a time as it drains.
        val tapped = tracks[index]
        return Playback("Search: $query", listOf(tapped), 0, radio = tapped)
    }

    /**
     * Resolves a "#…" child id to an index. ids are "id:<catalogue id>" for new rows and the old
     * positional forms ("s3", "t3") for anything a client cached from a previous version.
     */
    private fun indexOfChild(tracks: List<Track>, child: String): Int {
        if (child.startsWith("id:")) {
            val wanted = Uri.decode(child.removePrefix("id:"))
            return tracks.indexOfFirst { it.sourceId == wanted }
        }
        val n = child.removePrefix("s").removePrefix("t").toIntOrNull() ?: return 0
        return if (n in tracks.indices) n else 0
    }

    // ------------------------------------------------------------------ helpers

    internal fun recentTracks(context: Context): List<Track> {
        val settings = Settings(context)
        // History first (most recent play wins), then whatever is queued but not played yet.
        // Using only history loses the queue you just built; using only the queue loses
        // everything from previous sessions.
        val history = settings.playHistory()
        val queued = FilePlayer.peek()?.queue?.takeIf { it.isNotEmpty() }
            ?: settings.queueSnapshot?.tracks.orEmpty()
        return (history + queued)
            // Same recording can arrive as an album track, a single, a remaster… ISRC is the
            // only reliable identity; fall back to the title/artist pair when it is missing.
            .distinctBy { it.isrc ?: "${it.title.trim().lowercase()}|${it.artist?.trim()?.lowercase().orEmpty()}" }
            .take(MAX_ROWS)
    }

    private fun downloads(context: Context): List<MediaBrowser.MediaItem> =
        Downloads.collections(context).map { c ->
            val st = Downloads.status(context, c)
            item(
                id = DOWNLOAD + c.id,
                title = c.title,
                subtitle = if (st.complete) "${st.total} songs" else "${st.done} of ${st.total} downloaded",
                iconUrl = c.artworkUrl,
                fallbackQuery = c.title,
                browsable = true,
                style = GRID,
            )
        }

    private fun downloadTracks(context: Context, parentId: String): List<MediaBrowser.MediaItem> {
        val c = Downloads.find(context, parentId.removePrefix(DOWNLOAD)) ?: return emptyList()
        return c.tracks.map { t ->
            item(
                id = "$parentId#" + Uri.encode(t.mediaId),
                title = t.title,
                subtitle = listOfNotNull(t.artist, t.album).joinToString(" · "),
                iconUrl = t.artworkUrl ?: c.artworkUrl,
                fallbackQuery = "${t.artist.orEmpty()} ${t.title}".trim(),
                durationMs = t.durationMs,
                playable = true,
            )
        }
    }

    /** The same search the phone uses (cached for two minutes, so a tap re-reads the same list). */
    private fun smartSearch(context: Context, addon: EclipseAddon, query: String) =
        dev.alamo3.cabineq.music.SmartSearch.search(context.applicationContext, addon, query)

    private fun addon(context: Context): EclipseAddon? {
        val link = Settings(context).addonLink
        if (link.isBlank()) return null
        return runCatching { EclipseAddon(link) }.getOrNull()
    }

    private fun torbox(context: Context): TorBoxClient? {
        val key = Settings(context).torboxApiKey
        if (key.isBlank()) return null
        return runCatching { TorBoxClient(key) }.getOrNull()
    }

    private fun fileTrack(album: String, torrentId: Int, fileId: Int, title: String): Track =
        Track(
            title = title,
            album = album,
            source = Track.Source.TORBOX,
            torrentId = torrentId,
            fileId = fileId,
        )

    private fun item(
        id: String,
        title: String,
        subtitle: String? = null,
        iconUrl: String? = null,
        fallbackQuery: String? = null,
        durationMs: Long = 0,
        browsable: Boolean = false,
        playable: Boolean = false,
        style: Int? = null,
    ): MediaBrowser.MediaItem {
        val extras = Bundle().apply {
            if (durationMs > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            // AA's content-style hints (only honoured after CONTENT_STYLE_SUPPORTED): "GRID"
            // renders square tiles with big art, "LIST" the compact rows.
            if (style != null) {
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
                putInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
            }
        }
        val description = MediaDescription.Builder()
            .setMediaId(id)
            .setTitle(title.ifBlank { "CabinEQ" })
            .setSubtitle(subtitle)
            .setIconUri(artUri(iconUrl, fallbackQuery))
            .setExtras(extras)
            .build()
        var flags = 0
        if (browsable) flags = flags or MediaBrowser.MediaItem.FLAG_BROWSABLE
        if (playable) flags = flags or MediaBrowser.MediaItem.FLAG_PLAYABLE
        return MediaBrowser.MediaItem(description, flags)
    }

    /**
     * The car opens icons itself, in its own process, so they have to go through our provider.
     * A null URI is fine: the car shows a placeholder.
     */
    private fun artUri(url: String?, fallbackQuery: String?): Uri? =
        ArtworkProvider.uriFor(url = url, query = fallbackQuery, sizePx = ART_PX)
}
