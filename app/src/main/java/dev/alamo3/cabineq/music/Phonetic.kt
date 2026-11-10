package dev.alamo3.cabineq.music

/**
 * Matching by sound, for text that came out of a speech recogniser. The assistant hears "beyonsay"
 * or "day ft punk"; spelling distance is no help there, but the consonant skeleton is the same.
 *
 * [key] is a deliberately coarse sound key (in the spirit of Metaphone): vowels dropped, letters
 * that sound alike merged. Coarse keys collide, so callers only trust them on words of some
 * length and prefer exact and near-spelling matches first.
 */
object Phonetic {

    /** Sound key of [text], ignoring spaces: "Daft Punk" and "day ft punk" are both TFTPNK. */
    fun key(text: String): String {
        var s = SongKey.words(text).joinToString("")
        if (s.isEmpty()) return ""
        // Digraphs first. '$' stands for the "sh" sound so it can't be confused with a real x.
        if (s.startsWith("kn") || s.startsWith("gn") || s.startsWith("pn")) s = s.substring(1)
        if (s.startsWith("wr")) s = s.substring(1)
        s = s.replace("tch", "$").replace("sch", "sk").replace("ch", "$").replace("sh", "$")
            .replace("ph", "f").replace("gh", "").replace("ck", "k").replace("dg", "j")
            .replace("th", "t").replace("qu", "kw").replace("x", "ks")
        val out = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            val soft = next == 'e' || next == 'i' || next == 'y'
            val mapped: Char? = when (c) {
                'a', 'e', 'i', 'o', 'u', 'y' -> if (i == 0) 'A' else null
                'h' -> null
                'w' -> if (i == 0) 'W' else null
                'c' -> if (soft) 'S' else 'K'
                'g' -> if (soft) 'J' else 'K'
                'q', 'k' -> 'K'
                's', 'z' -> 'S'
                'b', 'p' -> 'P'
                'd', 't' -> 'T'
                'f', 'v' -> 'F'
                'm', 'n' -> 'N'
                '$' -> 'X'
                else -> c.uppercaseChar()
            }
            if (mapped != null && (out.isEmpty() || out.last() != mapped)) out.append(mapped)
        }
        return out.toString()
    }

    /** Two words that sound the same. Only for words long enough for the key to mean something. */
    fun sameWord(a: String, b: String): Boolean {
        if (a.length < 4 || b.length < 4 || kotlin.math.abs(a.length - b.length) > 3) return false
        val ka = key(a)
        return ka.length >= 3 && ka == key(b)
    }

    /** Whole names that sound the same, wherever the recogniser put the spaces. */
    fun sameName(a: String, b: String): Boolean {
        val ka = key(a)
        return ka.length >= 4 && ka == key(b)
    }

    /**
     * The name from [known] that [heard] sounds like, when [heard] isn't already one of them.
     * Used to turn a misheard artist into one the listener actually plays.
     */
    fun correct(heard: String, known: Collection<String>): String? {
        val plain = SongKey.simplify(heard)
        if (plain.isEmpty() || known.any { SongKey.simplify(it) == plain }) return null
        val k = key(heard)
        // Short keys collide; allow a three-sound key only for a reasonably long name, and only
        // because [known] is the listener's own short list, not a whole catalogue.
        if (k.length < 3 || (k.length == 3 && plain.length < 6)) return null
        return known.firstOrNull { key(it) == k }
    }
}
