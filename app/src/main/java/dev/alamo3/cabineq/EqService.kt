package dev.alamo3.cabineq

import android.Manifest
import android.annotation.SuppressLint
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
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Foreground service that:
 *  1. listens for music apps announcing their audio sessions and attaches the EQ to them,
 *  2. optionally attaches to the global output mix (session 0, deprecated but sometimes works),
 *  3. reads GPS speed and adds speed-dependent compensation,
 *  4. ramps all gain changes smoothly at 10 Hz so adjustments are inaudible.
 */
class EqService : Service() {

    private lateinit var settings: Settings
    private val effects = EffectManager()
    private val speedComp = SpeedCompensator()
    private val handler = Handler(Looper.getMainLooper())
    private var locationManager: LocationManager? = null

    private val sessionPackages = LinkedHashMap<Int, String>()

    // Smoothed state actually being applied.
    private val currentBands = FloatArray(Bands.COUNT)
    private var currentInputGain = 0f
    private var initialised = false

    // Scratch arrays to avoid allocating on every tick.
    private val targetBands = FloatArray(Bands.COUNT)
    private val speedBoosts = FloatArray(Bands.COUNT)

    private var lastGpsKmh = 0f
    private var lastGpsFixElapsed = 0L

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return  // 0 is the global mix, handled separately
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown"
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    Log.i(TAG, "Session opened: $session ($pkg)")
                    if (effects.attach(session)) sessionPackages[session] = pkg
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    Log.i(TAG, "Session closed: $session ($pkg)")
                    effects.detach(session)
                    sessionPackages.remove(session)
                }
            }
            publishSessions()
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Settings.KEY_GLOBAL) applyGlobalMode()
        // Everything else is picked up by the ticker on its next run.
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Always (re)call startForeground: every startForegroundService() needs a matching call.
        val hasLocation = hasLocationPermission()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        startForeground(NOTIF_ID, buildNotification(), type)
        if (EngineState.running) return START_NOT_STICKY

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // Must be exported: the broadcasts come from other apps (music players).
        registerSessionReceiver(filter)

        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applyGlobalMode()
        if (hasLocation) startLocation()

        EngineState.running = true
        handler.post(ticker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(sessionReceiver) }
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        locationManager?.removeUpdates(locationListener)
        effects.releaseAll()
        sessionPackages.clear()
        EngineState.running = false
        EngineState.gpsActive = false
        publishSessions()
        super.onDestroy()
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
        if (settings.globalMode) {
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
        effects.apply(currentBands, currentInputGain)

        // --- Status for the UI -------------------------------------------------------
        EngineState.rawKmh = rawKmh
        EngineState.smoothedKmh = kmh
        EngineState.speedAmount = amount
        EngineState.appliedBands = currentBands.copyOf()
        EngineState.appliedInputGain = currentInputGain
        EngineState.sessionsWithoutControl = effects.sessionsWithoutControl()
        if (sessionPackages.keys != effects.sessionIds) {
            sessionPackages.keys.retainAll(effects.sessionIds)
            publishSessions()
        }
    }

    private fun slew(current: Float, target: Float): Float {
        val d = target - current
        return if (abs(d) <= MAX_STEP_DB) target else current + MAX_STEP_DB * (if (d > 0) 1f else -1f)
    }

    private fun publishSessions() {
        EngineState.sessions = LinkedHashMap(sessionPackages)
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
            .setContentText("EQ + speed compensation active")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "CabinEQ/Service"
        const val ACTION_STOP = "dev.alamo3.cabineq.STOP"
        private const val CHANNEL_ID = "eq_running"
        private const val NOTIF_ID = 1
        private const val GLOBAL_SESSION = 0

        private const val TICK_MS = 100L
        private const val MAX_STEP_DB = 0.4f          // per tick -> max 4 dB/s; speed is already smoothed upstream
        private const val MAX_TOTAL_DB = 15f
        private const val HEADROOM_SHARE_FOR_SPEED = 0.5f

        private const val GPS_INTERVAL_MS = 1000L
        private const val GPS_HOLD_MS = 30_000L
    }
}
