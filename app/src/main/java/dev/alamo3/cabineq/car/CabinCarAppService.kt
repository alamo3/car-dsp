package dev.alamo3.cabineq.car

import android.content.Intent
import android.util.Log
import android.os.SystemClock
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.Screen
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import dev.alamo3.cabineq.BuildConfig
import androidx.core.graphics.drawable.IconCompat
import dev.alamo3.cabineq.PlaybackTokenHolder
import dev.alamo3.cabineq.Radio
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
        // Build the recommendation pool off the connection thread so the "For you" shelf is
        // ready when the first screen renders (it can take a few addon calls).
        Thread({ runCatching { Radio.warm(this) } }, "CabinEQ-CarWarm").apply { isDaemon = true }.start()
        return CabinCarSession()
    }

    /**
     * Only real car hosts may bind: Android Auto (which also drives the desktop head unit) and
     * Android Automotive's templates host, as listed by the library's allow-list. Allowing every
     * host let any app on the phone bind the service and drive playback. Debug builds still
     * accept any host, for third-party test hosts.
     */
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

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
        // Which entry point did the host use? "The app looks wrong in the car" is impossible to
        // diagnose without knowing whether this was ever called, and with what.
        Log.i(CabinCarAppService.TAG, "onCreateScreen action=${intent.action} extras=${intent.extras?.keySet()?.joinToString() ?: "none"}")
        // What the host negotiated for this session. This is the number minCarApiLevel is
        // compared against, so "the car does not list our app" becomes a readable fact.
        runCatching {
            val host = carContext.hostInfo
            Log.i(CabinCarAppService.TAG,
                "host=${host?.packageName ?: "?"} uid=${host?.uid ?: -1} carAppApiLevel=${carContext.carAppApiLevel}")
        }.onFailure { Log.w(CabinCarAppService.TAG, "could not read the host level: ${it.message}") }
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
