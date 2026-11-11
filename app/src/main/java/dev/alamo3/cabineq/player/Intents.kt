package dev.alamo3.cabineq.player

/**
 * What the listener asked for that the player has not carried out yet.
 *
 * Every command the app sends is posted to the engine's main thread, but the player is not always
 * able to take it: a start is still reading the disk cache, or the player has not been built at
 * all. Commands used to be handed straight to the player in those windows and dropped, which is
 * how "next" right after picking a song did nothing, and how a pause pressed while a song loaded
 * still let it play.
 *
 * They are kept here instead — the last request of each kind wins — and applied the moment there
 * is a queue to act on. Plain data, no player, no Android: the rules are covered by
 * `IntentsTest`.
 *
 * Main thread only.
 */
internal class Intents {

    /** A queue start whose media items are not in the player yet. */
    private class Start(val seq: Long, val index: Int, val positionMs: Long)
    private class Skip(val seq: Long, val index: Int)
    private class Seek(val seq: Long, val positionMs: Long)
    private class Playing(val seq: Long, val play: Boolean)

    private var seq = 0L
    private var start: Start? = null
    private var skip: Skip? = null
    private var seek: Seek? = null
    private var playing: Playing? = null

    /** Where the queue should begin once it is in the player. */
    class Resolution(val index: Int, val positionMs: Long, val play: Boolean)

    val hasStart: Boolean get() = start != null
    val isEmpty: Boolean get() = start == null && skip == null && seek == null && playing == null

    /**
     * A new queue is being loaded at [index]. Anything held for the queue this replaces is
     * dropped: a skip or a seek pressed earlier was aimed at songs that are no longer queued, and
     * a start means play.
     */
    fun start(index: Int, positionMs: Long) {
        start = Start(++seq, index, positionMs)
        skip = null
        seek = null
        playing = null
    }

    fun skipTo(index: Int) {
        skip = Skip(++seq, index)
    }

    fun seekTo(positionMs: Long) {
        seek = Seek(++seq, positionMs)
    }

    fun setPlaying(play: Boolean) {
        playing = Playing(++seq, play)
    }

    /**
     * The queue is in the player now. A skip sent after the start wins (the new song starts at
     * the beginning unless a seek followed it); a seek on its own moves within the start's song.
     * Returns null when nothing was held, so a caller can tell "no request" from "defaults".
     */
    fun onQueueReady(): Resolution? {
        val s = start ?: return null
        start = null
        val jump = skip?.takeIf { it.seq > s.seq }
        // A seek only counts if it came after whichever move it is meant to apply to.
        val move = seek?.takeIf { it.seq > (jump?.seq ?: s.seq) }
        val index = jump?.index ?: s.index
        val positionMs = when {
            jump != null -> move?.positionMs ?: 0L
            move != null -> move.positionMs
            else -> s.positionMs
        }
        skip = null
        seek = null
        val play = playing?.play ?: true
        playing = null
        return Resolution(index, positionMs, play)
    }

    /** The next skip to apply to a queue that exists, if one was held. */
    fun takeSkip(): Int? {
        val value = skip?.index
        skip = null
        return value
    }

    fun takeSeek(): Long? {
        val value = seek?.positionMs
        seek = null
        return value
    }

    fun takePlaying(): Boolean? {
        val value = playing?.play
        playing = null
        return value
    }

    /** A description for the log line when something is held rather than applied. */
    fun describe(): String = buildList {
        if (start != null) add("start@${start?.index}")
        if (skip != null) add("skip to ${skip?.index}")
        if (seek != null) add("seek to ${seek?.positionMs}")
        if (playing != null) add(if (playing?.play == true) "play" else "pause")
    }.joinToString(", ")

    fun clear() {
        start = null
        skip = null
        seek = null
        playing = null
    }
}
