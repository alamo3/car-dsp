package dev.alamo3.cabineq

import dev.alamo3.cabineq.player.Intents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what a player command means when it arrives before the player can take it.
 *
 * These are the bugs that reached the phone as "next did nothing" and "it played even though I
 * paused": the player had no queue yet, and the command was thrown away. The cases below are the
 * situations that can actually happen between tapping a song and its first second.
 */
class IntentsTest {

    @Test
    fun aStartOnItsOwnStartsThereAtItsPosition() {
        val intents = Intents()
        intents.start(3, 5_000)
        val ready = intents.onQueueReady()!!
        assertEquals(3, ready.index)
        assertEquals(5_000, ready.positionMs)
        assertTrue(ready.play)
    }

    @Test
    fun aSkipAfterTheStartWinsAndStartsAtTheBeginning() {
        val intents = Intents()
        intents.start(3, 5_000)
        intents.skipTo(7)
        val ready = intents.onQueueReady()!!
        assertEquals("the tapped next is the song the listener wants", 7, ready.index)
        assertEquals("a different song starts at its beginning", 0, ready.positionMs)
    }

    @Test
    fun aSeekAfterTheSkipPositionsWithinTheNewSong() {
        val intents = Intents()
        intents.start(3, 5_000)
        intents.skipTo(7)
        intents.seekTo(1_000)
        val ready = intents.onQueueReady()!!
        assertEquals(7, ready.index)
        assertEquals(1_000, ready.positionMs)
    }

    @Test
    fun aSeekOnItsOwnKeepsTheStartPositionOfThatSong() {
        val intents = Intents()
        intents.start(3, 5_000)
        intents.seekTo(2_000)
        val ready = intents.onQueueReady()!!
        assertEquals(3, ready.index)
        assertEquals(2_000, ready.positionMs)
    }

    @Test
    fun aSkipPressedForTheQueueBeingReplacedIsDropped() {
        val intents = Intents()
        intents.skipTo(9)          // pressed at the old queue, which is about to go
        intents.start(2, 0)        // the listener then picked a song
        val ready = intents.onQueueReady()!!
        assertEquals("the tap that came last is what plays", 2, ready.index)
        assertEquals(0, ready.positionMs)
    }

    @Test
    fun aPausePressedBeforeTheStartDoesNotSilenceTheNewSong() {
        val intents = Intents()
        intents.setPlaying(false)
        intents.start(4, 0)
        assertTrue("tapping a song is a request to hear it", intents.onQueueReady()!!.play)
    }

    @Test
    fun aPausePressedWhileTheQueueLoadsKeepsItPaused() {
        val intents = Intents()
        intents.start(4, 0)
        intents.setPlaying(false)
        assertFalse(intents.onQueueReady()!!.play)
    }

    @Test
    fun commandsAreKeptWhileThereIsNoQueue() {
        val intents = Intents()
        intents.skipTo(5)
        assertTrue(intents.hasStart.not())
        assertEquals("nothing to apply to yet, and nothing lost", 5, intents.takeSkip())
        assertNull("taken once, not replayed", intents.takeSkip())
    }

    @Test
    fun theLastSkipWinsAndIsAppliedOnce() {
        val intents = Intents()
        intents.skipTo(1)
        intents.skipTo(2)
        intents.skipTo(3)
        assertEquals(3, intents.takeSkip())
        assertTrue(intents.isEmpty)
    }

    @Test
    fun aStartReplacesAPreviousStart() {
        val intents = Intents()
        intents.start(1, 0)
        intents.start(2, 9_000)
        val ready = intents.onQueueReady()!!
        assertEquals(2, ready.index)
        assertEquals(9_000, ready.positionMs)
    }

    @Test
    fun aPlayingRequestSentAfterTheQueueIsUpIsApplied() {
        val intents = Intents()
        intents.setPlaying(false)
        assertEquals(false, intents.takePlaying())
        assertNull(intents.takePlaying())
        assertTrue(intents.isEmpty)
    }

    @Test
    fun everythingIsClearedWhenTheEngineStops() {
        val intents = Intents()
        intents.start(1, 0)
        intents.skipTo(2)
        intents.seekTo(300)
        intents.setPlaying(true)
        intents.clear()
        assertTrue(intents.isEmpty)
        assertNull(intents.onQueueReady())
    }
}
