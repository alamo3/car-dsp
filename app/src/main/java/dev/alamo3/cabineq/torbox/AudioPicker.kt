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

    fun pick(files: List<TorBoxFile>): Pick {
        val candidates = files.filterNot(::isJunk)
        val unsupported = candidates.count { extension(it.name) in UNSUPPORTED }

        val lossless = candidates.filter { extension(it.name) in LOSSLESS }
        val tier = lossless.ifEmpty { candidates.filter { extension(it.name) in LOSSY } }
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

    // ------------------------------------------------------------------ search ranking

    private val LOSSLESS_TITLE = Regex("""\b(flac|lossless|alac|wav|24[- ]?bit|16[- ]?bit|hi[- ]?res|cd[- ]?rip|web[- ]?flac)\b""", RegexOption.IGNORE_CASE)
    private val LOSSY_TITLE = Regex("""\b(mp3|320\s?kbps|v0|aac|opus)\b""", RegexOption.IGNORE_CASE)

    fun looksLossless(title: String): Boolean = LOSSLESS_TITLE.containsMatchIn(title)
    fun looksLossy(title: String): Boolean = !looksLossless(title) && LOSSY_TITLE.containsMatchIn(title)

    /** Cached first (instant), then lossless-looking titles, then seeders. */
    fun rank(results: List<SearchResult>): List<SearchResult> = results.sortedWith(
        compareByDescending<SearchResult> { it.cached == true }
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
