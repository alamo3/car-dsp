package dev.alamo3.cabineq

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Nebula vs YouTube timing ([SourceRace]) and the radio's reaction to signals ([RadioRevision]). */
class SourceRaceTest {
    private val pool = Executors.newCachedThreadPool()
    private val race = SourceRace<String>(pool, headStartMs = 200, pollMs = 5)

    @After fun stop() { pool.shutdownNow() }

    private fun slow(ms: Long, value: String): () -> String = { Thread.sleep(ms); value }

    @Test fun `primary inside its head start wins and the secondary never starts`() {
        val asked = AtomicBoolean(false)
        val out = race.run(slow(20, "nebula"), { asked.set(true); "youtube" })
        assertTrue(out is SourceRace.Outcome.Primary && out.value == "nebula")
        assertTrue("YouTube asked although Nebula answered in time", !asked.get())
    }

    @Test fun `slow primary loses to the secondary`() {
        val start = System.currentTimeMillis()
        val out = race.run(slow(3_000, "nebula"), slow(50, "youtube"))
        assertTrue(out is SourceRace.Outcome.Secondary && out.value == "youtube")
        assertTrue("took too long", System.currentTimeMillis() - start < 1_500)
    }

    @Test fun `a failing primary starts the secondary at once`() {
        val start = System.currentTimeMillis()
        val out = race.run({ throw IllegalStateException("no source") }, slow(10, "youtube"))
        assertTrue(out is SourceRace.Outcome.Secondary)
        assertTrue("waited for the head start", System.currentTimeMillis() - start < 150)
    }

    @Test fun `both failing reports the primary's error`() {
        val err = IllegalStateException("TorBox caching")
        val out = race.run({ throw err }, { null })
        assertTrue(out is SourceRace.Outcome.PrimaryFailed && out.error === err)
    }

    @Test fun `secondary with nothing waits for a late primary`() {
        val out = race.run(slow(500, "nebula"), { null })
        assertTrue(out is SourceRace.Outcome.Primary && out.value == "nebula")
    }

    @Test fun `cancelling stops the race`() {
        val cancel = AtomicBoolean(false)
        pool.execute { Thread.sleep(100); cancel.set(true) }
        val thrown = runCatching { race.run(slow(5_000, "nebula"), slow(5_000, "youtube"), isCancelled = { cancel.get() }) }
            .exceptionOrNull()
        assertTrue(thrown is InterruptedException)
    }

    // ---------------- radio

    private val mine = listOf("a", "b")

    @Test fun `one early skip moves the artist back, a second drops them`() {
        assertTrue(RadioRevision.plan(Taste.Kind.SKIP, 0.1f, mine, 1) is RadioRevision.Action.MoveBack)
        val drop = RadioRevision.plan(Taste.Kind.SKIP, 0.1f, mine, 2)
        assertTrue(drop is RadioRevision.Action.Drop && drop.keys == mine)
    }

    @Test fun `a late skip changes nothing`() {
        assertTrue(RadioRevision.plan(Taste.Kind.SKIP, 0.6f, mine, 3) is RadioRevision.Action.None)
    }

    @Test fun `dislike drops, like and play-through bring the next one forward`() {
        assertTrue(RadioRevision.plan(Taste.Kind.DISLIKE, 0f, mine, 0) is RadioRevision.Action.Drop)
        val like = RadioRevision.plan(Taste.Kind.LIKE, 1f, mine, 0)
        assertTrue(like is RadioRevision.Action.BringForward && like.key == "a")
        assertTrue(RadioRevision.plan(Taste.Kind.COMPLETE, 1f, mine, 0) is RadioRevision.Action.BringForward)
    }

    @Test fun `nothing upcoming by the artist means nothing to do`() {
        assertTrue(RadioRevision.plan(Taste.Kind.DISLIKE, 0f, emptyList(), 0) is RadioRevision.Action.None)
    }

    @Test fun `early skips are forgotten after two hours`() {
        val t = 1_000_000_000L
        val one = RadioRevision.countSkip(null, t)
        assertEquals(2, RadioRevision.countSkip(one, t + 3_600_000L).first)
        assertEquals(1, RadioRevision.countSkip(one, t + RadioRevision.MEMORY_MS + 1).first)
    }
}
