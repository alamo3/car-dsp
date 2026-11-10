package dev.alamo3.cabineq

import android.content.Context
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.torbox.TorBoxClient
import java.util.Locale

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
    class Resolved(val url: String, val quality: String? = null)

    fun resolve(
        context: Context,
        track: Track,
        onNote: (String?) -> Unit,
        isCancelled: () -> Boolean,
        maxWaitMs: Long = 5 * 60_000L,
    ): Resolved {
        track.streamUrl?.let { return Resolved(it) }
        val settings = Settings(context)
        return when (track.source) {
            Track.Source.ADDON -> {
                val link = settings.addonLink
                if (link.isBlank()) throw IllegalStateException("Connect Nebula in Settings to play this")
                // A discovered song (Discover) is only a title and artist until now: find the
                // same recording on the addon right before it plays.
                val id = track.sourceId
                    ?: foundOnAddon[track.mediaId]
                    ?: dev.alamo3.cabineq.music.AddonMatch.find(EclipseAddon(link), track)?.id
                        ?.also { foundOnAddon[track.mediaId] = it }
                    ?: throw IllegalStateException("Couldn't find “${track.title}” on Nebula")
                EclipseAddon(link).resolveStream(
                    trackId = id,
                    maxWaitMs = maxWaitMs,
                    isCancelled = isCancelled,
                    onNotice = { onNote(describe(it)) },
                ).let { Resolved(it.url, it.quality) }
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
