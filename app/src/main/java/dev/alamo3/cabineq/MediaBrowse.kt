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
    /** "Library": liked songs, the listener's playlists, downloads and the TorBox account. */
    private const val MY = "my"
    private const val LIKED = "liked"
    private const val USER_PLAYLIST = "upl:"
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
        parentId == MY -> myLibrary(context)
        parentId == LIKED -> trackItems(LIKED, Library.liked(context), null)
        parentId.startsWith(USER_PLAYLIST) ->
            trackItems(parentId, Library.playlist(context, parentId.removePrefix(USER_PLAYLIST))?.tracks.orEmpty(), null)
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
        // One tab for everything that is the listener's own (hosts show four tabs at most).
        if (myLibrary(context).isNotEmpty()) {
            nodes += item(MY, "Library", "Liked songs, playlists, downloads", browsable = true)
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
            parent == LIKED -> listPlayback("Liked songs", Library.liked(context), child)
            parent.startsWith(USER_PLAYLIST) -> {
                val p = Library.playlist(context, parent.removePrefix(USER_PLAYLIST)) ?: return null
                listPlayback(p.name, p.tracks, child)
            }
            parent.startsWith(DOWNLOAD) -> {
                val c = Downloads.find(context, parent.removePrefix(DOWNLOAD)) ?: return null
                val index = c.tracks.indexOfFirst { it.mediaId == Uri.decode(child) }
                Playback(c.title, c.tracks, index.coerceAtLeast(0))
            }
            else -> null
        }
    }

    /** "Play <something>" as plain text (the phone's search intent, older callers). */
    fun searchPlayback(context: Context, query: String): Playback? =
        voicePlayback(context, dev.alamo3.cabineq.music.VoiceRequest(query))

    /**
     * "Play …" from the assistant. Tries the assistant's own fields first (artist, title, album),
     * then the transcript, then what the transcript may have been meant as, and plays the first
     * reading that finds what it was looking for. Nothing named at all means "play some music".
     */
    fun voicePlayback(context: Context, request: dev.alamo3.cabineq.music.VoiceRequest): Playback? {
        if (request.isOpenEnded) return somethingToPlay(context)
        // The listener's own things by name: "play my liked songs", "play <my playlist>".
        ownCollection(context, request)?.let { return it }
        val addon = addon(context) ?: return somethingToPlay(context)
        val known = runCatching { Taste.profile(context).knownArtists() }.getOrDefault(emptyList())
        var fallback: Playback? = null
        for (attempt in request.attempts(known).take(4)) {
            val found = runCatching { smartSearch(context, addon, attempt.text) }.getOrNull() ?: continue
            val play = playbackFor(context, addon, attempt, found) ?: continue
            if (confident(attempt, found)) {
                Log.i(TAG, "voice: \"${request.query.take(40)}\" played as \"${attempt.text.take(40)}\" (${attempt.expect})")
                return play
            }
            if (fallback == null) fallback = play
        }
        return fallback
    }

    /** Did this reading find the thing it names, rather than merely something? */
    private fun confident(
        attempt: dev.alamo3.cabineq.music.VoiceRequest.Attempt,
        found: dev.alamo3.cabineq.music.SmartSearch.Result,
    ): Boolean {
        val rank = dev.alamo3.cabineq.music.SearchRank
        return when (found.lead) {
            dev.alamo3.cabineq.music.SearchRank.Lead.ARTIST, dev.alamo3.cabineq.music.SearchRank.Lead.ALBUM -> true
            else -> found.results.tracks.firstOrNull()?.let { rank.covers(attempt.text, it) >= 0.75f } == true
        }
    }

    private fun playbackFor(
        context: Context,
        addon: EclipseAddon,
        attempt: dev.alamo3.cabineq.music.VoiceRequest.Attempt,
        found: dev.alamo3.cabineq.music.SmartSearch.Result,
    ): Playback? {
        val rank = dev.alamo3.cabineq.music.SearchRank
        val tracks = found.results.tracks.take(MAX_ROWS).map { it.toTrack() }
        // The assistant said what it is: believe that over our own guess when the results agree.
        val artist = found.results.artists.firstOrNull()
        val album = found.results.albums.firstOrNull()
        val lead = when {
            attempt.expect == dev.alamo3.cabineq.music.VoiceRequest.Focus.ARTIST && artist != null && rank.sameName(artist.name, attempt.text) -> dev.alamo3.cabineq.music.SearchRank.Lead.ARTIST
            attempt.expect == dev.alamo3.cabineq.music.VoiceRequest.Focus.ALBUM && album != null -> dev.alamo3.cabineq.music.SearchRank.Lead.ALBUM
            attempt.expect == dev.alamo3.cabineq.music.VoiceRequest.Focus.SONG && tracks.isNotEmpty() -> dev.alamo3.cabineq.music.SearchRank.Lead.SONGS
            else -> found.lead
        }
        return when (lead) {
            // "Play Daft Punk": their top songs, in order, then radio from them.
            dev.alamo3.cabineq.music.SearchRank.Lead.ARTIST -> {
                val name = artist?.name ?: return null
                val key = dev.alamo3.cabineq.music.SongKey.artist(name)
                val top = runCatching { addon.artist(artist.id)?.topTracks }.getOrNull().orEmpty().map { it.toTrack() }
                val theirs = (top + tracks.filter { dev.alamo3.cabineq.music.SongKey.artist(it.artist) == key })
                    .distinctBy { Taste.key(it) }.ifEmpty { tracks }
                if (theirs.isEmpty()) null else Playback(name, theirs, 0, radio = theirs.last())
            }
            // "Play the album …": the album itself.
            dev.alamo3.cabineq.music.SearchRank.Lead.ALBUM -> {
                val al = album ?: return null
                val albumTracks = runCatching { addon.album(al.id) }.getOrNull()?.tracks?.map { it.toTrack() }.orEmpty()
                when {
                    albumTracks.isNotEmpty() -> Playback(al.title, albumTracks, 0)
                    tracks.isNotEmpty() -> Playback("Search: ${attempt.text}", listOf(tracks[0]), 0, radio = tracks[0])
                    else -> null
                }
            }
            // "Play <song>": that song, then radio from it — not twenty lookalikes.
            else -> tracks.firstOrNull()?.let { Playback("Search: ${attempt.text}", listOf(it), 0, radio = it) }
        }
    }

    /** "Play my liked songs / my downloads / <playlist name>". */
    private fun ownCollection(context: Context, request: dev.alamo3.cabineq.music.VoiceRequest): Playback? {
        val rank = dev.alamo3.cabineq.music.SearchRank
        val asked = dev.alamo3.cabineq.music.VoiceRequest.clean(request.playlist ?: request.query)
            .replace(Regex("(?i)^my\\s+"), "").replace(Regex("(?i)\\s+playlist$"), "").trim()
        if (asked.isBlank()) return null
        val words = dev.alamo3.cabineq.music.SongKey.simplify(asked)
        if (words in setOf("liked songs", "likes", "liked", "favorites", "favourites", "liked music", "favorite songs")) {
            val liked = Library.liked(context)
            if (liked.isNotEmpty()) return Playback("Liked songs", liked.shuffled(), 0)
        }
        if (words in setOf("downloads", "downloaded music", "offline music", "downloaded songs")) {
            val all = Downloads.collections(context).flatMap { it.tracks }.distinctBy { it.mediaId }
                .filter { Downloads.fileFor(context, it) != null }
            if (all.isNotEmpty()) return Playback("Downloads", all.shuffled(), 0)
        }
        // A playlist of the listener's own wins over a catalogue search for the same words —
        // always when the assistant said "playlist", otherwise only on a full name match.
        val wantsPlaylist = request.focus == dev.alamo3.cabineq.music.VoiceRequest.Focus.PLAYLIST ||
            Regex("(?i)\\bplaylist\\b").containsMatchIn(request.query)
        Library.playlists(context).filter { it.tracks.isNotEmpty() }
            .firstOrNull { rank.sameName(it.name, asked) }
            ?.let { return Playback(it.name, it.tracks, 0) }
        if (wantsPlaylist) {
            Downloads.collections(context).firstOrNull { rank.sameName(it.title, asked) }
                ?.let { return Playback(it.title, it.tracks, 0) }
        }
        return null
    }

    /**
     * "Play some music": the listener's own favourites, shuffled, with the radio carrying on
     * after them. Falls back to what was played recently, then to whatever is downloaded, so it
     * also works with no signal.
     */
    private fun somethingToPlay(context: Context): Playback? {
        val liked = Library.liked(context)
        if (liked.size >= 5) {
            val mix = liked.shuffled().take(30)
            return Playback("Your mix", mix, 0, radio = mix.last().takeIf { it.sourceId != null })
        }
        val recent = recentTracks(context)
        if (recent.isNotEmpty()) {
            val mix = (liked + recent).distinctBy { Taste.key(it) }.shuffled().take(30)
            return Playback("Your mix", mix, 0, radio = mix.last().takeIf { it.sourceId != null })
        }
        val offline = Downloads.collections(context).flatMap { it.tracks }.distinctBy { it.mediaId }
        return if (offline.isEmpty()) null else Playback("Downloads", offline.shuffled(), 0)
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

    private fun myLibrary(context: Context): List<MediaBrowser.MediaItem> {
        val nodes = ArrayList<MediaBrowser.MediaItem>()
        val liked = Library.liked(context)
        if (liked.isNotEmpty()) nodes += item(LIKED, "Liked songs", "${liked.size} songs", browsable = true)
        if (Downloads.collections(context).isNotEmpty()) {
            nodes += item(DOWNLOADS, "Downloads", "On this phone — plays offline", browsable = true)
        }
        Library.playlists(context).filter { it.tracks.isNotEmpty() }.take(30).forEach { p ->
            nodes += item(USER_PLAYLIST + p.id, p.name, "${p.tracks.size} songs", browsable = true)
        }
        if (Settings(context).torboxApiKey.isNotBlank()) {
            nodes += item(LIBRARY, "My TorBox", "Albums on your TorBox account", browsable = true)
        }
        return nodes
    }

    private fun trackItems(parentId: String, tracks: List<Track>, art: String?): List<MediaBrowser.MediaItem> =
        tracks.take(MAX_ROWS).map { t ->
            item(
                id = "$parentId#" + Uri.encode(t.mediaId),
                title = t.title,
                subtitle = listOfNotNull(t.artist, t.album).joinToString(" · "),
                iconUrl = t.artworkUrl ?: art,
                fallbackQuery = "${t.artist.orEmpty()} ${t.title}".trim(),
                durationMs = t.durationMs,
                playable = true,
            )
        }

    private fun listPlayback(title: String, tracks: List<Track>, child: String): Playback? {
        if (tracks.isEmpty()) return null
        val index = tracks.indexOfFirst { it.mediaId == Uri.decode(child) }
        return Playback(title, tracks, index.coerceAtLeast(0))
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
