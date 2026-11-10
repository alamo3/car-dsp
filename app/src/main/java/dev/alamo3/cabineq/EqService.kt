package dev.alamo3.cabineq

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.Rating
import android.media.audiofx.AudioEffect
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import androidx.media.MediaBrowserServiceCompat
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Foreground service. Two processing paths, chosen per app session:
 *
 *  - **DSP** (preferred): the app's audio is captured with AudioPlaybackCapture, run through
 *    our native engine and played back by us; the app's own output is muted.
 *  - **Android EQ** (fallback): DynamicsProcessing on the app's session, as in milestone 1.
 *    Used when DSP is off, couldn't start, for apps that block capture, or after a
 *    routing problem.
 *
 * Both paths get the same EQ + speed compensation, recomputed and slew-limited at 10 Hz.
 *
 * A third consumer of that EQ is the in-app [FilePlayer] (TorBox streams): it decodes files
 * itself and runs them through its own native engine, so it needs no capture at all. The
 * service gives it a MediaSession (steering-wheel / Bluetooth controls) and notification controls.
 */
class EqService : MediaBrowserServiceCompat() {

    private lateinit var settings: Settings
    private lateinit var audioManager: AudioManager
    private val effects = EffectManager()
    private val muter = SessionMuter()
    private val speedComp = SpeedCompensator()
    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null

    private var projection: MediaProjection? = null
    private var capture: CaptureEngine? = null

    private val sessionPackages = LinkedHashMap<Int, String>()

    // Smoothed state actually being applied.
    private val currentBands = FloatArray(Bands.COUNT)
    private var currentInputGain = 0f
    private var initialised = false
    private val lastSentBands = FloatArray(Bands.COUNT) { Float.NaN }
    private var lastSentInputGain = Float.NaN

    private val targetBands = FloatArray(Bands.COUNT)
    private val speedBoosts = FloatArray(Bands.COUNT)

    private var lastGpsKmh = 0f
    private var lastGpsFixElapsed = 0L
    private var stoppedForProjection = false

    // In-app file player (TorBox)
    private var mediaSession: MediaSession? = null
    private var lastPlayerVersion = -1
    private var queueSignature: String? = null
    private var lastPlayerActive = false
    private var sessionArtUrl: String? = null
    private var sessionArt: android.graphics.Bitmap? = null

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return  // 0 is the global mix, handled separately
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown"
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    Log.i(TAG, "Session opened: $session ($pkg)")
                    sessionPackages[session] = pkg
                    routeSession(session, pkg)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    Log.i(TAG, "Session closed: $session ($pkg)")
                    effects.detach(session)
                    muter.unmute(session)
                    sessionPackages.remove(session)
                }
            }
            publishSessions()
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Settings.KEY_GLOBAL -> applyGlobalMode()
            Settings.KEY_CAPTURE_BLOCKED -> rerouteAllSessions()
        }
        // Everything else is picked up by the ticker on its next run.
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked it (e.g. from the status bar chip) or the system stopped it.
            stoppedForProjection = true
            stopDsp("Audio capture permission was revoked — using Android EQ")
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) {
                lastGpsKmh = location.speed * 3.6f
                lastGpsFixElapsed = SystemClock.elapsedRealtime()
                EngineState.gpsActive = true
            }
        }
        // Explicit overrides: on Android 10 these are abstract on the device even though
        // the compile SDK gives them default implementations.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { EngineState.gpsActive = false }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }


    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        audioManager = getSystemService(AudioManager::class.java)
        // The session exists from the start, but stays inactive until we actually play or a
        // browse client connects: running the EQ on someone else's music must not take the
        // media buttons away from their music app.
        val session = ensureMediaSession()
        // Publish the *framework* token, wrapped only so MediaBrowserServiceCompat can hold it.
        // A MediaSessionCompat wrapper here made the car's controller report a null PlaybackState
        // (metadata still arrived), which left the now-playing screen without controls/progress.
        setSessionToken(MediaSessionCompat.Token.fromToken(session.sessionToken))
        PlaybackTokenHolder.publish(session.sessionToken)
    }

    // ---------------------------------------------------------------------------------
    // Browsing: Android Auto and Android Automotive

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserServiceCompat.BrowserRoot {
        mediaSession?.isActive = true
        // Tell the car what we can do beyond a plain list:
        //  - SEARCH_SUPPORTED: show its search UI and call onSearch
        //  - CONTENT_STYLE_SUPPORTED: honour the per-item GRID/LIST hints below
        // Both are AA's own bundle keys (the framework/androidx don't expose constants).
        val extras = Bundle().apply {
            putBoolean(androidx.media.utils.MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
        }
        return MediaBrowserServiceCompat.BrowserRoot(MediaBrowse.ROOT, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        Thread({
            val items = runCatching { MediaBrowse.children(this, parentId) }.getOrElse {
                Log.w(TAG, "Browse $parentId failed: ${it.message}")
                emptyList()
            }
            Log.d(TAG, "Browse $parentId -> ${items.size} items")
            result.sendResult(items.map(::toCompat).toMutableList())
        }, "CabinEQ-Browse").start()
    }

    /** The car's search (Android Auto and Automotive both call this). */
    override fun onSearch(
        query: String,
        extras: Bundle?,
        result: MediaBrowserServiceCompat.Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.detach()
        Thread({
            val items = runCatching { MediaBrowse.search(this, query) }.getOrElse {
                Log.w(TAG, "Search \"$query\" failed: ${it.message}")
                emptyList()
            }
            Log.d(TAG, "Search \"$query\" -> ${items.size} items")
            if (items.isNotEmpty()) settings.addRecentSearch(query)   // feeds the car's Search tab
            result.sendResult(items.map(::toCompat).toMutableList())
        }, "CabinEQ-Search").start()
    }

    /** MediaBrowse speaks framework types; the compat service needs its own copies. */
    @android.annotation.SuppressLint("WrongConstant")   // item.flags is already a MediaItem mask
    private fun toCompat(item: MediaBrowser.MediaItem): MediaBrowserCompat.MediaItem {
        val d = item.description
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(d.mediaId)
            .setTitle(d.title)
            .setSubtitle(d.subtitle)
            .setIconUri(d.iconUri)
            .setExtras(d.extras)
            .build()
        return MediaBrowserCompat.MediaItem(description, item.flags)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not stop what the car is playing.
        if (FilePlayer.peek()?.isActive != true) super.onTaskRemoved(rootIntent)
    }

    /** Plays a browse selection: the queue comes from the id, the position from the child id. */
    private fun playFromBrowse(mediaId: String) {
        startEngine()
        startForegroundSafely()
        mediaSession?.isActive = true
        Thread({
            val spec = runCatching { MediaBrowse.playback(this, mediaId) }.getOrElse {
                Log.w(TAG, "Can't resolve $mediaId: ${it.message}")
                null
            }
            if (spec == null) {
                Log.w(TAG, "Nothing playable for $mediaId")
                return@Thread
            }
            FilePlayer.get(this).play(spec.tracks, spec.index, spec.title, radio = spec.radio)
        }, "CabinEQ-CarPlay").start()
    }

    /** "Play <something>" from the car's assistant. */
    /**
     * The assistant's transcript plus what it understood about it: the kind of thing asked for
     * and, when it could tell, the artist, album and title.
     */
    private fun voiceRequest(query: String, extras: Bundle?): dev.alamo3.cabineq.music.VoiceRequest {
        fun text(key: String) = runCatching { extras?.getString(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return dev.alamo3.cabineq.music.VoiceRequest(
            query = query,
            focus = dev.alamo3.cabineq.music.VoiceRequest.focusOf(text(android.provider.MediaStore.EXTRA_MEDIA_FOCUS)),
            artist = text(android.provider.MediaStore.EXTRA_MEDIA_ARTIST),
            album = text(android.provider.MediaStore.EXTRA_MEDIA_ALBUM),
            title = text(android.provider.MediaStore.EXTRA_MEDIA_TITLE),
            playlist = text("android.intent.extra.playlist"),
            // Not usually sent for media requests, but free to use when it is.
            alternatives = runCatching { extras?.getStringArrayList(android.speech.RecognizerIntent.EXTRA_RESULTS) }
                .getOrNull().orEmpty().filter { !it.equals(query, ignoreCase = true) }.take(3),
        )
    }

    private fun playFromSearchQuery(query: String) {
        if (query.isBlank()) return
        playFromVoice(dev.alamo3.cabineq.music.VoiceRequest(query))
    }

    private fun playFromVoice(request: dev.alamo3.cabineq.music.VoiceRequest) {
        Log.i(TAG, "voice request: focus=${request.focus} fields=" +
            listOfNotNull("artist".takeIf { request.artist != null }, "album".takeIf { request.album != null },
                "title".takeIf { request.title != null }, "playlist".takeIf { request.playlist != null }) +
            " alternatives=${request.alternatives.size}")
        startEngine()
        startForegroundSafely()
        mediaSession?.isActive = true
        Thread({
            val spec = runCatching { MediaBrowse.voicePlayback(this, request) }.getOrNull()
            if (spec == null) {
                Log.w(TAG, "Nothing to play for the voice request")
                // Say so where the car shows it, instead of silently doing nothing.
                EngineState.playerMessage = if (request.isOpenEnded) "Nothing to play yet — like or play a few songs first."
                    else "Couldn't find “${dev.alamo3.cabineq.music.VoiceRequest.clean(request.query).take(60)}”."
                return@Thread
            }
            FilePlayer.get(this).play(spec.tracks, spec.index, spec.title, radio = spec.radio)
        }, "CabinEQ-CarSearch").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A media button when we have no session yet (process was killed, car wants to start
        // playing): come up, then hand the key to the session like the system would.
        if (intent?.action == ACTION_MEDIA_BUTTON) {
            startEngine()
            startForegroundSafely()
            val session = ensureMediaSession()
            session.isActive = true
            val key = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            }
            if (key != null) session.controller.dispatchMediaButtonEvent(key)
            return START_NOT_STICKY
        }
        // Notification media buttons. Only meaningful while we're already running.
        val action = intent?.action
        when (action) {
            ACTION_PLAYER_PREV, ACTION_PLAYER_TOGGLE, ACTION_PLAYER_NEXT -> {
                if (!EngineState.running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val player = FilePlayer.get(this)
                when (action) {
                    ACTION_PLAYER_PREV -> player.previous()
                    ACTION_PLAYER_TOGGLE -> player.togglePause()
                    ACTION_PLAYER_NEXT -> player.next()
                }
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData: Intent? = intent?.let {
            if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") it.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        }
        val wantsDsp = resultCode == Activity.RESULT_OK && resultData != null && hasRecordPermission()

        // Every startForegroundService() needs a matching startForeground(). The
        // mediaProjection type is only allowed once the user has granted the projection.
        val hasLocation = hasLocationPermission()
        val projectionType = if (wantsDsp || projection != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        startForegroundSafely(projectionType)

        val wasRunning = EngineState.running
        startEngine(hasLocation)

        if (intent?.action == ACTION_PLAYER_ATTACH) {
            ensureMediaSession()
            if (wasRunning) {
                // The player was just started; the EQ/DSP side is already set up.
                applyGlobalMode()
                return START_NOT_STICKY
            }
        }

        if (wantsDsp && capture == null) {
            startDsp(resultCode, resultData!!)
        } else if (capture == null && intent?.action == ACTION_PLAYER_ATTACH) {
            EngineState.dspMessage = null  // started for the in-app player, which has its own DSP
        } else if (capture == null) {
            EngineState.dspMessage = when {
                !settings.dspEngine -> "DSP engine off — using Android EQ"
                !hasRecordPermission() -> "Microphone/audio permission denied — using Android EQ"
                else -> "Audio capture not granted — using Android EQ"
            }
        }
        applyGlobalMode()
        return START_NOT_STICKY
    }

    /**
     * Registers the session receiver, the prefs listener, GPS and the 10 Hz ticker exactly once.
     * [onStartCommand] and the car's first "play" both come through here.
     */
    private fun startEngine(hasLocation: Boolean = hasLocationPermission()) {
        if (EngineState.running) return
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        registerSessionReceiver(filter)
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        if (hasLocation) startLocation()
        EngineState.running = true
        handler.post(ticker)
    }

    /** Foreground with the types we actually qualify for, from a start command or from the car. */
    private fun startForegroundSafely(extraTypes: Int = 0) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        type = type or extraTypes
        runCatching { startForeground(NOTIF_ID, buildNotification(), type) }
            .onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(sessionReceiver) }
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        locationManager?.removeUpdates(locationListener)
        capture?.stop()
        capture = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        FilePlayer.peek()?.stop()
        mediaSession?.release()
        mediaSession = null
        queueSignature = null
        muter.unmuteAll()
        effects.releaseAll()
        sessionPackages.clear()
        EngineState.running = false
        EngineState.gpsActive = false
        EngineState.dspActive = false
        publishSessions()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------
    // DSP path

    private fun startDsp(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection unavailable")
            mp.registerCallback(projectionCallback, handler)
            projection = mp
            val engine = CaptureEngine(this, mp)
            if (!engine.start()) {
                stopDsp("DSP failed to start: ${engine.lastError} — using Android EQ")
                return
            }
            capture = engine
            stoppedForProjection = false
            EngineState.dspActive = true
            EngineState.dspSampleRate = engine.sampleRate
            EngineState.dspMessage = null
            lastSentInputGain = Float.NaN  // force a full parameter push
            lastSentBands.fill(Float.NaN)
            rerouteAllSessions()
            Log.i(TAG, "DSP started at ${engine.sampleRate} Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start DSP", e)
            stopDsp("DSP failed to start: ${e.message} — using Android EQ")
        }
    }

    /** Tears down capture and moves every session back to the Android EQ path. */
    private fun stopDsp(reason: String) {
        Log.w(TAG, "Stopping DSP: $reason")
        capture?.stop()
        capture = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            if (!stoppedForProjection) runCatching { it.stop() }
        }
        projection = null
        EngineState.dspActive = false
        EngineState.dspOutputPaused = true
        EngineState.dspMessage = reason
        rerouteAllSessions()
        updateNotification()
    }

    private fun dspRunning() = capture?.running == true

    /** Sends one session down the DSP path (mute + capture) or the Android EQ path. */
    private fun routeSession(session: Int, pkg: String) {
        val useDsp = dspRunning() && pkg !in settings.captureBlockedPackages
        if (useDsp) {
            effects.detach(session)
            muter.mute(session)
        } else {
            muter.unmute(session)
            effects.attach(session)
        }
    }

    private fun rerouteAllSessions() {
        for ((session, pkg) in sessionPackages.toMap()) {
            if (session != GLOBAL_SESSION) routeSession(session, pkg)
        }
        applyGlobalMode()
        publishSessions()
    }

    // ---------------------------------------------------------------------------------

    // Before API 33 the exported flag doesn't exist; dynamically registered receivers
    // are exported by default there, which is what we need.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSessionReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
        }
    }

    private fun applyGlobalMode() {
        // With DSP or the file player running, an effect on the global mix would also hit
        // our own output and EQ it twice.
        if (settings.globalMode && !dspRunning() && FilePlayer.peek()?.isActive != true) {
            if (effects.attach(GLOBAL_SESSION)) sessionPackages[GLOBAL_SESSION] = "global mix (session 0)"
        } else {
            effects.detach(GLOBAL_SESSION)
            sessionPackages.remove(GLOBAL_SESSION)
        }
        publishSessions()
    }

    @SuppressLint("MissingPermission") // checked by hasLocationPermission() before calling
    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        locationManager = lm
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper()
            )
            EngineState.gpsActive = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start GPS", e)
            EngineState.gpsActive = false
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtimeNanos()

        // --- Speed -------------------------------------------------------------------
        val rawKmh = when {
            settings.simulateSpeed -> settings.simulatedKmh
            // In a tunnel / lost fix: hold the last speed for a while, then let it decay to 0.
            SystemClock.elapsedRealtime() - lastGpsFixElapsed < GPS_HOLD_MS -> lastGpsKmh
            else -> 0f
        }
        val kmh = speedComp.update(rawKmh, now)
        val amount = if (settings.speedCompEnabled)
            SpeedCompensator.amount(kmh, settings.speedStartKmh, settings.speedFullKmh) else 0f
        SpeedCompensator.bandBoosts(amount, settings.speedMaxBoostDb, speedBoosts)

        // --- Targets -----------------------------------------------------------------
        var maxSpeedBoost = 0f
        for (i in 0 until Bands.COUNT) {
            val user = settings.bandGain(i)
            maxSpeedBoost = max(maxSpeedBoost, speedBoosts[i])
            targetBands[i] = (user + speedBoosts[i]).coerceIn(-MAX_TOTAL_DB, MAX_TOTAL_DB)
        }
        // Headroom: the preamp (auto = recommended, or the user's manual value) covers the
        // static EQ. Only half of the speed boost is offset — the point of speed
        // compensation is to get louder, and the limiter catches the remaining peaks.
        val targetInput = settings.effectivePreampDb() - HEADROOM_SHARE_FOR_SPEED * maxSpeedBoost

        // --- Slew-limited ramp -------------------------------------------------------
        if (!initialised) {
            targetBands.copyInto(currentBands)
            currentInputGain = targetInput
            initialised = true
        } else {
            for (i in 0 until Bands.COUNT) currentBands[i] = slew(currentBands[i], targetBands[i])
            currentInputGain = slew(currentInputGain, targetInput)
        }

        // Android EQ path (sessions not on DSP)
        effects.apply(currentBands, currentInputGain)

        // In-app file player (TorBox)
        FilePlayer.peek()?.let { player ->
            player.setEq(currentBands, currentInputGain, LIMITER_THRESHOLD_LIN)
            tickPlayer(player)
        }

        // DSP path
        capture?.let { engine ->
            if (!engine.running) {
                stopDsp("DSP stopped: ${engine.lastError ?: "unknown error"} — using Android EQ")
                return@let
            }
            pushDspParams(engine)
            checkDspHealth(engine)
            engine.readMeters()?.let { m ->
                EngineState.dspInPeakDb = toDb(m[0])
                EngineState.dspOutPeakDb = toDb(m[1])
                EngineState.dspLimiterDb = toDb(m[2])
            }
            EngineState.dspOutputPaused = engine.outputPaused
            EngineState.dspRoute = engine.routedDeviceType()?.let { deviceName(it) }
        }

        // --- Status for the UI -------------------------------------------------------
        EngineState.rawKmh = rawKmh
        EngineState.smoothedKmh = kmh
        EngineState.speedAmount = amount
        EngineState.appliedBands = currentBands.copyOf()
        EngineState.appliedInputGain = currentInputGain
        EngineState.sessionsWithoutControl = effects.sessionsWithoutControl()
        val live = effects.sessionIds + muter.sessionIds
        if (!sessionPackages.keys.all { it in live }) {
            sessionPackages.keys.retainAll(live)
            publishSessions()
        }
    }

    private fun pushDspParams(engine: CaptureEngine) {
        var changed = abs(currentInputGain - lastSentInputGain) > 0.01f || lastSentInputGain.isNaN()
        for (i in 0 until Bands.COUNT) {
            if (lastSentBands[i].isNaN() || abs(currentBands[i] - lastSentBands[i]) > 0.01f) changed = true
        }
        if (!changed) return
        val coeffs = EqDesign.designAll(currentBands, engine.sampleRate.toDouble())
        engine.setParams(coeffs, Bands.COUNT, dbToLin(currentInputGain), LIMITER_THRESHOLD_LIN)
        currentBands.copyInto(lastSentBands)
        lastSentInputGain = currentInputGain
    }

    /**
     * Capture blocked: media is playing, apps are muted, yet the capture is silent. The muted
     * app(s) opted out of capture -> remember them and send them to Android EQ.
     */
    private fun checkDspHealth(engine: CaptureEngine) {
        val nowMs = SystemClock.elapsedRealtime()
        val dspPackages = muter.sessionIds.mapNotNull { sessionPackages[it] }.toSet()
        if (dspPackages.isNotEmpty() && engine.outputPaused &&
            nowMs - engine.lastSignalAt > BLOCKED_SILENCE_MS &&
            audioManager.isMusicActive &&
            FilePlayer.peek()?.isActive != true  // our own player also makes isMusicActive true
        ) {
            Log.w(TAG, "Capture silent while media is active; marking blocked: $dspPackages")
            settings.markCaptureBlocked(dspPackages)  // triggers rerouteAllSessions via prefs listener
            EngineState.dspMessage = "${dspPackages.joinToString()} blocks audio capture — using Android EQ for it"
        }
    }

    // ---------------------------------------------------------------------------------
    // File player

    private fun tickPlayer(player: FilePlayer) {
        val active = player.isActive
        if (active != lastPlayerActive) {
            lastPlayerActive = active
            applyGlobalMode()
        }

        // A silent output sink would otherwise freeze the UI at 0:00 with no explanation.
        if (player.recoverStalledOutput()) {
            EngineState.playerMessage = "Audio output stalled — paused. " +
                "The output device stopped consuming audio."
        }

        if (player.state == FilePlayer.State.PLAYING) EngineState.playerMessage = null

        player.readMeters()?.let { m ->
            EngineState.playerInPeakDb = toDb(m[0])
            EngineState.playerOutPeakDb = toDb(m[1])
            EngineState.playerLimiterDb = toDb(m[2])
            EngineState.playerTruePeakDb = toDb(m[3])
            if (m[4] > 0f) EngineState.playerClicks += m[4].toLong()
        }
        EngineState.playerRoute = player.routedDeviceType()?.let { deviceName(it) }

        if (player.version != lastPlayerVersion) {
            lastPlayerVersion = player.version
            updateMediaSession(player)
            updateNotification()
        }
    }

    private fun ensureMediaSession(): MediaSession {
        mediaSession?.let { return it }
        val session = MediaSession(this, "CabinEQ")
        // What the car and the lock screen open when the user taps Now Playing.
        session.setSessionActivity(
            PendingIntent.getActivity(
                this, 6,
                Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        // Where the system sends media keys / "play" when no session is alive yet.
        // Mutable on purpose: the media session service fills in the KeyEvent, and an immutable
        // PendingIntent makes that send fail with PendingIntent.CanceledException.
        val mediaButtonFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        session.setMediaButtonReceiver(
            PendingIntent.getBroadcast(
                this, 2,
                Intent(this, MediaButtonReceiver::class.java).setAction(Intent.ACTION_MEDIA_BUTTON),
                mediaButtonFlags,
            )
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = FilePlayer.get(this@EqService).resume()
            override fun onPause() = FilePlayer.get(this@EqService).pause()
            override fun onSkipToNext() = FilePlayer.get(this@EqService).next()
            override fun onSkipToPrevious() = FilePlayer.get(this@EqService).previous()
            override fun onStop() = FilePlayer.get(this@EqService).stop()
            override fun onSeekTo(pos: Long) = FilePlayer.get(this@EqService).seekTo(pos)
            override fun onSkipToQueueItem(id: Long) {
                val player = FilePlayer.get(this@EqService)
                val index = player.queue.indexOfFirst { queueItemId(it) == id }
                if (index >= 0) player.skipTo(index)
            }
            /** Android Auto / the lock screen show thumbs up/down when the session offers it. */
            override fun onSetRating(rating: Rating) {
                val track = FilePlayer.get(this@EqService).currentTrack ?: return
                if (!rating.isRated) return
                if (rating.isThumbUp) Taste.like(this@EqService, track)
                else Taste.dislike(this@EqService, track)
                Log.i(TAG, "rating: thumbUp=${rating.isThumbUp} for ${track.title}")
            }

            override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = playFromBrowse(mediaId)
            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                // An empty query is "play some music"; the extras say what was asked for.
                playFromVoice(voiceRequest(query.orEmpty(), extras))
            }
        })
        mediaSession = session
        lastPlayerVersion = -1  // force a state push on the next tick
        return session
    }

    /** The car's "up next" list, which it can also skip within. Ids are queue positions. */
    /** Identity of a queue entry for MediaSession clients (see [pushQueue]). */
    private fun queueItemId(track: Track): Long = track.mediaId.hashCode().toLong() and 0xFFFFFFFFL

    private fun pushQueue(session: MediaSession, player: FilePlayer) {
        val queue = player.queue
        val signature = "${queue.size}|${player.queueTitle}|${queue.hashCode()}"
        if (signature == queueSignature) return
        queueSignature = signature
        session.setQueue(queue.map { track ->
            MediaSession.QueueItem(
                MediaDescription.Builder()
                    .setMediaId("qi:${track.mediaId}")
                    .setTitle(track.title)
                    .setSubtitle(track.artist ?: track.album)
                    .build(),
                // Stable across queue edits: a Jam add/remove shifts indices, and a client that
                // cached the old queue would otherwise skip to the wrong song.
                queueItemId(track),
            )
        })
    }

    private fun updateMediaSession(player: FilePlayer) {
        val session = mediaSession ?: return
        val state = when (player.state) {
            FilePlayer.State.PLAYING -> PlaybackState.STATE_PLAYING
            FilePlayer.State.PAUSED -> PlaybackState.STATE_PAUSED
            FilePlayer.State.LOADING -> PlaybackState.STATE_BUFFERING
            FilePlayer.State.ERROR -> PlaybackState.STATE_ERROR
            FilePlayer.State.IDLE -> PlaybackState.STATE_STOPPED
        }
        val speed = if (state == PlaybackState.STATE_PLAYING) 1f else 0f
        val playback = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
                    PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH or
                    PlaybackState.ACTION_SET_RATING
            )
            .setState(state, player.positionMs, speed)
            // Our buffer is the AudioTrack's (~200 ms), so "buffered" is the play head.
            .setBufferedPosition(player.positionMs)
            .setActiveQueueItemId(
                player.currentTrack?.let(::queueItemId) ?: -1L
            )
        if (state == PlaybackState.STATE_ERROR) playback.setErrorMessage(player.lastError ?: "Playback error")
        session.setPlaybackState(playback.build())
        session.isActive = true
        pushQueue(session, player)

        val track = player.currentTrack
        val info = player.currentInfo
        // Cover: embedded in the file, else from the source, else an iTunes match for the song.
        val fallback = when {
            track == null -> null
            track.artist != null -> "${track.artist} ${track.title}"
            info != null && info.artist != null -> "${info.artist} ${info.title ?: track.title}"
            else -> info?.title ?: track.title
        }
        val waitForTags = track != null && track.artworkUrl == null && info == null
        val art = "${track?.artworkUrl}|$fallback|${info?.art?.let { System.identityHashCode(it) }}|$waitForTags"
        // Car displays and the lock screen show this; the art bitmap arrives asynchronously.
        session.setMetadata(buildMetadata(player, track, if (art == sessionArtUrl) sessionArt else null))
        if (art != sessionArtUrl) {
            sessionArtUrl = art
            sessionArt = null
            val embedded = info?.art
            when {
                embedded != null -> {
                    sessionArt = embedded
                    session.setMetadata(buildMetadata(player, track, embedded))
                    updateNotification()
                }
                waitForTags -> Unit   // re-run when the file's tags arrive (version bump)
                else -> dev.alamo3.cabineq.music.Artwork.loadBitmap(this, track?.artworkUrl, ART_PX, fallback) { bmp ->
                    if (sessionArtUrl != art || mediaSession == null) return@loadBitmap
                    sessionArt = bmp
                    mediaSession?.setMetadata(buildMetadata(player, player.currentTrack, bmp))
                    updateNotification()
                }
            }
        }
    }

    private fun buildMetadata(player: FilePlayer, track: Track?, art: android.graphics.Bitmap?): MediaMetadata {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE,
                (if (track?.artist == null) player.currentInfo?.title else null) ?: track?.title ?: player.currentTitle ?: "")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track?.artist ?: player.currentInfo?.artist ?: player.formatInfo ?: "CabinEQ")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track?.album ?: player.queueTitle ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, player.durationMs)
        // The car draws its own artwork from a URI it can open itself; the bitmap below is for
        // the lock screen / notification. Covers resolved here also work for streamed files with
        // no embedded art (the iTunes fallback query).
        val fallbackQuery = when {
            track == null -> null
            track.artist != null -> "${track.artist} ${track.title}"
            player.currentInfo?.artist != null ->
                "${player.currentInfo?.artist} ${player.currentInfo?.title ?: track.title}"
            else -> player.currentInfo?.title ?: track.title
        }
        ArtworkProvider.uriFor(
            url = track?.artworkUrl,
            query = if (track?.artworkUrl == null) fallbackQuery else null,
            sizePx = ART_PX,
        )?.let { b.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it.toString()) }
        art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        return b.build()
    }

    private fun slew(current: Float, target: Float): Float {
        val d = target - current
        return if (abs(d) <= MAX_STEP_DB) target else current + MAX_STEP_DB * (if (d > 0) 1f else -1f)
    }

    private fun publishSessions() {
        EngineState.sessions = LinkedHashMap(sessionPackages)
        EngineState.sessionModes = sessionPackages.keys.associateWith { id ->
            if (id in muter.sessionIds) "DSP" else "Android EQ"
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasRecordPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EQ running", NotificationManager.IMPORTANCE_LOW)
        )
        // Music is the app; the EQ screen is secondary.
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MusicActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cabineq)
            .setContentTitle(
                when {
                    capture != null -> "CabinEQ is running"
                    FilePlayer.peek()?.isActive == true -> "CabinEQ player"
                    else -> "CabinEQ is idle"
                }
            )
            .setContentText(
                when {
                    capture != null -> "Native DSP active"
                    else -> "Nothing playing"
                }
            )
            .setContentIntent(openApp)
            .setOngoing(true)

        val player = FilePlayer.peek()
        val session = mediaSession
        if (player != null && player.isActive && session != null) {
            // While music plays, the notification opens the player screen.
            builder.setContentIntent(PendingIntent.getActivity(
                this, 5, Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ))
            fun action(label: String, action: String, code: Int) = Notification.Action.Builder(
                null, label,
                PendingIntent.getService(this, code, Intent(this, EqService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
            ).build()
            builder
                .setContentTitle(player.currentTitle ?: "CabinEQ player")
                .setContentText(listOfNotNull(player.currentTrack?.artist, player.formatInfo).joinToString(" · "))
            sessionArt?.let { builder.setLargeIcon(it) }
            builder
                .addAction(action("Prev", ACTION_PLAYER_PREV, 2))
                .addAction(action(if (player.state == FilePlayer.State.PAUSED) "Play" else "Pause", ACTION_PLAYER_TOGGLE, 3))
                .addAction(action("Next", ACTION_PLAYER_NEXT, 4))
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        } else {
            builder.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "CabinEQ/Service"
        const val ACTION_STOP = "dev.alamo3.cabineq.STOP"
        /** Sent when the in-app player starts, so the service runs (EQ updates, MediaSession). */
        const val ACTION_PLAYER_ATTACH = "dev.alamo3.cabineq.PLAYER_ATTACH"
        const val ACTION_MEDIA_BUTTON = "dev.alamo3.cabineq.MEDIA_BUTTON"
        const val ACTION_PLAYER_PREV = "dev.alamo3.cabineq.PLAYER_PREV"
        const val ACTION_PLAYER_TOGGLE = "dev.alamo3.cabineq.PLAYER_TOGGLE"
        const val ACTION_PLAYER_NEXT = "dev.alamo3.cabineq.PLAYER_NEXT"
        const val EXTRA_PROJECTION_CODE = "projection_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "eq_running"
        private const val NOTIF_ID = 1
        private const val GLOBAL_SESSION = 0

        private const val TICK_MS = 100L
        private const val ART_PX = 512
        private const val MAX_STEP_DB = 0.4f          // per tick -> max 4 dB/s; speed is already smoothed upstream
        private const val MAX_TOTAL_DB = 15f
        private const val HEADROOM_SHARE_FOR_SPEED = 0.5f
        private const val LIMITER_THRESHOLD_LIN = 0.891f  // -1 dBFS

        private const val BLOCKED_SILENCE_MS = 2500L

        private const val GPS_INTERVAL_MS = 1000L
        private const val GPS_HOLD_MS = 30_000L

        fun dbToLin(db: Float): Float = 10f.pow(db / 20f)
        fun toDb(lin: Float): Float = if (lin <= 1e-6f) -120f else 20f * log10(lin)

        fun deviceName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "phone speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headphones"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix (Android Auto / cast)"
            AudioDeviceInfo.TYPE_BUS -> "bus (car / Android Auto)"
            else -> "device type $type"
        }
    }
}
