package dev.alamo3.cabineq.player

/**
 * The queue, kept in step with the player's own playlist.
 *
 * Media3 holds `MediaItem`s; the rest of the app addresses songs by their queue key ("which song
 * is at index 4", "move this key up"), so the keys are a second copy of the same list. Two copies
 * drift apart the moment one of them is changed alone — a missing key means the wrong song's
 * stream is fetched, a stale key at the end means a song that cannot be resolved.
 *
 * Every change therefore goes through one of the functions below, which change both copies in the
 * same call, and [check] compares them in debug builds after each one.
 *
 * The player side is an interface so the rules can be exercised on a plain JVM: the app passes
 * `MediaItem`s to ExoPlayer, the tests pass strings to an `ArrayList`.
 *
 * Main thread only.
 */
internal class QueueMirror<T>(private val playlist: Playlist<T>) {

    /** The player's playlist, as far as the mirror needs it. */
    interface Playlist<T> {
        val itemCount: Int
        fun add(items: List<T>)
        fun removeAt(index: Int)
        fun move(from: Int, to: Int)
    }

    private val order = ArrayList<String>()

    /** Queue keys in playlist order. Read-only: changes go through the functions below. */
    val keys: List<String> get() = order

    /**
     * A new queue replaced the old one. The caller has already given the player its items, so
     * there is nothing to change here — only the keys to replace and the result to [check].
     */
    fun loaded(newKeys: List<String>) {
        order.clear()
        order.addAll(newKeys)
    }

    /**
     * Appends [items] (and their keys) at the end; returns the index they start at, which is what
     * the player's "play the new one if the queue had ended" path needs.
     */
    fun added(items: List<T>, newKeys: List<String>): Int {
        require(items.size == newKeys.size) { "added ${items.size} items with ${newKeys.size} keys" }
        val first = order.size
        order.addAll(newKeys)
        playlist.add(items)
        return first
    }

    /** Removes one entry from both copies; returns its key, or null when the index is not there. */
    fun removedAt(index: Int): String? {
        // Both copies are checked, not just ours: if they have already drifted, removing by an
        // index only one of them has would throw out of the player, or take the wrong song.
        if (index !in order.indices || index !in 0 until playlist.itemCount) return null
        val gone = order.removeAt(index)
        playlist.removeAt(index)
        return gone
    }

    /** Moves one entry in both copies; false when either index is out of range. */
    fun moved(from: Int, to: Int): Boolean {
        if (from == to) return from in order.indices && from in 0 until playlist.itemCount
        if (from !in order.indices || to !in order.indices) return false
        if (from !in 0 until playlist.itemCount || to !in 0 until playlist.itemCount) return false
        // ExoPlayer's moveMediaItem puts the item at `to` in the list as it stands afterwards,
        // which is what ArrayList.add(to, removeAt(from)) does.
        order.add(to, order.removeAt(from))
        playlist.move(from, to)
        return true
    }

    /** Everything is gone (the engine was shut down and its player released). */
    fun cleared() = order.clear()

    fun keyAt(index: Int): String? = order.getOrNull(index)

    fun indexOf(key: String): Int = order.indexOf(key)

    /**
     * The two copies must have the same length. A debug build fails here rather than playing the
     * wrong song later; release builds keep going, because a wrong queue is better than a crash.
     */
    fun check(where: String, enabled: Boolean = true) {
        if (!enabled) return
        check(order.size == playlist.itemCount) {
            "queue mirror out of step after $where: ${order.size} keys, ${playlist.itemCount} items"
        }
    }
}
