package dev.alamo3.cabineq.torbox

import java.util.Locale

/**
 * Picks the tracks to play out of a torrent's file list, and ranks search results.
 *
 * Rules:
 *  - Only formats Android's MediaCodec can decode are playable (FLAC, WAV, ALAC/AAC in .m4a,
 *    MP3, Ogg Vorbis, Opus). APE, WavPack, AIFF and DSD are counted but skipped.
 *  - If any lossless files exist, only lossless files are used (albums often ship FLAC + MP3).
 *  - Within that tier, the single most common extension wins, so duplicate encodes
 *    (e.g. FLAC and WAV of the same album) don't play twice.
 *  - Junk is dropped: macOS resource forks, "sample" clips, tiny files.
 *  - Tracks are ordered by natural sort of their full path, so "CD1/2 …" < "CD1/10 …" < "CD2/1 …".
 */
object AudioPicker {

    val LOSSLESS = setOf("flac", "wav")
    val LOSSY = setOf("m4a", "mp3", "ogg", "oga", "opus", "aac")
    val UNSUPPORTED = setOf("ape", "wv", "aiff", "aif", "dsf", "dff", "tak", "tta", "wma", "mka")

    private const val MIN_TRACK_BYTES = 256 * 1024L

    data class Pick(
        val tracks: List<TorBoxFile>,
        /** Chosen extension, e.g. "flac", or null if nothing playable was found. */
        val format: String?,
        val lossless: Boolean,
        /** Audio files present but in a format Android can't decode. */
        val unsupportedCount: Int,
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** [filter] restricts the pick to that format (e.g. MP3 from an album that ships FLAC + MP3). */
    fun pick(files: List<TorBoxFile>, filter: FormatFilter = FormatFilter.ANY): Pick {
        val candidates = files.filterNot(::isJunk)
        val unsupported = candidates.count { extension(it.name) in UNSUPPORTED }

        val allowed = filter.extensions
        fun ok(f: TorBoxFile) = allowed == null || extension(f.name) in allowed
        val lossless = candidates.filter { extension(it.name) in LOSSLESS && ok(it) }
        val tier = lossless.ifEmpty { candidates.filter { extension(it.name) in LOSSY && ok(it) } }
        if (tier.isEmpty()) return Pick(emptyList(), null, false, unsupported)

        // Most common extension; ties go to the preferred order below.
        val preference = listOf("flac", "wav", "m4a", "opus", "ogg", "oga", "mp3", "aac")
        val format = tier.groupingBy { extension(it.name) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { preference.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } })
            .first().key

        val tracks = tier.filter { extension(it.name) == format }.sortedWith(NaturalPathOrder)
        return Pick(tracks, format, format in LOSSLESS, unsupported)
    }

    /** Display title for a track: file name without extension or leading path. */
    fun trackTitle(file: TorBoxFile): String = file.shortName.substringAfterLast('/').substringBeforeLast('.')

    private fun isJunk(f: TorBoxFile): Boolean {
        val path = f.name.lowercase(Locale.ROOT)
        val base = path.substringAfterLast('/')
        return "__macosx" in path ||
            base.startsWith("._") ||
            Regex("(^|[^a-z])sample([^a-z]|$)").containsMatchIn(base) ||
            (f.size in 1 until MIN_TRACK_BYTES)
    }

    // ------------------------------------------------------------------ audio vs video

    private val LOSSLESS_TITLE = Regex("""\b(flac|lossless|alac|wav|24[- ]?bit|16[- ]?bit|hi[- ]?res|cd[- ]?rip|web[- ]?flac)\b""", RegexOption.IGNORE_CASE)
    private val LOSSY_TITLE = Regex("""\b(mp3|\d{3}\s?kbps|v0|aac|opus|ogg)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_TITLE = Regex(
        """\b(album|discography|ep|single|ost|soundtrack|vinyl|lp|cd\d?|disc\s?\d|remaster(ed)?|deluxe|kbps|khz|audiobook)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Video release markers: resolutions, video codecs, video sources, TV episode tags and
     * video containers. One of these is enough to call a title video, even if it also says
     * FLAC — "Concert 1080p BluRay FLAC" is a video with a lossless audio track.
     */
    private val VIDEO_TITLE = Regex(
        """\b(\d{3,4}p|4k|uhd|hdr(10)?|dolby[ .]?vision|dv|[xh][ .]?26[45]|hevc|avc|xvid|divx|av1|vp9|""" +
            """blu[- ]?ray|bdrip|brrip|bdremux|remux|web[- .]?dl|webrip|hdtv|hdrip|dvd(rip|scr|5|9)?|""" +
            """camrip|hdcam|telesync|s\d{1,2}e\d{1,3}|s\d{1,2}|season\s?\d+|complete series|""" +
            """mkv|mp4|avi|m2ts|wmv)\b""",
        RegexOption.IGNORE_CASE,
    )

    enum class Kind { AUDIO, VIDEO, UNKNOWN }

    /** Format filter for search results and track picking. Null extensions = any playable audio. */
    enum class FormatFilter(val label: String, val extensions: Set<String>?) {
        ANY("Any audio", null),
        LOSSLESS("Lossless", setOf("flac", "wav")),
        FLAC("FLAC", setOf("flac")),
        MP3("MP3", setOf("mp3")),
    }

    private val FLAC_TITLE = Regex("""\bflac\b""", RegexOption.IGNORE_CASE)
    private val MP3_TITLE = Regex("""\bmp3\b""", RegexOption.IGNORE_CASE)

    /** By actual files: playable audio → AUDIO; video files and no audio → VIDEO; else UNKNOWN. */
    fun classifyFiles(files: List<TorBoxFile>): Kind = when {
        pick(files).tracks.isNotEmpty() -> Kind.AUDIO
        files.any { extension(it.name) in VIDEO_EXT } -> Kind.VIDEO
        else -> Kind.UNKNOWN
    }

    /** A search result, plus its real file list when TorBox has it cached. */
    class Candidate(val result: SearchResult, val files: List<TorBoxFile>?) {
        val filesKnown: Boolean get() = files != null
        val kind: Kind = if (files != null) classifyFiles(files) else classify(result.title)

        /** Count per playable extension from the real files, e.g. {flac=12, mp3=12}. */
        val formats: Map<String, Int> = files.orEmpty().filterNot(::isJunk).map { extension(it.name) }
            .filter { it in LOSSLESS || it in LOSSY }.groupingBy { it }.eachCount()

        fun matches(filter: FormatFilter): Boolean {
            if (files != null) {
                if (kind != Kind.AUDIO) return false   // known not to be audio (video, ebooks, …)
                val allowed = filter.extensions ?: return true
                return formats.keys.any { it in allowed }
            }
            // Files unknown (not cached): fall back to the title.
            if (kind == Kind.VIDEO) return false
            val t = result.title
            return when (filter) {
                FormatFilter.ANY -> true
                FormatFilter.LOSSLESS -> looksLossless(t)
                FormatFilter.FLAC -> FLAC_TITLE.containsMatchIn(t)
                FormatFilter.MP3 -> MP3_TITLE.containsMatchIn(t)
            }
        }
    }

    /**
     * Keeps audio results matching [filter]. Order: confirmed by real files first, then
     * audio-looking titles, then ambiguous titles; within each: cached, lossless, seeders.
     */
    fun filterAndRank(candidates: List<Candidate>, filter: FormatFilter): List<Candidate> = candidates
        .filter { it.matches(filter) }
        .sortedWith(
            compareByDescending<Candidate> { it.filesKnown }
                .thenByDescending { it.kind == Kind.AUDIO }
                .thenByDescending { it.result.cached == true }
                .thenByDescending { c -> c.formats.keys.any { it in LOSSLESS } || looksLossless(c.result.title) }
                .thenBy { looksLossy(it.result.title) }
                .thenByDescending { it.result.seeders }
        )

    fun looksLossless(title: String): Boolean = LOSSLESS_TITLE.containsMatchIn(title)
    fun looksLossy(title: String): Boolean = !looksLossless(title) && LOSSY_TITLE.containsMatchIn(title)

    /** Classifies a release title. Any video marker wins; otherwise audio markers make it audio. */
    fun classify(title: String): Kind {
        // Separators like "." and "_" are common in release names; treat them as spaces.
        val t = title.replace('_', ' ').replace(Regex("""(?<=\w)\.(?=\w)"""), " ")
        return when {
            VIDEO_TITLE.containsMatchIn(t) -> Kind.VIDEO
            LOSSLESS_TITLE.containsMatchIn(t) || LOSSY_TITLE.containsMatchIn(t) || AUDIO_TITLE.containsMatchIn(t) -> Kind.AUDIO
            else -> Kind.UNKNOWN
        }
    }

    /** True if a torrent already on the account contains video files and no playable audio. */
    fun isVideoTorrent(files: List<TorBoxFile>): Boolean =
        pick(files).tracks.isEmpty() && files.any { extension(it.name) in VIDEO_EXT }

    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m2ts", "ts", "wmv", "webm", "m4v", "iso", "vob")

    /**
     * Audio-only search results: video releases are dropped. Titles with clear audio markers
     * come before ambiguous ones (plain "Artist - Album (2020)"); within each group cached
     * first (instant), then lossless, then seeders.
     */
    fun rank(results: List<SearchResult>): List<SearchResult> = results
        .filter { classify(it.title) != Kind.VIDEO }
        .sortedWith(
            compareByDescending<SearchResult> { classify(it.title) == Kind.AUDIO }
                .thenByDescending { it.cached == true }
                .thenByDescending { looksLossless(it.title) }
                .thenBy { looksLossy(it.title) }
                .thenByDescending { it.seeders }
        )

    // ------------------------------------------------------------------ natural sort

    object NaturalPathOrder : Comparator<TorBoxFile> {
        private val chunk = Regex("""\d+|\D+""")

        override fun compare(a: TorBoxFile, b: TorBoxFile): Int = compareNatural(a.name, b.name)

        fun compareNatural(a: String, b: String): Int {
            val ca = chunk.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
            val cb = chunk.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
            for (i in 0 until minOf(ca.size, cb.size)) {
                val x = ca[i]
                val y = cb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    val nx = x.trimStart('0')
                    val ny = y.trimStart('0')
                    if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
                } else {
                    x.compareTo(y)
                }
                if (c != 0) return c
            }
            return ca.size - cb.size
        }
    }
}
