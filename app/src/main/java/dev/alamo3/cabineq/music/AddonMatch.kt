package dev.alamo3.cabineq.music

import dev.alamo3.cabineq.Track
import dev.alamo3.cabineq.addon.EclipseAddon
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Finds a discovered song (title + artist, from [Discover]) in the addon's catalogue so it can
 * be streamed. A wrong match plays the wrong song, so this would rather find nothing: the
 * title has to match both ways, the artist has to be there, and live/remix/karaoke versions
 * only match when that is what was asked for.
 */
object AddonMatch {

    /** Versions that are a different listen from the studio track. */
    private val VARIANTS = setOf(
        "live", "remix", "remixed", "mix", "karaoke", "instrumental", "acoustic", "cover",
        "tribute", "rehearsal", "demo", "sped", "slowed", "reverb", "nightcore", "8d",
    )

    /** Harmless decorations: "Radio Edit", "Remastered 2011", "Single Version". */
    private val NOISE = setOf("radio", "edit", "single", "version", "remaster", "remastered", "mono", "stereo", "original")

    private fun words(s: String?): List<String> {
        if (s.isNullOrBlank()) return emptyList()
        val plain = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && !(it.length == 4 && it.all(Char::isDigit)) }
    }

    /** "(feat. X)", "[with Y]", " ft. Z": who else is on it, not what the song is called. */
    private val FEATURING = Regex("""(?i)\s*[(\[](feat|ft|featuring|with)\b[^)\]]*[)\]]|\s+(feat|ft|featuring)\.?\s.*$""")

    private fun titleWords(s: String?): Set<String> =
        words(s?.replace(FEATURING, "")).filter { it !in NOISE && it !in setOf("the", "a", "an", "feat", "ft", "featuring", "with") }.toSet()

    /** 0..1: how well [candidate] is the same song as [title] by [artist]; 0 = not it. */
    fun score(title: String, artist: String?, durationSec: Int?, candidate: EclipseAddon.Track): Double {
        val want = titleWords(title)
        val have = titleWords(candidate.title)
        if (want.isEmpty() || have.isEmpty()) {
            // Titles made only of noise words or symbols: compare them whole.
            return if (words(title) == words(candidate.title) && artistOk(artist, candidate.artist)) 0.9 else 0.0
        }
        // A live/remix/karaoke version only when the wanted title says so.
        if ((have - want).any { it in VARIANTS }) return 0.0
        val inCandidate = want.count { it in have }.toDouble() / want.size
        val inWanted = have.count { it in want }.toDouble() / have.size
        // Both ways: "Time" must not match "Time After Time", nor the other way round.
        if (inCandidate < 0.8 || inWanted < 0.6) return 0.0
        if (!artistOk(artist, candidate.artist)) return 0.0
        val dur = candidate.durationSec
        if (durationSec != null && durationSec > 0 && dur != null && dur > 0) {
            val delta = abs(durationSec - dur)
            if (delta > 30) return 0.0
            return (inCandidate + inWanted) / 2 - delta / 300.0
        }
        return (inCandidate + inWanted) / 2 - 0.05
    }

    private fun artistOk(wanted: String?, candidate: String?): Boolean {
        val w = words(wanted).filter { it !in setOf("the", "and", "feat", "ft") }.toSet()
        if (w.isEmpty()) return true
        val c = words(candidate).toSet()
        // The primary artist is enough: "Daft Punk" matches "Daft Punk, Pharrell Williams".
        return w.count { it in c }.toDouble() / w.size >= 0.6
    }

    fun best(title: String, artist: String?, durationSec: Int?, candidates: List<EclipseAddon.Track>): EclipseAddon.Track? =
        candidates.map { it to score(title, artist, durationSec, it) }
            .filter { it.second > 0.0 }
            .maxByOrNull { it.second }
            ?.first

    /** Searches the addon for [track]; blocking (one or two requests). */
    fun find(addon: EclipseAddon, track: Track): EclipseAddon.Track? {
        val dur = (track.durationMs / 1000).toInt().takeIf { it > 0 }
        val artist = track.artist?.split(',')?.first()?.trim()
        val queries = listOfNotNull(
            listOfNotNull(artist, track.title).joinToString(" "),
            track.title.takeIf { artist != null },
        )
        for (q in queries) {
            val results = runCatching { addon.search(q, 15) }.getOrNull() ?: continue
            best(track.title, artist, dur, results.tracks)?.let { return it }
        }
        return null
    }
}
