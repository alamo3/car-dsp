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
import android.media.audiofx.AudioEffect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
 */
class EqService : Service() {

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
    private var speakerSince = 0L
    private var stoppedForProjection = false

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
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
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (wantsDsp || projection != null) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForeground(NOTIF_ID, buildNotification(), type)

        if (!EngineState.running) {
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

        if (wantsDsp && capture == null) {
            startDsp(resultCode, resultData!!)
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
        // With DSP running, an effect on the global mix would also hit our own output.
        if (settings.globalMode && !dspRunning()) {
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
     * Two safety nets for the DSP path:
     *  1. Speaker safety: our output landing on the phone speaker (the RootlessJamesDSP +
     *     Android Auto failure) -> fall back to Android EQ.
     *  2. Capture blocked: media is playing, apps are muted, yet the capture is silent.
     *     The muted app(s) opted out of capture -> remember them and send them to Android EQ.
     */
    private fun checkDspHealth(engine: CaptureEngine) {
        val nowMs = SystemClock.elapsedRealtime()

        if (!settings.allowSpeakerOutput && engine.isRoutedToPhoneSpeaker()) {
            if (speakerSince == 0L) speakerSince = nowMs
            if (nowMs - speakerSince > SPEAKER_GRACE_MS) {
                speakerSince = 0L
                stopDsp("DSP output went to the phone speaker — switched to Android EQ. " +
                    "(Allow it under Testing if that's intended.)")
                return
            }
        } else {
            speakerSince = 0L
        }

        val dspPackages = muter.sessionIds.mapNotNull { sessionPackages[it] }.toSet()
        if (dspPackages.isNotEmpty() && engine.outputPaused &&
            nowMs - engine.lastSignalAt > BLOCKED_SILENCE_MS &&
            audioManager.isMusicActive
        ) {
            Log.w(TAG, "Capture silent while media is active; marking blocked: $dspPackages")
            settings.markCaptureBlocked(dspPackages)  // triggers rerouteAllSessions via prefs listener
            EngineState.dspMessage = "${dspPackages.joinToString()} blocks audio capture — using Android EQ for it"
        }
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
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("CabinEQ is running")
            .setContentText(if (capture != null) "Native DSP active" else "Android EQ active")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "CabinEQ/Service"
        const val ACTION_STOP = "dev.alamo3.cabineq.STOP"
        const val EXTRA_PROJECTION_CODE = "projection_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "eq_running"
        private const val NOTIF_ID = 1
        private const val GLOBAL_SESSION = 0

        private const val TICK_MS = 100L
        private const val MAX_STEP_DB = 0.4f          // per tick -> max 4 dB/s; speed is already smoothed upstream
        private const val MAX_TOTAL_DB = 15f
        private const val HEADROOM_SHARE_FOR_SPEED = 0.5f
        private const val LIMITER_THRESHOLD_LIN = 0.891f  // -1 dBFS

        private const val SPEAKER_GRACE_MS = 1500L
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
