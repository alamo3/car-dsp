package dev.alamo3.cabineq.music

import java.text.Normalizer
import java.util.Locale

/**
 * Picks the iTunes search result that actually matches what we searched for, or none.
 * A wrong cover is worse than no cover: "Various Artists – Pop Hits for the Summer" must not
 * come back as Frozen just because it was iTunes' top hit.
 */
object CoverMatch {
    data class Candidate(val artist: String?, val collection: String?, val track: String?, val artworkUrl: String)

    /** Words that don't identify a release. */
    private val STOP = setOf(
        "the", "a", "an", "and", "of", "to", "in", "on", "for", "with", "by", "from", "feat", "ft", "featuring",
        "various", "artists", "va", "original", "version", "remaster", "remastered", "deluxe", "edition",
        "album", "single", "ep", "explicit", "clean", "mono", "stereo", "bonus", "track", "disc", "cd",
        "flac", "mp3", "web", "lossless", "hi", "res", "bit", "khz", "kbps",
    )

    fun tokens(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in STOP && !(it.length == 4 && it.all(Char::isDigit)) }  // drop years
            .toSet()
    }

    /** Fraction of the query's identifying words found in the candidate. */
    fun score(query: String, c: Candidate): Double {
        val q = tokens(query)
        if (q.isEmpty()) return 0.0
        val have = tokens(c.artist) + tokens(c.collection) + tokens(c.track)
        return q.count { it in have }.toDouble() / q.size
    }

    /** Best candidate with at least [minScore] of the query's words, or null. */
    fun best(query: String, candidates: List<Candidate>, minScore: Double = 0.6): Candidate? {
        if (tokens(query).isEmpty()) return null
        return candidates.map { it to score(query, it) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }
            ?.first
    }
}
