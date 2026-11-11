package dev.alamo3.cabineq

import dev.alamo3.cabineq.addon.EclipseAddon
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Playlist import: turn a file exported from another service into taste.
 *
 * The big services do not expose their playlists to other apps (their MediaBrowserServices are
 * not exported), so the practical route is an export from a transfer tool — Soundiiz,
 * TuneMyMusic, MusConv — or Exportify for Spotify. Those all produce CSV, JSON or plain text,
 * which is what this parses.
 *
 * Parsing is deliberately tolerant: a known header row wins, but a file with no header still
 * works as "Artist - Title" lines. Matching is the honest part — it reports what it could not
 * find instead of quietly seeding half a playlist.
 */
object Playlists {

    /** One imported row, before it is matched against the catalogue. */
    data class Row(
        val artist: String?,
        val title: String,
        val album: String?,
        val isrc: String?,
        /** The playlist this row belongs to, when the file names one (TuneMyMusic does). */
        val playlist: String? = null,
    )

    /** Rows plus how many entries were left out because they are not songs (channel subscriptions). */
    data class Parsed(val rows: List<Row>, val skipped: Int)

    data class Import(
        val rows: List<Row>,
        val matched: List<Track>,
        val matchedRows: Int,
        val withIsrc: Int,
        val source: String,
        /** Matched songs grouped by the playlist they came from (the file's name when it has none). */
        val byPlaylist: Map<String, List<Track>> = emptyMap(),
        val skipped: Int = 0,
    ) {
        val unmatched: Int get() = rows.size - matchedRows
    }

    // ----------------------------------------------------------------------------- parsing

    /** Detects JSON, delimited text with a header, or plain "Artist - Title" lines. */
    fun parse(fileName: String, text: String): List<Row> = parseFile(fileName, text).rows

    fun parseFile(fileName: String, text: String): Parsed {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.isEmpty()) return Parsed(emptyList(), 0)
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            jsonRows(trimmed)?.let { if (it.isNotEmpty()) return Parsed(it, 0) }
        }
        delimitedRows(trimmed)?.let { if (it.rows.isNotEmpty() || it.skipped > 0) return it }
        return Parsed(lines(trimmed), 0)
    }

    /** Source label for the report: the file name, trimmed and prefixed. */
    fun sourceName(fileName: String?): String =
        fileName?.substringBeforeLast('/')?.substringAfterLast('/')?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() } ?: "Pasted list"

    /** Spotify's "Download your data" JSON and most JSON exports share this shape. */
    private fun jsonRows(text: String): List<Row>? {
        val root = runCatching { if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text) }
            .getOrNull() ?: return null
        val out = ArrayList<Row>()
        fun track(obj: JSONObject) {
            val t = obj.optJSONObject("track") ?: obj
            val title = t.optString("trackName").ifBlank { t.optString("name") }
                .ifBlank { t.optString("title") }
            if (title.isBlank()) return
            val artist = t.optString("artistName").ifBlank {
                t.optJSONArray("artists")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optString("name") }
                        .filter { it.isNotBlank() }.joinToString(", ")
                } ?: ""
            }.ifBlank { t.optString("artist") }
            val album = t.optString("albumName").ifBlank { t.optString("album") }
            val isrc = t.optString("isrc").ifBlank {
                t.optJSONObject("external_ids")?.optString("isrc") ?: ""
            }
            out += Row(artist.ifBlank { null }?.take(120), title.take(200), album.ifBlank { null }?.take(120),
                normalizeIsrc(isrc))
        }
        when (root) {
            is JSONObject -> {
                root.optJSONArray("playlists")?.let { pl ->
                    for (i in 0 until pl.length()) {
                        val items = pl.optJSONObject(i)?.optJSONArray("items") ?: continue
                        for (j in 0 until items.length()) items.optJSONObject(j)?.let { track(it) }
                    }
                }
                root.optJSONArray("items")?.let { items ->
                    for (j in 0 until items.length()) items.optJSONObject(j)?.let { track(it) }
                }
                root.optJSONArray("tracks")?.let { items ->
                    for (j in 0 until items.length()) items.optJSONObject(j)?.let { track(it) }
                }
            }
            is JSONArray -> for (i in 0 until root.length()) root.optJSONObject(i)?.let { track(it) }
        }
        return out.distinctBy { key(it.artist, it.title) }
    }

    /** CSV/TSV with a header row naming the columns (Exportify, Soundiiz, TuneMyMusic…). */
    private fun delimitedRows(text: String): Parsed? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val delimiter = when {
            lines[0].count { it == ';' } > lines[0].count { it == ',' } -> ';'
            lines[0].contains('\t') -> '\t'
            else -> ','
        }
        val header = splitCsv(lines[0], delimiter).map { it.lowercase(Locale.ROOT).trim() }
        fun col(vararg names: String): Int = header.indexOfFirst { h -> names.any { h.contains(it) } }
        val titleCol = col("track name", "trackname", "title", "song", "name")
        val artistCol = col("artist name", "artist", "artists")
        val albumCol = col("album")
        val isrcCol = col("isrc")
        val playlistCol = col("playlist name", "playlist")
        val typeCol = header.indexOfFirst { it == "type" }
        if (titleCol < 0) return null
        val out = ArrayList<Row>()
        var skipped = 0
        for (line in lines.drop(1)) {
            val cells = splitCsv(line, delimiter)
            val title = cells.getOrNull(titleCol)?.trim().orEmpty()
            if (title.isBlank()) continue
            val playlist = playlistCol.takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim() }
                ?.takeIf { it.isNotBlank() }
            // A YouTube library export lists the channels you subscribe to next to your songs.
            // Those are people and shows, not music: searching for "Linus Tech Tips" as a song
            // only finds noise.
            val type = typeCol.takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim()?.lowercase(Locale.ROOT) }
            if (type == "artist" || type == "channel" || type == "subscription" ||
                playlist?.contains("subscriptions", ignoreCase = true) == true
            ) {
                skipped++
                continue
            }
            out += Row(
                artist = artistCol.takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.trim()
                    ?.takeIf { it.isNotBlank() }?.take(120),
                title = title.take(200),
                album = albumCol.takeIf { it >= 0 }?.let { cells.getOrNull(it)?.trim()?.take(120) }
                    ?.takeIf { it.isNotBlank() },
                isrc = isrcCol.takeIf { it >= 0 }?.let { normalizeIsrc(cells.getOrNull(it).orEmpty()) },
                playlist = playlist,
            )
        }
        return Parsed(out.distinctBy { key(it.artist, it.title) + "|" + it.playlist }, skipped)
    }

    /** "Artist - Title", "Artist<TAB>Title", "Artist, Title", or a bare title. */
    private fun lines(text: String): List<Row> = text.lines()
        .map { it.trim().removePrefix("-").trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val cleaned = line.removePrefix("\"").removeSuffix("\"")
            val parts = when {
                cleaned.contains("\t") -> cleaned.split('\t')
                cleaned.contains(" - ") -> cleaned.split(" - ", limit = 2)
                cleaned.contains(" – ") -> cleaned.split(" – ", limit = 2)
                cleaned.contains(" — ") -> cleaned.split(" — ", limit = 2)
                cleaned.contains(',') && cleaned.indexOf(',') < cleaned.length / 2 -> cleaned.split(',', limit = 2)
                else -> listOf(cleaned)
            }.map { it.trim().trim('"') }.filter { it.isNotBlank() }
            when {
                parts.isEmpty() -> null
                parts.size == 1 -> Row(null, parts[0].take(200), null, null)
                else -> Row(parts[0].take(120), parts[1].take(200), null, null)
            }
        }
        .distinctBy { key(it.artist, it.title) }

    /** Minimal RFC-4180-ish splitter: handles quoted fields and doubled quotes. */
    internal fun splitCsv(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> {
                    out += sb.toString(); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    // ---------------------------------------------------------------------------- matching

    /**
     * Resolves rows against the catalogue: one artist search per distinct artist (not per row),
     * then the best title match within that artist's results. Rows that do not match are kept
     * so the caller can say how many were left behind.
     */
    fun match(
        addon: EclipseAddon,
        rows: List<Row>,
        source: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        skipped: Int = 0,
    ): Import {
        val matchedRows = ArrayList<Row>()
        val matchedTracks = LinkedHashMap<String, Track>()
        val byPlaylist = LinkedHashMap<String, LinkedHashMap<String, Track>>()
        var done = 0
        val cache = HashMap<String, List<Track>>()

        fun record(row: Row, track: Track) {
            matchedRows += row
            matchedTracks.putIfAbsent(identity(track), track)
            byPlaylist.getOrPut(row.playlist ?: source) { LinkedHashMap() }.putIfAbsent(identity(track), track)
        }

        // Rows with an artist: one artist search per distinct artist, then the best title in it.
        val (named, bare) = rows.partition { !it.artist.isNullOrBlank() }
        for ((artistKey, group) in named.groupBy { norm(it.artist.orEmpty()) }) {
            if (isCancelled()) break
            val artist = group.first().artist
            val candidates = cache.getOrPut(artistKey) {
                runCatching { addon.search(artist!!, limit = 20).tracks.map { it.toTrack() } }
                    .getOrDefault(emptyList())
            }
            for (row in group) {
                done++
                onProgress(done, rows.size)
                var best = bestMatch(row, candidates)
                if (best == null && candidates.isEmpty()) {
                    // Nothing under that artist: one query with both fields, once per row.
                    val query = listOfNotNull(row.artist, row.title).joinToString(" ")
                    best = bestMatch(row, runCatching {
                        addon.search(query, limit = 10).tracks.map { it.toTrack() }
                    }.getOrDefault(emptyList()))
                }
                if (best != null) record(row, best)
            }
        }

        // Rows with no artist column are YouTube video names ("Katy Perry - Roar (Official
        // Video)"): the artist is somewhere inside the title, so search the whole cleaned name.
        for (row in bare) {
            if (isCancelled()) break
            done++
            onProgress(done, rows.size)
            val query = videoQuery(row.title)
            val candidates = if (query.isBlank()) emptyList() else cache.getOrPut("v:" + query.lowercase(Locale.ROOT)) {
                runCatching { addon.search(query, limit = 10).tracks.map { it.toTrack() } }
                    .getOrDefault(emptyList())
            }
            bestVideoMatch(row, query, candidates)?.let { record(row, it) }
        }
        return Import(
            rows = rows,
            matched = matchedTracks.values.toList(),
            matchedRows = matchedRows.size,
            withIsrc = rows.count { it.isrc != null },
            source = source,
            byPlaylist = byPlaylist.mapValues { it.value.values.toList() },
            skipped = skipped,
        )
    }

    /** "Beartooth – The Lines (Low Gain Mix) (Audio)" → "Beartooth The Lines". */
    internal fun videoQuery(title: String): String =
        cleanTitle(title).replace(Regex("""\s+[-–—:]\s+"""), " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * A video name holds the artist and the song in either order, so a candidate is right when
     * the words of its artist and title are all in the name, and the name is not much longer than
     * them (a short name must not match a long different song). Needs two words at least.
     */
    internal fun bestVideoMatch(row: Row, query: String, candidates: List<Track>): Track? {
        row.isrc?.let { want ->
            candidates.firstOrNull { normalizeIsrc(it.isrc.orEmpty()) == want }?.let { return it }
        }
        val want = titleTokens(query)
        if (want.size < 2 || candidates.isEmpty()) return null
        var best: Track? = null
        var bestScore = 0f
        for (c in candidates) {
            val title = titleTokens(c.title)
            val artist = titleTokens(c.artist.orEmpty())
            if (title.isEmpty()) continue
            val titleHit = coverage(title, want)                 // the song's words are in the name
            val artistHit = if (artist.isEmpty()) 0f else coverage(artist, want)
            val nameCovered = coverage(want, title + artist)      // the name's words are all accounted for
            if (titleHit < 0.99f || artistHit < 0.5f || nameCovered < 0.75f) continue
            val score = titleHit + artistHit + nameCovered
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    /** Exact ISRC wins; otherwise the title that best covers the row, above a floor. */
    private fun bestMatch(row: Row, candidates: List<Track>): Track? {
        if (candidates.isEmpty()) return null
        row.isrc?.let { want ->
            candidates.firstOrNull { normalizeIsrc(it.isrc.orEmpty()) == want }?.let { return it }
        }
        val want = titleTokens(row.title)
        if (want.isEmpty()) return null
        var best: Track? = null
        var bestScore = 0f
        for (c in candidates) {
            val score = coverage(want, titleTokens(c.title))
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        // 0.75 is "most of the title matches": below that, different songs start colliding.
        return if (bestScore >= 0.75f) best else null
    }

    // ----------------------------------------------------------------------------- helpers

    /** Strip the noise streaming exports carry: "(Official Video)", "[4K]", "feat. …". */
    fun cleanTitle(s: String): String {
        var t = s
        t = t.replace(Regex("""\s+\|.*$"""), "")   // "… | NCS - Copyright Free Music"
        t = t.replace(Regex("""\s*[\(\[][^)\]]*[)\]]"""), "")
        t = t.replace(Regex("""(?i)\s+feat\.?\s+.*$"""), "")
        t = t.replace(Regex("""(?i)\s*-\s*(official|lyric|audio|video|remaster(ed)?|hd|4k).*$"""), "")
        return t.trim().ifBlank { s.trim() }
    }

    private fun titleTokens(s: String): Set<String> =
        cleanTitle(s).lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .toSet()

    /** Fraction of the wanted title's tokens present in the candidate. */
    private fun coverage(want: Set<String>, have: Set<String>): Float {
        if (want.isEmpty()) return 0f
        var hit = 0
        for (t in want) if (t in have) hit++
        return hit.toFloat() / want.size
    }

    fun normalizeIsrc(s: String?): String? =
        s?.uppercase(Locale.ROOT)?.replace(Regex("[^A-Z0-9]"), "")?.takeIf { it.length >= 10 }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).trim()
    private fun key(artist: String?, title: String) = norm(artist.orEmpty()) + "|" + norm(cleanTitle(title))
    private fun identity(t: Track) = t.isrc?.lowercase(Locale.ROOT) ?: key(t.artist, t.title)
}
