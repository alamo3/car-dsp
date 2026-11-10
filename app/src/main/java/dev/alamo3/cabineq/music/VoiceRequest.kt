package dev.alamo3.cabineq.music

/**
 * What the assistant handed over for "play …". Besides the transcript it often says what kind of
 * thing was asked for and which words are the artist, the album or the title — which is worth
 * far more than guessing from one string.
 */
data class VoiceRequest(
    val query: String,
    val focus: Focus = Focus.ANY,
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val playlist: String? = null,
    /** Other readings of the same utterance, when the recogniser supplied them. */
    val alternatives: List<String> = emptyList(),
) {
    enum class Focus { ANY, SONG, ARTIST, ALBUM, PLAYLIST }

    /** "Play some music": nothing was named. */
    val isOpenEnded: Boolean
        get() = clean(query).isBlank() && artist.isNullOrBlank() && album.isNullOrBlank() &&
            title.isNullOrBlank() && playlist.isNullOrBlank()

    /** One thing to search for, and what it is expected to be. */
    data class Attempt(val text: String, val expect: Focus)

    /**
     * Searches to try, most trustworthy first: the assistant's own fields, then the transcript,
     * then how the transcript might have been meant (an artist the listener knows that sounds
     * the same), then the recogniser's other readings.
     */
    fun attempts(knownArtists: Collection<String> = emptyList()): List<Attempt> {
        val out = LinkedHashMap<String, Attempt>()
        fun add(text: String?, expect: Focus) {
            val t = clean(text.orEmpty())
            if (t.isNotBlank()) out.putIfAbsent(SongKey.simplify(t), Attempt(t, expect))
        }
        val fixedArtist = artist?.let { Phonetic.correct(it, knownArtists) }
        val by = fixedArtist ?: artist
        when {
            !title.isNullOrBlank() && !by.isNullOrBlank() -> add("$title $by", Focus.SONG)
            !album.isNullOrBlank() && focus == Focus.ALBUM -> add(listOfNotNull(album, by).joinToString(" "), Focus.ALBUM)
            !by.isNullOrBlank() && (focus == Focus.ARTIST || title.isNullOrBlank()) -> add(by, Focus.ARTIST)
            !title.isNullOrBlank() -> add(title, Focus.SONG)
        }
        val raw = clean(query)
        add(raw, focus)
        // The whole transcript may be a name the listener knows, misheard.
        Phonetic.correct(raw, knownArtists)?.let { add(it, Focus.ARTIST) }
        // "<song> by <artist>", with the artist misheard.
        val split = Regex("(?i)^(.+?)\\s+by\\s+(.+)$").find(raw)
        if (split != null) {
            val (song, who) = split.destructured
            val fixed = Phonetic.correct(who, knownArtists)
            if (fixed != null) add("$song $fixed", Focus.SONG)
        }
        alternatives.forEach { add(it, focus) }
        return out.values.toList()
    }

    companion object {
        /** The transcript without the command around it: "play … on CabinEQ". */
        fun clean(text: String): String = text.trim()
            .replace(Regex("(?i)^(please\\s+)?(play|put on|listen to|start)\\s+"), "")
            .replace(Regex("(?i)\\s+(on|in|with|using)\\s+cabin\\s*eq\\.?$"), "")
            .replace(Regex("(?i)^(the\\s+)?(song|track|album|artist|band|playlist)\\s+(called\\s+)?"), "")
            .trim()

        /** Android's media-focus content types ("vnd.android.cursor.item/artist", …). */
        fun focusOf(mediaFocus: String?): Focus = when {
            mediaFocus == null -> Focus.ANY
            "artist" in mediaFocus -> Focus.ARTIST
            "album" in mediaFocus -> Focus.ALBUM
            "playlist" in mediaFocus -> Focus.PLAYLIST
            "audio" in mediaFocus -> Focus.SONG
            else -> Focus.ANY
        }
    }
}
