package dev.alamo3.cabineq

import android.support.v4.media.session.MediaSessionCompat

/**
 * Our MediaSession as a compat token, published by [EqService] so the Car App Library host can
 * render media controls for the templates (it asks for this before playback starts).
 */
object PlaybackTokenHolder {
    @Volatile var token: MediaSessionCompat.Token? = null
        private set

    fun publish(frameworkToken: Any?) {
        token = frameworkToken?.let {
            runCatching { MediaSessionCompat.Token.fromToken(it) }.getOrNull()
        }
    }
}
