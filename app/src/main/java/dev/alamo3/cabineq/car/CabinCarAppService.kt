package dev.alamo3.cabineq.car

import android.content.Intent
import android.os.SystemClock
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import dev.alamo3.cabineq.PlaybackTokenHolder
import dev.alamo3.cabineq.R

/**
 * Car App Library entry point — this is what makes us look like YouTube Music/Spotify in the car
 * (sectioned home with tile shelves, instead of the plain media-browser list).
 *
 * The MediaBrowserService in [dev.alamo3.cabineq.EqService] stays: it owns the MediaSession, the
 * queue, art and the "now playing" surface. This service only draws browse screens, and registers
 * that session with the host so the car's media controls drive the same playback.
 */
class CabinCarAppService : CarAppService() {

    override fun onCreateSession(): Session {
        // Once per connection (not per screen): an explicit pause must survive opening the app.
        CarPlayback.resumeOnConnect(this)
        return CabinCarSession()
    }

    /**
     * Android Auto and Android Automotive are the hosts we care about; this app is not on Play,
     * so the default allow-list (which names Play-signed hosts) is too strict to test with.
     */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    companion object {
        /** Hosts validate this; AA and AAOS are the expected ones. */
        const val TAG = "CabinEQ/CarApp"
    }
}

class CabinCarSession : Session() {

    private var home: HomeScreen? = null

    /** The car's "playing" surface sends this; open the playback screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != SHOW_MEDIA_PLAYBACK) return
        // The host can send this repeatedly; don't stack a new playback screen each time.
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlaybackPushAt < PLAYBACK_PUSH_DEBOUNCE_MS) return
        lastPlaybackPushAt = now
        home?.let { screen ->
            runCatching { screen.screenManager.push(NowPlayingScreen(screen.carContext)) }
        }
    }

    private var lastPlaybackPushAt = 0L

    override fun onCreateScreen(intent: Intent): Screen {
        // The car needs our MediaSessionCompat token to render controls for this app. It lives in
        // EqService, so make sure that service is up before asking for it.
        CarPlayback.ensureService(carContext)
        val token = PlaybackTokenHolder.token
        if (token != null) {
            runCatching {
                carContext.getCarService(MediaPlaybackManager::class.java)
                    .registerMediaPlaybackToken(token)
            }
        }
        val screen = HomeScreen(carContext)
        home = screen
        if (intent.action == SHOW_MEDIA_PLAYBACK) {
            // Launched from the car's media card: land on playback, not browsing.
            runCatching { screen.screenManager.push(NowPlayingScreen(carContext)) }
        }
        return screen
    }

    companion object {
        /** Documented action the host sends when the user taps the playing surface. */
        const val SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"
        private const val PLAYBACK_PUSH_DEBOUNCE_MS = 1500L
    }
}
