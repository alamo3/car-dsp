package dev.alamo3.cabineq

import android.content.Context
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.torbox.TorBoxClient
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Turns a [Track] into a stream URL.
 *
 * Runs on the player's worker thread and may block for minutes: while TorBox is still caching a
 * track the addon answers 503 and this waits it out. [onNote] gets human-readable progress for
 * the UI; [isCancelled] lets a skip abandon the wait.
 *
 * Sources are configured in [Settings], so a track restored from a saved queue resolves exactly
 * like a fresh one instead of holding onto a screen's addon instance.
 */
object TrackResolver {

    /**
     * Songs already found on the addon, by media id. The prefetch matches a discovered song a few
     * minutes before it plays; without this the player repeated the same two or three searches at
     * the moment it needed the audio, which is where the silence between songs came from.
     */
    private val foundOnAddon = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** A playable URL plus what the source claims it is (the addon tells us; TorBox doesn't). */
    class Resolved(
        val url: String,
        val quality: String? = null,
        /** Request headers the link needs (YouTube's links come with some). */
        val headers: Map<String, String> = emptyMap(),
        /** Where it came from, for what the player says while connecting. */
        val via: String = "TorBox",
    )

    // Links already asked for, by song. TorBox documents its download links as valid for 3 hours
    // (reports from the field say some die sooner, around half an hour), so a link is reused for
    // ten minutes: long enough that a song looked up ahead of its turn starts without the ~1 s
    // look-up, short enough to be well inside either figure. A link that is refused anyway is
    // dropped by [forget] and fetched again.
    private class Cached(val url: String, val quality: String?, val at: Long, val headers: Map<String, String> = emptyMap(), val via: String = "TorBox")
    private val linkCache = ConcurrentHashMap<String, Cached>()
    private const val LINK_TTL_MS = 10 * 60_000L
    /** YouTube's links stay valid about 6 hours: kept for 5, so replays and skips back are instant. */
    private const val YOUTUBE_TTL_MS = 5 * 3600_000L
    fun ttlFor(via: String): Long = if (via == "YouTube") YOUTUBE_TTL_MS else LINK_TTL_MS
    private val warmPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "CabinEQ-Warm").apply { isDaemon = true }
    }

    private fun cachedLink(track: Track): Cached? =
        linkCache[track.mediaId]?.takeIf { SystemClock.elapsedRealtime() - it.at < ttlFor(it.via) }

    /** A link that failed must not be handed out again. */
    fun forget(track: Track) {
        linkCache.remove(track.mediaId)
    }

    /**
     * Looks the links up in the background, so that tapping one of these songs starts at once.
     * Meant for songs the listener has already played (their audio is on the source already): a
     * look-up that finds a song still being prepared is dropped, not waited for.
     */
    fun warm(context: Context, tracks: List<Track>) {
        val app = context.applicationContext
        for (track in tracks) {
            if (track.streamUrl != null || cachedLink(track) != null) continue
            warmPool.execute {
                runCatching { resolve(app, track, { }, { false }, 15_000L, waitIfPending = false) }
            }
        }
    }

    fun resolve(
        context: Context,
        track: Track,
        onNote: (String?) -> Unit,
        isCancelled: () -> Boolean,
        maxWaitMs: Long = 5 * 60_000L,
        /** False: throw [dev.alamo3.cabineq.addon.AddonNotReady] when the source is still caching. */
        waitIfPending: Boolean = true,
    ): Resolved {
        track.streamUrl?.let { return Resolved(it) }
        val settings = Settings(context)
        return when (track.source) {
            Track.Source.ADDON -> {
                val link = settings.addonLink
                cachedLink(track)?.let { return Resolved(it.url, it.quality, it.headers, it.via) }
                val youtube = YouTubeCompanion.usable(context)
                // A song picked from YouTube's own results: that video, Nebula only if it fails.
                if (track.youtubeId != null && YouTubeCompanion.installed(context)) {
                    fromYouTube(context, track, onNote)?.let { return it }
                    if (link.isBlank()) throw IllegalStateException("YouTube couldn't play “${track.title}”")
                }
                if (link.isBlank()) {
                    // No Nebula: YouTube alone, when the companion is there.
                    if (youtube) fromYouTube(context, track, onNote)?.let { return it }
                    throw IllegalStateException("Connect Nebula in Settings to play this")
                }
                // YouTube first, when chosen: Nebula only if YouTube has nothing.
                if (youtube && settings.youtubeSource == Settings.YOUTUBE_FIRST) {
                    fromYouTube(context, track, onNote)?.let { return it }
                }
                if (youtube) return race(context, link, track, onNote, isCancelled, maxWaitMs, waitIfPending)
                fromNebula(context, link, track, onNote, isCancelled, maxWaitMs, waitIfPending)
            }
            Track.Source.TORBOX -> {
                if (track.torrentId == 0 || track.fileId == 0) {
                    throw IllegalStateException("“${track.title}” has no TorBox file")
                }
                val key = settings.torboxApiKey
                if (key.isBlank()) throw IllegalStateException("Add your TorBox API key in Settings")
                Resolved(TorBoxClient(key).requestDownloadUrl(track.torrentId, track.fileId))
            }
        }
    }

    private val racers = Executors.newCachedThreadPool { r -> Thread(r, "CabinEQ-Race").apply { isDaemon = true } }
    /** Nebula's head start: a cached song answers well inside it and plays in its own quality. */
    private const val HEAD_START_MS = 2_000L

    /**
     * Nebula first, YouTube in parallel: Nebula gets [HEAD_START_MS]; if it has no link by then
     * (no match yet, no source, slow, or TorBox still caching), YouTube starts too and whichever
     * is ready first plays — Nebula's if both are. Before this, YouTube only started after
     * Nebula gave up, which with Nebula timing out could be 30 s of silence.
     */
    private fun race(
        context: Context,
        link: String,
        track: Track,
        onNote: (String?) -> Unit,
        isCancelled: () -> Boolean,
        maxWaitMs: Long,
        waitIfPending: Boolean,
    ): Resolved {
        val nebula = racers.submit<Resolved> { fromNebula(context, link, track, onNote, isCancelled, maxWaitMs, waitIfPending = false) }
        var youtube: java.util.concurrent.Future<Resolved?>? = null
        val started = SystemClock.elapsedRealtime()
        var nebulaError: Throwable? = null
        try {
            while (true) {
                if (isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
                if (nebulaError == null && nebula.isDone) {
                    try { return nebula.get() } catch (e: java.util.concurrent.ExecutionException) {
                        nebulaError = e.cause ?: e
                        DLog.i("CabinEQ/Resolve", "\"${track.title}\": Nebula ${dev.alamo3.cabineq.player.SourceTrouble.of(nebulaError!!).describe(nebulaError)}")
                    }
                }
                if (youtube == null && (nebulaError != null || SystemClock.elapsedRealtime() - started >= HEAD_START_MS)) {
                    youtube = racers.submit<Resolved?> { fromYouTube(context, track, onNote) }
                }
                youtube?.takeIf { it.isDone }?.let { f ->
                    val got = runCatching { f.get() }.getOrNull()
                    // Nebula's answer may have landed at the same moment: its copy is the better one.
                    if (nebulaError == null && nebula.isDone) runCatching { return nebula.get() }
                    if (got != null) return got
                    if (nebulaError != null) {
                        // Neither: Nebula's own way, waiting for TorBox to cache if that was the problem.
                        if (nebulaError is dev.alamo3.cabineq.addon.AddonNotReady && waitIfPending) {
                            return fromNebula(context, link, track, onNote, isCancelled, maxWaitMs, waitIfPending = true)
                        }
                        throw nebulaError!!
                    }
                    // YouTube had nothing: Nebula is all that is left, keep waiting on it.
                    try { return nebula.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                }
                Thread.sleep(50)
            }
        } finally {
            nebula.cancel(true)
            youtube?.cancel(true)
        }
    }

    /** YouTube through the companion; null when it has no suitable recording or fails. */
    private fun fromYouTube(context: Context, track: Track, onNote: (String?) -> Unit): Resolved? {
        onNote("Finding it on YouTube…")
        return try {
            val l = YouTubeCompanion.resolve(context, track)
            Resolved(l.url, l.quality, l.headers, via = "YouTube")
                .also { linkCache[track.mediaId] = Cached(it.url, it.quality, SystemClock.elapsedRealtime(), it.headers, it.via) }
        } catch (e: Exception) {
            DLog.i("CabinEQ/Resolve", "\"${track.title}\": YouTube ${e.message}")
            null
        }
    }

    private fun fromNebula(
        context: Context,
        link: String,
        track: Track,
        onNote: (String?) -> Unit,
        isCancelled: () -> Boolean,
        maxWaitMs: Long,
        waitIfPending: Boolean,
    ): Resolved {
        // A discovered song (Discover) is only a title and artist until now: find the
        // same recording on the addon right before it plays.
        val id = track.sourceId
            ?: foundOnAddon[track.mediaId]
            ?: dev.alamo3.cabineq.music.AddonMatch.find(EclipseAddon(link), track)?.id
                ?.also { foundOnAddon[track.mediaId] = it }
            ?: throw IllegalStateException("Couldn't find “${track.title}” on Nebula")
        return EclipseAddon(link).resolveStream(
            trackId = id,
            maxWaitMs = maxWaitMs,
            isCancelled = isCancelled,
            onNotice = { onNote(describe(it)) },
            waitIfPending = waitIfPending,
        ).let { Resolved(it.url, it.quality) }
            .also { linkCache[track.mediaId] = Cached(it.url, it.quality, SystemClock.elapsedRealtime()) }
    }

    /** e.g. "TorBox: caching this track (42%)" */
    private fun describe(n: EclipseAddon.Notice?): String {
        if (n == null) return "Preparing…"
        val who = listOfNotNull(n.title, n.message).joinToString(": ").ifBlank { "Preparing…" }
        val percent = n.progress?.let { p ->
            val pct = if (p <= 1.0) p * 100 else p
            String.format(Locale.US, " (%.0f%%)", pct)
        }.orEmpty()
        return who + percent
    }
}
