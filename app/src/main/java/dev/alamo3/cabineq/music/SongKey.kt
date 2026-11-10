package dev.alamo3.cabineq.music

import java.text.Normalizer

/**
 * One spelling for a song and an artist across catalogues. The same recording is "Get Lucky" in
 * one place and "Get Lucky (feat. Pharrell Williams) - Radio Edit" in another; history, dedupe,
 * discovery and search all have to agree that it is one song.
 */
object SongKey {
    /** "(feat. X)", "[with Y]", " ft. Z" */
    private val FEATURING = Regex("""(?i)\s*[(\[](feat|ft|featuring|with)\b[^)\]]*[)\]]|\s+(feat|ft|featuring)\.?\s.*$""")
    /** "(Remastered 2011)", "[Radio Edit]", " - Single Version" */
    private val DECORATION = Regex(
        """(?i)\s*[(\[][^)\]]*\b(remaster(ed)?|radio edit|single version|album version|mono|stereo)\b[^)\]]*[)\]]""" +
            """|\s+-\s+[^-]*\b(remaster(ed)?|radio edit|single version|album version|mono|stereo)\b.*$"""
    )

    /** The title without credits and release decorations (still as written otherwise). */
    fun plainTitle(title: String): String =
        title.replace(FEATURING, "").replace(DECORATION, "").trim().ifBlank { title.trim() }

    /** Title|primary-artist, simplified. */
    fun of(title: String, artist: String?): String =
        "${simplify(plainTitle(title)).ifBlank { simplify(title) }}|${artist(artist).orEmpty()}"

    /** Primary artist, simplified; "&" stays ("Simon & Garfunkel" is one act). */
    fun artist(name: String?): String? =
        name?.split(',')?.first()?.split(Regex("(?i)\\s+(feat|ft|featuring)\\.?\\s+"))?.first()
            ?.let(::simplify)?.ifBlank { null }

    /** Lowercase, accents folded, punctuation gone, single spaces. */
    fun simplify(s: String): String = words(s).joinToString(" ")

    private val STYLISED_S = Regex("""(?<=\p{L})\$|\$(?=\p{L})""")
    private val STYLISED_I = Regex("""(?<=\p{L})!(?=\p{L})""")

    fun words(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        return Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase()
            // Stylised names are one word, not two: Ke$ha is "kesha", P!nk is "pink", A$AP is
            // "asap". Catalogues disagree on which spelling they list.
            .replace(STYLISED_S, "s").replace(STYLISED_I, "i")
            .replace("&", " and ").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }
}
