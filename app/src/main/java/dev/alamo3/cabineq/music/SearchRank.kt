package dev.alamo3.cabineq.music

import dev.alamo3.cabineq.addon.EclipseAddon
import kotlin.math.log10
import kotlin.math.min

/**
 * The ranking half of search: no network, no Android. Given what the user typed, what a
 * typo-tolerant index thinks they meant ([Hit]s) and whatever the addon returned for a handful
 * of queries, decide what goes on top.
 */
object SearchRank {

    /** One reading of the query: a real song, with how popular it is (0..1). */
    data class Hit(val title: String, val artist: String, val popularity: Float = 0f)

    enum class Lead { SONGS, ARTIST, ALBUM }

    /** Versions that are a different listen from the studio track. */
    private val VARIANTS = setOf(
        "live", "remix", "remixed", "mix", "karaoke", "instrumental", "acoustic", "cover", "tribute",
        "rehearsal", "demo", "sped", "slowed", "reverb", "nightcore", "8d", "lullaby", "piano", "workout",
    )
    /** In an album title these mean every track on it is a variant. */
    private val VARIANT_ALBUMS = setOf("live", "karaoke", "tribute", "lullaby", "instrumental")

    /** The decoration of a title: what is in brackets or after " - ". "Piano Man" has none. */
    private fun decoration(title: String): List<String> {
        val parts = Regex("""[(\[]([^)\]]*)[)\]]""").findAll(title).map { it.groupValues[1] }.toMutableList()
        val dash = title.indexOf(" - ")
        if (dash >= 0) parts += title.substring(dash + 3)
        return parts.flatMap { SongKey.words(it) }
    }

    private val STOP = setOf("the", "a", "an", "by", "of", "and", "feat", "ft", "song", "songs", "music")

    /** Query words that carry meaning ("the" only when that is all there is). */
    fun queryWords(q: String): List<String> {
        val all = SongKey.words(q)
        return all.filter { it !in STOP }.ifEmpty { all }
    }

    // ------------------------------------------------------------------ fuzzy words

    /** Same word, allowing a typo or two: 1 edit from 4 letters, 2 from 8. */
    fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val n = min(a.length, b.length)
        val allowed = when {
            n >= 8 -> 2
            n >= 4 -> 1
            else -> 0
        }
        if (allowed > 0 && kotlin.math.abs(a.length - b.length) <= allowed &&
            editDistance(a, b, allowed) <= allowed
        ) return true
        // Spoken queries: "beyonsay" is nowhere near "beyonce" in letters, but sounds the same.
        return Phonetic.sameWord(a, b)
    }

    /** Damerau-Levenshtein (adjacent swaps count once: "teh"), cut off above [max]. */
    internal fun editDistance(a: String, b: String, max: Int): Int {
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Is query word [q] in [words]? The last word of an as-you-type query may be unfinished, so
     * it also matches as a prefix.
     */
    private fun found(q: String, words: Collection<String>, prefixOk: Boolean): Boolean =
        words.any { w -> same(q, w) || (prefixOk && q.length >= 2 && w.startsWith(q)) }

    /** Fraction of the query's words found in [words]. */
    private fun coverage(query: List<String>, words: Collection<String>, typing: Boolean): Float {
        if (query.isEmpty()) return 0f
        var n = 0
        query.forEachIndexed { i, q -> if (found(q, words, typing && i == query.lastIndex)) n++ }
        return n.toFloat() / query.size
    }

    /** Same words both ways (typos allowed): "the weekend" is "The Weeknd". */
    fun sameName(a: String, b: String): Boolean {
        val x = queryWords(a)
        val y = queryWords(b)
        if (x.isNotEmpty() && y.isNotEmpty() &&
            x.all { w -> y.any { same(w, it) } } && y.all { w -> x.any { same(w, it) } }
        ) return true
        // The recogniser splits words where it likes: "day ft punk" is "Daft Punk". Compared
        // whole, small words included — they are part of the sound.
        return Phonetic.sameName(a, b)
    }

    // ------------------------------------------------------------------ songs

    class Scored<T>(val item: T, val score: Float)

    /**
     * @param hits      what the index thinks the query means, best first
     * @param affinity  how much the listener likes an artist (roughly -4..+4), 0 when unknown
     * @param typing    the last query word may be incomplete
     */
    fun scoreTrack(
        query: String,
        t: EclipseAddon.Track,
        position: Int,
        hits: List<Hit>,
        affinity: (String) -> Float = { 0f },
        typing: Boolean = false,
    ): Float {
        val q = queryWords(query)
        val title = SongKey.words(SongKey.plainTitle(t.title))
        val fullTitle = SongKey.words(t.title)
        val artist = SongKey.words(t.artist)
        val album = SongKey.words(t.album)

        val inTitle = coverage(q, fullTitle, typing)
        val inArtist = coverage(q, artist, typing)
        val anywhere = coverage(q, fullTitle + artist, typing)
        val withAlbum = coverage(q, fullTitle + artist + album, typing)

        // Is this one of the songs the index says was meant?
        val key = SongKey.of(t.title, t.artist)
        val hitIndex = hits.indexOfFirst { SongKey.of(it.title, it.artist) == key }

        // Shares too little with the query and isn't a known reading of it: not a result.
        if (hitIndex < 0 && anywhere < 0.5f && withAlbum < 0.75f) return Float.NEGATIVE_INFINITY

        var s = 3f * anywhere + 1f * inTitle + 0.4f * inArtist + 0.3f * (withAlbum - anywhere)
        // Precision: "Time" for the query "time" beats "Time After Time".
        if (title.isNotEmpty()) {
            val used = title.count { w -> q.any { same(it, w) } }
            s += 0.8f * used / title.size
            // The title is exactly the query, or the query minus the artist's name.
            val rest = q.filterNot { w -> artist.any { same(w, it) } }
            if (sameWords(title, q) || (rest.isNotEmpty() && rest.size < q.size && sameWords(title, rest))) s += 1.2f
        }
        if (hitIndex >= 0) s += 2.5f - 0.4f * min(hitIndex, 5) + hits[hitIndex].popularity
        // Live / remix / karaoke only when asked for.
        val odd = decoration(t.title).any { it in VARIANTS && it !in q } ||
            album.any { it in VARIANT_ALBUMS && it !in q }
        if (odd) s -= 2f
        // Asked for by name ("… live", "… remix"): that version leads, the studio cut follows.
        val asked = q.filter { it in VARIANTS }
        if (asked.isNotEmpty() && title.none { it in asked }) {
            s += if (asked.all { it in decoration(t.title) || it in album }) 3f else -1f
        }
        if ((artist).any { it in setOf("karaoke", "tribute", "kidz") && it !in q }) s -= 2f
        // Listeners mostly search for what they already like.
        s += (affinity(t.artist) * 0.15f).coerceIn(-0.5f, 0.6f)
        // The addon's own order (popularity) breaks ties.
        s -= 0.01f * min(position, 50)
        return s
    }

    /** Fraction of the query's words found in the track's title and artist. */
    fun covers(query: String, t: EclipseAddon.Track): Float =
        coverage(queryWords(query), SongKey.words(t.title) + SongKey.words(t.artist), false)

    private fun sameWords(a: List<String>, b: List<String>): Boolean {
        val x = a.filter { it !in STOP }.ifEmpty { a }
        val y = b.filter { it !in STOP }.ifEmpty { b }
        return x.size == y.size && x.all { w -> y.any { same(w, it) } }
    }

    /**
     * Best first, one row per recording: the album cut, the single and the remaster are the same
     * song. Within a group the lossless copy is kept.
     */
    fun rankTracks(
        query: String,
        tracks: List<EclipseAddon.Track>,
        hits: List<Hit>,
        affinity: (String) -> Float = { 0f },
        typing: Boolean = false,
    ): List<EclipseAddon.Track> {
        val scored = tracks.mapIndexed { i, t -> Scored(t, scoreTrack(query, t, i, hits, affinity, typing)) }
            .filter { it.score > Float.NEGATIVE_INFINITY }
            .sortedByDescending { it.score }
        val groups = LinkedHashMap<String, MutableList<EclipseAddon.Track>>()
        for (s in scored) {
            val t = s.item
            // Variant words stay in the key, so the live take doesn't hide behind the studio one.
            val key = t.isrc?.lowercase()?.takeIf { it.isNotBlank() } ?: SongKey.of(t.title, t.artist)
            groups.getOrPut(key) { ArrayList() }.add(t)
        }
        // A second pass by name: the same song with and without an ISRC must still be one row.
        val byName = LinkedHashMap<String, MutableList<EclipseAddon.Track>>()
        for (g in groups.values) byName.getOrPut(SongKey.of(g[0].title, g[0].artist)) { ArrayList() }.addAll(g)
        return byName.values.map { g -> g.firstOrNull { isLossless(it) } ?: g[0] }
    }

    private fun isLossless(t: EclipseAddon.Track): Boolean =
        t.format?.lowercase()?.let { "flac" in it || "alac" in it || "lossless" in it || "wav" in it } == true

    // ------------------------------------------------------------------ albums, artists

    fun rankAlbums(query: String, albums: List<EclipseAddon.Album>, typing: Boolean = false): List<EclipseAddon.Album> {
        val q = queryWords(query)
        return albums.mapIndexed { i, a ->
            val title = SongKey.words(a.title)
            val artist = SongKey.words(a.artist)
            var s = 2f * coverage(q, title + artist, typing) + coverage(q, title, typing)
            if (sameWords(title, q)) s += 1f
            if (title.any { it in setOf("karaoke", "tribute") && it !in q }) s -= 2f
            Scored(a, s - 0.01f * i)
        }.filter { it.score >= 1f }.sortedByDescending { it.score }.map { it.item }
            .distinctBy { SongKey.of(it.title, it.artist) }
    }

    fun rankArtists(query: String, artists: List<EclipseAddon.Artist>, typing: Boolean = false): List<EclipseAddon.Artist> {
        val q = queryWords(query)
        return artists.mapIndexed { i, a ->
            val name = SongKey.words(a.name)
            var s = coverage(q, name, typing)
            if (sameName(a.name, query)) s += 2f
            Scored(a, s - 0.01f * i)
        }.filter { it.score > 0.3f }.sortedByDescending { it.score }.map { it.item }
            .distinctBy { SongKey.artist(it.name) }
    }

    // ------------------------------------------------------------------ intent

    /**
     * What the query is asking for. An artist's name leads with the artist; an album's title
     * with the album; anything else is a song search.
     */
    fun lead(
        query: String,
        tracks: List<EclipseAddon.Track>,
        albums: List<EclipseAddon.Album>,
        artists: List<EclipseAddon.Artist>,
        artistHits: List<String>,
    ): Lead {
        val q = queryWords(query)
        val wantsAlbum = "album" in SongKey.words(query)
        val bare = if (wantsAlbum) query.replace(Regex("(?i)\\balbum\\b"), " ") else query
        val topArtist = artists.firstOrNull()
        val songNamedLikeThis = tracks.take(3).any { sameWords(SongKey.words(SongKey.plainTitle(it.title)), q) }
        if (!wantsAlbum && topArtist != null && sameName(topArtist.name, query)) {
            // "Hello" is a song first, even if some band is called Hello — unless the index
            // itself reads the query as an artist.
            val indexAgrees = artistHits.firstOrNull()?.let { sameName(it, query) } == true
            if (!songNamedLikeThis || indexAgrees) return Lead.ARTIST
        }
        val topAlbum = albums.firstOrNull()
        if (topAlbum != null) {
            val titleOnly = sameWords(SongKey.words(topAlbum.title), queryWords(bare))
            val withArtist = sameWords(SongKey.words(topAlbum.title) + SongKey.words(topAlbum.artist), queryWords(bare))
            if ((titleOnly || withArtist) && (wantsAlbum || !songNamedLikeThis)) return Lead.ALBUM
        }
        return Lead.SONGS
    }

    /**
     * "Showing results for …": what the query was read as, when that isn't what was typed
     * (a typo was fixed). Null when the top result contains the query as written.
     */
    fun correction(query: String, lead: Lead, topTrack: EclipseAddon.Track?, topArtist: EclipseAddon.Artist?): String? {
        val q = queryWords(query)
        val (label, words) = when (lead) {
            Lead.ARTIST -> (topArtist?.name ?: return null) to SongKey.words(topArtist.name)
            else -> {
                val t = topTrack ?: return null
                "${SongKey.plainTitle(t.title)} — ${t.artist.split(',').first().trim()}" to
                    SongKey.words(t.title) + SongKey.words(t.artist)
            }
        }
        val exact = q.all { it in words }
        val fuzzy = q.all { w -> words.any { same(w, it) } }
        return if (!exact && fuzzy) label else null
    }

    /** Deezer's rank (0..~1,000,000) on a 0..1 scale. */
    fun popularity(rank: Long): Float = if (rank <= 1) 0f else (log10(rank.toDouble()) / 6.0).toFloat().coerceIn(0f, 1f)
}
