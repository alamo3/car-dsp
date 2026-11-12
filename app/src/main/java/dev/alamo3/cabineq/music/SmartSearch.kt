package dev.alamo3.cabineq.music

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.alamo3.cabineq.Lyrics
import dev.alamo3.cabineq.Taste
import dev.alamo3.cabineq.Track
import dev.alamo3.cabineq.addon.EclipseAddon
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Search as the user means it, for the phone, the car and "play X" voice requests alike.
 *
 * The addon matches the whole string it is given, in its own order, and forgives nothing. So:
 *
 *  1. understand the query with a typo-tolerant index ([Discover], Deezer's public search):
 *     "blinding lights weekend" is *Blinding Lights* by The Weeknd;
 *  2. ask the addon for the raw text and for those readings, in parallel;
 *  3. rank and de-duplicate everything here ([SearchRank]): match quality, popularity, studio
 *     versions first, the listener's own taste as a nudge;
 *  4. decide what leads: an artist's name opens with the artist and their top songs;
 *  5. when nothing fits, try the text as a line of lyrics.
 *
 * If the index is unreachable this degrades to the addon's own results, ranked.
 */
object SmartSearch {
    private const val TAG = "CabinEQ/Search"
    private const val INDEX_WAIT_MS = 3500L
    private const val CACHE_MS = 2 * 60_000L
    private const val LIMIT = 20

    class Result(
        val results: EclipseAddon.SearchResults,
        val lead: SearchRank.Lead,
        /** "Showing results for …" when a typo was fixed; null otherwise. */
        val showingFor: String?,
        /** The top songs were found by their lyrics. */
        val fromLyrics: Boolean = false,
    ) {
        val leadArtist: EclipseAddon.Artist? get() = results.artists.firstOrNull()?.takeIf { lead == SearchRank.Lead.ARTIST }
    }

    private val pool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "CabinEQ-Search").apply { isDaemon = true }
    }

    private class Cached(val at: Long, val result: Result)
    private val cache = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 40
    }

    /**
     * Blocking. [typing] is the as-you-type path: one extra addon request at most, no artist
     * page, no lyrics, and the last word may be unfinished.
     *
     * Throws what the addon throws when the addon itself can't be reached.
     */
    fun search(context: Context?, addon: EclipseAddon, query: String, typing: Boolean = false): Result {
        val q = query.trim()
        val empty = EclipseAddon.SearchResults(emptyList(), emptyList())
        if (q.isBlank()) return Result(empty, SearchRank.Lead.SONGS, null)
        val cacheKey = "${addon.baseUrl.hashCode()}|$typing|${q.lowercase()}"
        val now = SystemClock.elapsedRealtime()
        synchronized(cache) { cache[cacheKey]?.takeIf { now - it.at < CACHE_MS }?.let { return it.result } }

        // A name the listener plays, misheard or misspelt beyond a typo ("beyonsay"): search
        // for the real one as well.
        val profile = context?.let { runCatching { Taste.profile(it) }.getOrNull() }
        val soundsLike = profile?.let { Phonetic.correct(q, it.knownArtists()) }

        // 1 + 2a: the index and the raw addon search, together.
        val trackHitsF = pool.submit(Callable { Discover.searchTracks(q, 8) })
        val artistHitsF = pool.submit(Callable { Discover.searchArtists(q, 3) })
        val rawF = pool.submit(Callable { addon.search(q, LIMIT, deep = false) })

        val trackHits = await(trackHitsF, INDEX_WAIT_MS).orEmpty()
        val artistHits = listOfNotNull(soundsLike) + await(artistHitsF, 500).orEmpty()
        val hits = trackHits.map { SearchRank.Hit(it.title, it.artist, SearchRank.popularity(it.rank)) }

        // 2b: the readings of the query, as the addon likes them ("title artist", exact).
        val asked = SongKey.simplify(q)
        val extra = LinkedHashSet<String>()
        // Only readings that share words with the query: the index returns *something* for
        // anything, and an unrelated top hit must not hijack the search.
        hits.filter { hit ->
            val words = SongKey.words(hit.title) + SongKey.words(hit.artist)
            val qw = SearchRank.queryWords(q)
            qw.count { w -> words.any { SearchRank.same(w, it) || (typing && it.startsWith(w)) } } * 2 >= qw.size
        }.take(if (typing) 1 else 2).forEach { hit ->
            val text = "${SongKey.plainTitle(hit.title)} ${hit.artist.split(',').first().trim()}"
            if (SongKey.simplify(text) != asked) extra += text
        }
        artistHits.firstOrNull()?.takeIf { SearchRank.sameName(it, q) && SongKey.simplify(it) != asked }
            ?.let { if (!typing || extra.isEmpty()) extra += it }
        val extraF = extra.map { text -> pool.submit(Callable { addon.search(text, LIMIT, deep = false) }) }

        val raw = try {
            rawF.get(30, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
        val more = extraF.mapNotNull { await(it, 30_000) }
        val all = listOf(raw) + more

        // 3: one ranking over everything.
        val affinity: (String) -> Float = { artist -> profile?.artistScore(artist) ?: 0f }
        // Readings first, so that when the same song came back twice the cleaner copy is seen.
        val mergedTracks = (more.flatMap { it.tracks } + raw.tracks).distinctBy { it.id }
        var tracks = SearchRank.rankTracks(q, mergedTracks, hits, affinity, typing)
        if (tracks.isEmpty()) tracks = raw.tracks          // nothing survived: the addon's own answer
        val albums = SearchRank.rankAlbums(q, all.flatMap { it.albums }.distinctBy { it.id }, typing)
            .ifEmpty { raw.albums.takeIf { tracks === raw.tracks }.orEmpty() }
        val artists = SearchRank.rankArtists(q, all.flatMap { it.artists }.distinctBy { it.id }, typing)
        val playlists = all.flatMap { it.playlists }.distinctBy { it.id }

        // 4: what is this query asking for?
        val lead = SearchRank.lead(q, tracks, albums, artists, artistHits, trackHits.firstOrNull()?.artist)
        if (lead == SearchRank.Lead.ARTIST) {
            val artist = artists.first()
            val theirs = { t: EclipseAddon.Track -> SongKey.artist(t.artist) == SongKey.artist(artist.name) }
            val top = if (typing) emptyList() else
                runCatching { addon.artist(artist.id)?.topTracks }.getOrNull().orEmpty()
            // Their best-known songs, in the addon's (popularity) order, then everything else.
            tracks = (top + tracks.filter(theirs) + tracks.filterNot(theirs))
                .distinctBy { SongKey.of(it.title, it.artist) }
        }

        // 5: nothing fits what was typed — maybe it is a line from the song.
        var fromLyrics = false
        val words = SearchRank.queryWords(q)
        val weak = tracks.isEmpty() || SearchRank.covers(q, tracks.first()) < 0.75f
        if (!typing && weak && words.size >= 4 && lead == SearchRank.Lead.SONGS) {
            val found = Lyrics.searchText(q, 3).mapNotNull { hit ->
                val wanted = Track(
                    title = hit.title, artist = hit.artist, durationMs = hit.durationSec * 1000L,
                    source = Track.Source.ADDON,
                )
                runCatching { AddonMatch.find(addon, wanted) }.getOrNull()
            }.distinctBy { it.id }
            if (found.isNotEmpty()) {
                fromLyrics = true
                tracks = (found + tracks).distinctBy { it.id }
            }
        }

        val showingFor = if (fromLyrics) null else
            SearchRank.correction(q, lead, tracks.firstOrNull(), artists.firstOrNull())
        Log.i(TAG, "\"${q.take(40)}\": ${hits.size} readings, ${extra.size} extra queries, " +
            "${mergedTracks.size} -> ${tracks.size} songs, lead=$lead" +
            (if (fromLyrics) ", by lyrics" else "") + (if (showingFor != null) ", corrected" else ""))
        // Head start: asking for the top song's stream makes the source begin preparing it
        // (TorBox caching) while the listener is still reading the results. Nothing is
        // downloaded; a tap a few seconds later finds it ready or well on its way.
        if (!typing) tracks.firstOrNull()?.let { top ->
            pool.execute { runCatching { addon.streamOnce(top.id) } }
        }
        val result = Result(EclipseAddon.SearchResults(tracks, albums, artists, playlists), lead, showingFor, fromLyrics)
        synchronized(cache) { cache[cacheKey] = Cached(SystemClock.elapsedRealtime(), result) }
        return result
    }

    private fun <T> await(f: Future<T>, ms: Long): T? =
        runCatching { f.get(ms, TimeUnit.MILLISECONDS) }.getOrNull()

    // ------------------------------------------------------------------ local suggestions

    /**
     * Instant, offline: songs from the listener's own history that match what is being typed.
     * Shown while the network search is still running.
     */
    fun suggest(query: String, history: List<Track>, limit: Int = 4): List<Track> {
        val q = SearchRank.queryWords(query)
        if (q.isEmpty()) return emptyList()
        return history.asSequence()
            .distinctBy { SongKey.of(it.title, it.artist) }
            .filter { t ->
                val words = SongKey.words(t.title) + SongKey.words(t.artist)
                q.withIndex().all { (i, w) ->
                    words.any { SearchRank.same(w, it) || (i == q.lastIndex && it.startsWith(w)) }
                }
            }
            .take(limit).toList()
    }
}
