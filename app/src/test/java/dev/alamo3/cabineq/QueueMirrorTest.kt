package dev.alamo3.cabineq

import dev.alamo3.cabineq.player.QueueMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The queue has two copies: the keys the app addresses songs by, and the player's own playlist.
 * A change applied to one and not the other is the bug that plays the wrong song, or fetches a
 * stream for an entry that no longer exists. These cases exercise every change the app makes.
 */
class QueueMirrorTest {

    /** Stands in for ExoPlayer's playlist: an ordered list of "media items". */
    private class FakePlaylist : QueueMirror.Playlist<String> {
        val items = ArrayList<String>()
        override val itemCount: Int get() = items.size
        override fun add(items: List<String>) { this.items.addAll(items) }
        override fun removeAt(index: Int) { items.removeAt(index) }
        override fun move(from: Int, to: Int) { items.add(to, items.removeAt(from)) }
    }

    /** A mirror with a queue already in it — the caller loads the items, as the engine does. */
    private fun queueOf(vararg keys: String): Pair<QueueMirror<String>, FakePlaylist> {
        val fake = FakePlaylist()
        val mirror = QueueMirror(fake)
        mirror.loaded(keys.toList())
        fake.items.addAll(keys)
        return mirror to fake
    }

    @Test
    fun aLoadedQueueIsTheSameOnBothSides() {
        val (mirror, fake) = queueOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), mirror.keys)
        assertEquals(listOf("a", "b", "c"), fake.items)
        mirror.check("load")
    }

    @Test
    fun addingReportsWhereTheNewSongsBeginAndAppendsToBoth() {
        val (mirror, fake) = queueOf("a", "b")
        val first = mirror.added(listOf("c", "d"), listOf("c", "d"))
        assertEquals("the index the player should seek to", 2, first)
        assertEquals(listOf("a", "b", "c", "d"), mirror.keys)
        assertEquals(listOf("a", "b", "c", "d"), fake.items)
        mirror.check("added")
    }

    @Test
    fun removingTakesTheSameEntryFromBoth() {
        val (mirror, fake) = queueOf("a", "b", "c")
        assertEquals("b", mirror.removedAt(1))
        assertEquals(listOf("a", "c"), mirror.keys)
        assertEquals(listOf("a", "c"), fake.items)
        mirror.check("removed")
    }

    @Test
    fun removingSomethingThatIsNotThereChangesNothing() {
        val (mirror, fake) = queueOf("a")
        assertNull(mirror.removedAt(4))
        assertNull(mirror.removedAt(-1))
        assertEquals(listOf("a"), mirror.keys)
        assertEquals(listOf("a"), fake.items)
    }

    @Test
    fun movingDownPutsTheEntryAtTheIndexItWasAskedFor() {
        val (mirror, fake) = queueOf("a", "b", "c", "d")
        assertTrue(mirror.moved(0, 2))
        assertEquals(listOf("b", "c", "a", "d"), mirror.keys)
        assertEquals("the player must be told the same thing", listOf("b", "c", "a", "d"), fake.items)
    }

    @Test
    fun movingUpUsesTheFinalIndexToo() {
        val (mirror, fake) = queueOf("a", "b", "c", "d")
        assertTrue(mirror.moved(3, 1))
        assertEquals(listOf("a", "d", "b", "c"), mirror.keys)
        assertEquals(listOf("a", "d", "b", "c"), fake.items)
    }

    @Test
    fun movingSomethingThatIsNotThereChangesNothing() {
        val (mirror, fake) = queueOf("a", "b")
        assertFalse(mirror.moved(0, 5))
        assertFalse(mirror.moved(5, 0))
        assertEquals(listOf("a", "b"), mirror.keys)
        assertEquals(listOf("a", "b"), fake.items)
    }

    @Test
    fun movingAnEntryOntoItselfIsAllowedAndChangesNothing() {
        val (mirror, fake) = queueOf("a", "b")
        assertTrue(mirror.moved(1, 1))
        assertEquals(listOf("a", "b"), mirror.keys)
        assertEquals(listOf("a", "b"), fake.items)
    }

    @Test
    fun aTurnOfPlaybackLeavesTheTwoCopiesIdentical() {
        val (mirror, fake) = queueOf("a", "b", "c")
        val steps = listOf(
            "add two" to { mirror.added(listOf("d", "e"), listOf("d", "e")) },
            "skip the second" to { mirror.removedAt(1) },
            "bring the last forward" to { mirror.moved(4, 0) },
            "append one" to { mirror.added(listOf("f"), listOf("f")) },
            "drop the first" to { mirror.removedAt(0) },
            "move one back" to { mirror.moved(1, 3) },
        )
        for ((what, step) in steps) {
            step()
            assertEquals("keys and playlist after $what", fake.items, mirror.keys)
            mirror.check(what)
        }
    }

    @Test
    fun aChangeIsRefusedWhenTheCopiesHaveAlreadyDrifted() {
        val (mirror, fake) = queueOf("a", "b", "c")
        fake.items.removeAt(2)          // the player lost one behind the mirror's back
        assertNull("do not remove by an index the player does not have", mirror.removedAt(2))
        assertFalse(mirror.moved(0, 2))
        assertEquals("the mirror is left as it was, for the check to report", listOf("a", "b", "c"), mirror.keys)
        assertThrows(IllegalStateException::class.java) { mirror.check("drift") }
    }

    @Test
    fun aCopyThatDriftsIsCaught() {
        val (mirror, fake) = queueOf("a", "b")
        // Something changed the player's list behind the mirror's back.
        fake.items.removeAt(1)
        assertThrows(IllegalStateException::class.java) { mirror.check("test") }
    }

    @Test
    fun lookingUpByIndexOrKeySeesTheSameOrder() {
        val (mirror, _) = queueOf("a", "b", "c")
        assertEquals("b", mirror.keyAt(1))
        assertNull(mirror.keyAt(9))
        assertEquals(2, mirror.indexOf("c"))
        assertEquals(-1, mirror.indexOf("nope"))
    }

    @Test
    fun clearingTheQueueLeavesNothingBehind() {
        val (mirror, fake) = queueOf("a", "b")
        mirror.cleared()
        fake.items.clear()          // the player was released, so its list is empty too
        assertTrue(mirror.keys.isEmpty())
        mirror.check("cleared")
    }
}
