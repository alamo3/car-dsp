package dev.alamo3.cabineq.player

import dev.alamo3.cabineq.addon.AddonNotReady
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a song's source could not be had, in words a listener can act on. The raw errors
 * ("addon /stream/tidal%3Atrack%3A… → 404: {"detail":"no playable source"}") say it to a developer;
 * this says it to the person in the car.
 */
internal enum class SourceTrouble {
    /** Nebula knows the song but has no copy to play (nothing on TorBox, no other stream). */
    NO_SOURCE,
    /** Nebula's search had no match for the song. */
    NOT_ON_NEBULA,
    /** TorBox is downloading it: it will be ready later. */
    CACHING,
    /** All TorBox download slots are in use: temporary. */
    TORBOX_FULL,
    /** Nebula (or the service behind it) did not answer in time. */
    SLOW,
    /** No connection. */
    OFFLINE,
    OTHER;

    fun describe(e: Throwable? = null): String = when (this) {
        NO_SOURCE -> "Nebula has no playable copy of this song"
        NOT_ON_NEBULA -> e?.message?.takeIf { it.startsWith("Couldn't find") } ?: "Not found on Nebula"
        CACHING -> "TorBox is still downloading this song"
        TORBOX_FULL -> "TorBox is at its download limit (all slots busy)"
        SLOW -> "Nebula took too long to answer"
        OFFLINE -> "No connection"
        OTHER -> e?.message?.takeIf { it.isNotBlank() && it.length < 120 && "://" !in it } ?: (e?.javaClass?.simpleName ?: "Couldn't play")
    }

    companion object {
        fun of(e: Throwable): SourceTrouble {
            var cause: Throwable? = e
            var depth = 0
            while (cause != null && depth++ < 6) {
                if (cause is AddonNotReady) return CACHING
                val m = cause.message.orEmpty().lowercase()
                when {
                    "download limit" in m -> return TORBOX_FULL
                    "no playable source" in m -> return NO_SOURCE
                    m.startsWith("couldn't find") -> return NOT_ON_NEBULA
                    cause is UnknownHostException || cause is ConnectException -> return OFFLINE
                    cause is SocketTimeoutException || "timed out" in m -> return SLOW
                }
                cause = cause.cause
            }
            return OTHER
        }
    }
}
