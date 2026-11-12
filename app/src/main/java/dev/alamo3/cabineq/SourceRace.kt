package dev.alamo3.cabineq

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Two sources for one song, the better one first: [primary] (Nebula) gets [headStartMs] on its
 * own; if it has no answer by then, or fails sooner, [secondary] (YouTube) starts as well and
 * whichever is ready first wins. When both are ready together the primary's copy is taken.
 *
 * No Android here, so the timing rules are tested off the phone.
 */
class SourceRace<T : Any>(
    private val pool: ExecutorService,
    private val headStartMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pollMs: Long = 50,
) {
    sealed class Outcome<T> {
        class Primary<T>(val value: T) : Outcome<T>()
        class Secondary<T>(val value: T) : Outcome<T>()
        /** The primary failed and the secondary had nothing: the caller decides (wait, or give up). */
        class PrimaryFailed<T>(val error: Throwable) : Outcome<T>()
    }

    /**
     * @param primary    throws when it has no copy
     * @param secondary  null when it has no copy
     * @param onPrimaryError told once, as soon as the primary fails (for logs)
     */
    fun run(
        primary: () -> T,
        secondary: () -> T?,
        isCancelled: () -> Boolean = { false },
        onPrimaryError: (Throwable) -> Unit = {},
    ): Outcome<T> {
        val first = pool.submit<T> { primary() }
        var second: Future<T?>? = null
        val started = now()
        var error: Throwable? = null
        fun firstResult(): T = try { first.get() } catch (e: ExecutionException) { throw e.cause ?: e }
        try {
            while (true) {
                if (isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
                if (error == null && first.isDone) {
                    try { return Outcome.Primary(firstResult()) } catch (e: InterruptedException) { throw e } catch (e: Throwable) {
                        error = e
                        onPrimaryError(e)
                    }
                }
                if (second == null && (error != null || now() - started >= headStartMs)) {
                    second = pool.submit<T?> { secondary() }
                }
                val s = second
                if (s != null && s.isDone) {
                    val got = runCatching { s.get() }.getOrNull()
                    // The primary may have answered in the same moment: its copy is the better one.
                    if (error == null && first.isDone) runCatching { return Outcome.Primary(firstResult()) }
                    if (got != null) return Outcome.Secondary(got)
                    error?.let { return Outcome.PrimaryFailed(it) }
                    // The secondary had nothing: the primary is all that is left, keep waiting on it.
                    return try { Outcome.Primary(firstResult()) } catch (e: InterruptedException) { throw e } catch (e: Throwable) {
                        Outcome.PrimaryFailed(e)
                    }
                }
                Thread.sleep(pollMs)
            }
        } finally {
            first.cancel(true)
            second?.cancel(true)
        }
    }
}

/**
 * What the radio does with its own upcoming picks when the listener reacts to a song. Pure, so
 * [FilePlayer] only applies the result.
 */
object RadioRevision {
    /** A skip before this fraction of the song counts as "not this". */
    const val EARLY_SKIP = 0.25f
    /** Early skips are forgotten after this long: a skip this morning is not a verdict for tonight. */
    const val MEMORY_MS = 2 * 3600_000L

    sealed class Action {
        object None : Action()
        class MoveBack(val keys: List<String>) : Action()
        class Drop(val keys: List<String>) : Action()
        class BringForward(val key: String) : Action()
    }

    /** The artist's early-skip count after one more: (count, time of the last). */
    fun countSkip(old: Pair<Int, Long>?, now: Long): Pair<Int, Long> =
        if (old == null || now - old.second > MEMORY_MS) 1 to now else (old.first + 1) to now

    /**
     * @param mine          the radio's own upcoming picks by the artist, beyond the protected few
     * @param earlySkipCount the artist's early skips including this one (only read for SKIP)
     */
    fun plan(kind: Taste.Kind, value: Float, mine: List<String>, earlySkipCount: Int): Action {
        if (mine.isEmpty()) return Action.None
        return when (kind) {
            Taste.Kind.SKIP -> when {
                value >= EARLY_SKIP -> Action.None
                earlySkipCount >= 2 -> Action.Drop(mine)
                else -> Action.MoveBack(mine)
            }
            Taste.Kind.DISLIKE -> Action.Drop(mine)
            Taste.Kind.LIKE, Taste.Kind.COMPLETE -> Action.BringForward(mine.first())
            else -> Action.None
        }
    }
}
