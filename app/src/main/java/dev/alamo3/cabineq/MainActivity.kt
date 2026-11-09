package dev.alamo3.cabineq

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.alamo3.cabineq.music.Ui
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Deliberately plain, framework-only UI for milestone 1: big controls that are easy to
 * hit, a live status panel, and a "simulate speed" slider for testing while parked.
 */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var statusText: TextView
    private lateinit var cabinView: dev.alamo3.cabineq.cabin.ResponseView
    private lateinit var cabinSummary: TextView
    private lateinit var cabinButton: Button
    private var cabinMeasuring = false
    private val cabinExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Measure").apply { isDaemon = true }
    }

    private lateinit var outputPathText: TextView
    private var outputReport = ""
    private lateinit var startStopButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var eqView: EqView
    private lateinit var preampBar: SeekBar
    private lateinit var preampHint: TextView
    private lateinit var useRecommendedButton: Button
    private lateinit var dspButton: Button
    private lateinit var blockedButton: Button
    private lateinit var updateText: TextView
    private lateinit var updateButton: Button
    private var pendingRelease: Updater.Release? = null
    private var updateBusy = false

    private val statusUpdater = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildUi())
        handleInstallStatus(intent)
        checkForUpdate(userInitiated = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusUpdater)
    }

    override fun onPause() {
        handler.removeCallbacks(statusUpdater)
        super.onPause()
    }

    // ---------------------------------------------------------------------------------
    // UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        // Secondary screen: reachable from Music's settings, so give it a way back when it is
        // not the task root (system back works too, this is just visible).
        if (!isTaskRoot) {
            root.addView(TextView(this).apply {
                text = "‹  Music"
                textSize = 15f
                setTextColor(Ui.ACCENT)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(10))
                setOnClickListener { finish() }
            })
        }
        root.addView(TextView(this).apply {
            text = "Equalizer & DSP"
            textSize = 26f
            setPadding(0, dp(4), 0, dp(12))
        })

        root.addView(TextView(this).apply {
            text = "Shapes everything CabinEQ plays — search, Android Auto and Jam. " +
                "Capturing other apps is optional, at the bottom."
            textSize = 13f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(12))
        })

        // --- EQ ---
        root.addView(header("Equalizer"))
        eqView = EqView(this).apply {
            setGains(FloatArray(Bands.COUNT) { settings.bandGain(it) })
            onBandChanged = { band, db ->
                settings.setBandGain(band, db)
                refreshPreampUi()
            }
        }
        root.addView(eqView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))
        root.addView(TextView(this).apply {
            text = "Drag a band up or down.  Solid = your EQ · dashed orange = live output (incl. speed comp)"
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(8))
        })

        // Presets
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, values) in PRESETS) {
            presetRow.addView(Button(this).apply {
                text = name
                textSize = 13f
                setOnClickListener { applyPreset(values) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(presetRow)

        // Preamp + recommendation
        val (bar, _) = slider(
            root, format = { "Preamp  ${signed(it)} dB" },
            min = PREAMP_MIN, max = PREAMP_MAX, step = PREAMP_STEP, initial = settings.effectivePreampDb(),
        ) {
            settings.preampDb = it
            refreshPreampUi()
        }
        preampBar = bar
        preampHint = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(2), 0, dp(4))
        }
        root.addView(preampHint)
        val preampRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        preampRow.addView(switch("Auto preamp", settings.autoPreamp) {
            if (!it) settings.preampDb = settings.effectivePreampDb()  // start manual from where auto was
            settings.autoPreamp = it
            refreshPreampUi()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        useRecommendedButton = Button(this).apply {
            text = "Use recommended"
            setOnClickListener {
                settings.preampDb = Bands.recommendedPreamp(settings.bandGains())
                refreshPreampUi()
            }
        }
        preampRow.addView(useRecommendedButton)
        root.addView(preampRow)
        refreshPreampUi()

        // --- Status ---
        root.addView(header("Status"))
        statusText = TextView(this).apply {
            setPadding(0, dp(4), 0, dp(12))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
        }
        root.addView(statusText)

        // --- Output path ---
        root.addView(header("Output path"))
        root.addView(switch("Match device rate", settings.matchDeviceRate) { on ->
            settings.matchDeviceRate = on
            FilePlayer.peek()?.onRateSettingChanged()
            refreshOutputPath()
        })
        root.addView(TextView(this).apply {
            text = "On: streams are resampled to the rate Android mixes at — by CabinEQ's own " +
                "128-tap polyphase resampler in the native DSP — so the system mixer has nothing " +
                "to convert. Off: the file's rate goes to the mixer and Android resamples it."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(switch("Bluetooth high-frequency guard", settings.bluetoothHfGuard) { on ->
            settings.bluetoothHfGuard = on
            FilePlayer.peek()?.refreshRouteDependentParams()
            refreshOutputPath()
        })
        root.addView(TextView(this).apply {
            text = "On Bluetooth, low-pass at 15.5 kHz (24 dB/oct). A2DP cannot carry that band — " +
                "what arrives up there is codec noise, heard as crackle on bright material — so it " +
                "is removed before the codec. Wired and USB routes are untouched."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(switch("Volume to max in the car", settings.maxVolumeInCar) { on ->
            settings.maxVolumeInCar = on
            CarVolume.sync(this)
            refreshOutputPath()
        })
        root.addView(TextView(this).apply {
            text = "In car mode (Android Auto) the media stream goes to 100%, so the mixer's " +
                "volume attenuation is out of the path and the car's own volume sets the level. " +
                "It is put back when car mode ends, and only if it is still at maximum. Skipped " +
                "when the platform fixes the volume, over Bluetooth (there the phone's volume is " +
                "the car's) and, unless enabled below, when the sound comes out of the phone's " +
                "own speaker."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(switch("…also with the phone speaker", settings.maxVolumeIncludeSpeaker) { on ->
            settings.maxVolumeIncludeSpeaker = on
            CarVolume.sync(this)
            refreshOutputPath()
        })
        root.addView(Button(this).apply {
            text = "Set volume to max now"
            setOnClickListener {
                CarVolume.setMaxNow(this@MainActivity)
                android.widget.Toast.makeText(this@MainActivity, "Volume set", android.widget.Toast.LENGTH_SHORT).show()
                refreshOutputPath()
            }
        })
        outputPathText = TextView(this).apply {
            setPadding(0, dp(4), 0, dp(8))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        root.addView(outputPathText)
        root.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("CabinEQ audio path", outputReport))
                android.widget.Toast.makeText(this@MainActivity, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(TextView(this).apply {
            text = "Read-only: this is the path your audio takes today, measured from the framework. " +
                "Nothing here changes the sound."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(12))
        })

        // --- Cabin measurement ---
        root.addView(header("Cabin response"))
        root.addView(TextView(this).apply {
            text = "Plays a 10 s sweep and records it with the phone's microphone, so you can see " +
                "how the car colours the sound. Mic, cabin, speakers and the head unit all add up " +
                "in here, so the curve is relative, and only 100 Hz – 8 kHz is trustworthy. " +
                "Engine and air conditioning off, windows up, phone where you normally sit."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(8))
        })
        cabinView = dev.alamo3.cabineq.cabin.ResponseView(this)
        root.addView(cabinView, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        cabinSummary = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(cabinSummary)
        cabinButton = Button(this).apply {
            setOnClickListener { startCabinMeasure() }
        }
        root.addView(cabinButton)
        refreshCabinButton()

        // --- Speed compensation ---
        root.addView(header("Speed compensation"))
        root.addView(switch("Enabled", settings.speedCompEnabled) { settings.speedCompEnabled = it })
        slider(
            root, format = { "Max boost  ${fmt(it)} dB" },
            min = 0f, max = 12f, step = 0.5f, initial = settings.speedMaxBoostDb,
        ) { settings.speedMaxBoostDb = it }
        slider(
            root, format = { "Starts at  ${it.roundToInt()} km/h" },
            min = 0f, max = 100f, step = 5f, initial = settings.speedStartKmh,
        ) { settings.speedStartKmh = it }
        slider(
            root, format = { "Full at  ${it.roundToInt()} km/h" },
            min = 40f, max = 160f, step = 5f, initial = settings.speedFullKmh,
        ) { settings.speedFullKmh = it }

        // --- Other apps (optional) ---
        // The player has its own DSP: none of this is needed to play music through CabinEQ.
        root.addView(header("Eq other apps (optional)"))
        root.addView(TextView(this).apply {
            text = "Only for audio played by other apps (Spotify, podcasts, …). CabinEQ's own playback " +
                "always runs through the same DSP with or without this."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(8))
        })
        startStopButton = Button(this).apply {
            textSize = 16f
            minHeight = dp(48)
            setOnClickListener { toggleService() }
        }
        root.addView(startStopButton)
        if (settings.captureBlockedPackages.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "Some apps block capture: ${settings.captureBlockedPackages.joinToString()} — they get " +
                    "Android's own EQ instead."
                textSize = 12f
                alpha = 0.7f
                setPadding(0, dp(4), 0, 0)
            })
        }
        root.addView(switch("Native DSP (captures app audio)", settings.dspEngine) { settings.dspEngine = it })
        root.addView(TextView(this).apply {
            text = "On: CabinEQ captures your music app's audio, processes it in its own DSP engine and plays " +
                "it back, muting the original. Android asks for screen-capture permission each time it starts — " +
                "only audio is used. Apps that block capture automatically use the Android EQ instead.\n" +
                "Off: Android's built-in effect engine applies the EQ (no capture prompt). Takes effect on next start."
            textSize = 12f
            alpha = 0.7f
        })
        blockedButton = Button(this).apply {
            setOnClickListener {
                settings.resetCaptureBlocked()
                refreshBlockedButton()
            }
        }
        root.addView(blockedButton)
        refreshBlockedButton()
        dspButton = Button(this).apply {
            text = "Start native DSP for other apps"
            setOnClickListener { requestDspStart() }
        }
        root.addView(dspButton)

        // --- Testing ---
        root.addView(header("Testing"))
        root.addView(switch("Simulate speed (ignore GPS)", settings.simulateSpeed) { settings.simulateSpeed = it })
        slider(
            root, format = { "Simulated  ${it.roundToInt()} km/h" },
            min = 0f, max = 160f, step = 5f, initial = settings.simulatedKmh,
        ) { settings.simulatedKmh = it }
        root.addView(switch("Global mode (session 0, fallback)", settings.globalMode) { settings.globalMode = it })
        root.addView(TextView(this).apply {
            text = "Use global mode only if your music app never shows up in the session list. " +
                "It is deprecated by Android and may do nothing on some phones."
            textSize = 12f
            alpha = 0.7f
        })

        // --- Updates ---
        root.addView(header("Updates"))
        updateText = TextView(this).apply {
            textSize = 13f
            text = "Version ${Updater.installedVersionName(this@MainActivity)}"
        }
        root.addView(updateText)
        updateButton = Button(this).apply {
            text = "Check for updates"
            setOnClickListener { onUpdateButton() }
        }
        root.addView(updateButton)

        return ScrollView(this).apply {
            addView(root)
            padForSystemBars()
        }
    }

    private fun applyPreset(values: FloatArray) {
        for (i in 0 until Bands.COUNT) settings.setBandGain(i, values[i])
        eqView.setGains(values)
        refreshPreampUi()
    }

    /** Updates the preamp slider, recommendation text and controls from current settings. */
    private fun refreshPreampUi() {
        val gains = settings.bandGains()
        val rec = Bands.recommendedPreamp(gains)
        val peak = Bands.peakBoost(gains)
        val auto = settings.autoPreamp
        val current = settings.effectivePreampDb()

        // Setting progress programmatically doesn't count as fromUser, so it won't write prefs.
        preampBar.progress = ((current.coerceIn(PREAMP_MIN, PREAMP_MAX) - PREAMP_MIN) / PREAMP_STEP).roundToInt()
        preampBar.isEnabled = !auto
        useRecommendedButton.visibility = if (auto) View.GONE else View.VISIBLE

        val why = if (peak > 0f) "offsets your EQ's peak boost (+${fmt(peak)} dB)" else "no boosts, so no cut needed"
        preampHint.text = when {
            auto -> "Auto: following recommended ${signed(rec)} dB — $why"
            current > rec + 0.01f ->
                "Recommended ${signed(rec)} dB — $why.\n⚠ ${fmt(current - rec)} dB above it: loud passages will hit the limiter or crackle."
            current < rec - 0.01f ->
                "Recommended ${signed(rec)} dB — $why. You're ${fmt(rec - current)} dB below it (safe, just quieter)."
            else -> "At recommended ${signed(rec)} dB — $why"
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun switch(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 16f
        isChecked = initial
        minHeight = dp(48)
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    /** Adds a labelled slider for a float range to [parent]. */
    private fun slider(
        parent: LinearLayout,
        format: (Float) -> String,
        min: Float,
        max: Float,
        step: Float,
        initial: Float,
        onChange: (Float) -> Unit,
    ): Pair<SeekBar, TextView> {
        val label = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(8), 0, 0)
            text = format(initial)
        }
        val steps = ((max - min) / step).roundToInt()
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((initial.coerceIn(min, max) - min) / step).roundToInt()
            minimumHeight = dp(40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    label.text = format(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        parent.addView(label)
        parent.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        return bar to label
    }

    /**
     * The mixer's contribution, spelled out: rate, resampling, volume, direct-path support and
     * the route. Built from public API only — the vendor HAL (Dolby, SoundAlive) is invisible
     * to apps, so [AudioPath] says so rather than guessing.
     */
    private fun refreshOutputPath() {
        val facts = AudioPath.collect(this, FilePlayer.peek())
        val rows = AudioPath.rows(facts)
        val text = rows.joinToString("\n") { row ->
            String.format(Locale.US, "%-11s %s", row.label + ":", row.value)
        }
        outputReport = AudioPath.plain(facts)
        if (outputPathText.text?.toString() != text) outputPathText.text = text
    }

    /**
     * Slice 1 of the cabin measurement: run it, draw it, say how good the recording was. No
     * correction is applied yet — that comes once the curve can be trusted.
     */
    private fun startCabinMeasure() {
        if (cabinMeasuring) return
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }
        cabinMeasuring = true
        refreshCabinButton()
        cabinSummary.text = "Starting…"
        // Don't measure over music, and don't leave the player paused afterwards by accident.
        val player = FilePlayer.peek()
        val wasPlaying = player?.state == FilePlayer.State.PLAYING
        player?.pause()
        cabinExecutor.execute {
            val result = runCatching {
                dev.alamo3.cabineq.cabin.CabinMeasure.measure(
                    this,
                    onProgress = { stage, frac ->
                        runOnUiThread {
                            cabinSummary.text = "%s %d%%".format(java.util.Locale.US, stage, (frac * 100).toInt())
                        }
                    },
                )
            }.getOrNull()
            runOnUiThread {
                cabinMeasuring = false
                refreshCabinButton()
                if (result == null) {
                    cabinSummary.text = "Measurement failed — check microphone permission and that nothing else is recording."
                    return@runOnUiThread
                }
                cabinView.set(result.points)
                val worst = result.extremes(3)
                cabinSummary.text = buildString {
                    append("SNR %.0f dB · %d bands\n".format(java.util.Locale.US, result.snrDb, result.points.size))
                    for (p in worst) append("  %5.0f Hz  %+.1f dB\n".format(java.util.Locale.US, p.hz, p.db))
                    if (result.snrDb < 20) append("Low SNR: turn the volume up and measure again.")
                }
                if (wasPlaying) player?.resume()
            }
        }
    }

    private fun refreshCabinButton() {
        if (!::cabinButton.isInitialized) return
        cabinButton.isEnabled = !cabinMeasuring
        cabinButton.text = if (cabinMeasuring) "Measuring…" else "Measure cabin response"
    }

    private fun refreshStatus() {
        val s = EngineState
        startStopButton.text = if (s.running) "Stop eq for other apps" else "Start eq for other apps"
        refreshBlockedButton()
        dspButton.visibility = if (s.running && !s.dspActive && settings.dspEngine) View.VISIBLE else View.GONE
        eqView.setApplied(if (s.running) s.appliedBands else null)

        val sb = StringBuilder()
        sb.append(if (s.running) "● Running\n" else "○ Stopped\n")
        if (s.running) {
            sb.append("Sessions: ")
            if (s.sessions.isEmpty()) {
                sb.append("none yet — start/restart music playback\n")
            } else {
                sb.append('\n')
                for ((id, pkg) in s.sessions) {
                    val lost = if (id in s.sessionsWithoutControl) "  (control lost!)" else ""
                    val mode = s.sessionModes[id]?.let { " [$it]" } ?: ""
                    sb.append("  #$id  $pkg$mode$lost\n")
                }
            }
            val gps = when {
                settings.simulateSpeed -> "simulated"
                s.gpsActive -> "GPS on"
                !hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) -> "no location permission"
                else -> "GPS off"
            }
            sb.append(String.format(Locale.US, "Speed: %.0f km/h (smoothed %.0f) [%s]\n", s.rawKmh, s.smoothedKmh, gps))
            sb.append(String.format(Locale.US, "Speed comp: %.0f%%   Input gain: %+.1f dB\n", s.speedAmount * 100, s.appliedInputGain))
            sb.append("Applied: ")
            sb.append(s.appliedBands.joinToString(" ") { String.format(Locale.US, "%+.1f", it) })
            sb.append('\n')
            // In-app player (TorBox): always runs through its own native DSP engine.
            val player = FilePlayer.peek()?.takeIf { it.isActive }
            if (player != null) {
                sb.append("Player → native DSP: ${player.state.name.lowercase(Locale.US)}")
                s.playerRoute?.let { sb.append(" → ").append(it) }
                sb.append('\n')
                sb.append("  ${player.currentTitle ?: "?"}\n")
                player.formatInfo?.let { sb.append("  ").append(it).append('\n') }
                if (player.state == FilePlayer.State.PLAYING) {
                    sb.append(String.format(Locale.US, "  Peaks: in %.1f dBFS  out %.1f dBFS  limiter %.1f dB\n",
                        s.playerInPeakDb, s.playerOutPeakDb, s.playerLimiterDb))
                }
                s.playerMessage?.let { sb.append("  ⚠ ").append(it).append('\n') }
            }

            // Other apps' audio: capture DSP or the Android EQ fallback.
            if (s.dspActive) {
                sb.append(String.format(Locale.US, "Other apps → native DSP: %d Hz, %s\n", s.dspSampleRate,
                    if (s.dspOutputPaused) "idle (no audio)" else "processing → ${s.dspRoute ?: "?"}"))
                sb.append(String.format(Locale.US, "  Peaks: in %.1f dBFS  out %.1f dBFS  limiter %.1f dB",
                    s.dspInPeakDb, s.dspOutPeakDb, s.dspLimiterDb))
            } else {
                sb.append("Other apps → Android EQ (capture DSP not started)")
            }
            s.dspMessage?.let { sb.append("\n  ⚠ ").append(it) }
        }
        statusText.text = sb.toString()
        statusText.gravity = Gravity.START
        refreshOutputPath()
    }

    // ---------------------------------------------------------------------------------
    // Self-update

    private fun onUpdateButton() {
        if (updateBusy) return
        val release = pendingRelease
        if (release == null) checkForUpdate(userInitiated = true) else downloadAndInstall(release)
    }

    private fun checkForUpdate(userInitiated: Boolean) {
        updateBusy = true
        if (userInitiated) updateText.text = "Checking for updates…"
        Thread {
            val result = runCatching { Updater.fetchLatest() }
            runOnUiThread {
                updateBusy = false
                val installed = Updater.installedVersionCode(this)
                val current = "Version ${Updater.installedVersionName(this)}"
                val release = result.getOrNull()
                when {
                    result.isFailure -> {
                        if (userInitiated) updateText.text = "$current — update check failed: ${result.exceptionOrNull()?.message}"
                    }
                    release != null && release.versionCode > installed -> {
                        pendingRelease = release
                        updateText.text = "$current — update available: ${release.tag}"
                        updateButton.text = "Install ${release.tag}"
                    }
                    else -> {
                        pendingRelease = null
                        updateText.text = "$current — up to date"
                        updateButton.text = "Check for updates"
                    }
                }
            }
        }.start()
    }

    private fun downloadAndInstall(release: Updater.Release) {
        // Android needs a one-time "Install unknown apps" grant for CabinEQ.
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow CabinEQ to install apps, then tap Install again", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updateBusy = true
        updateButton.isEnabled = false
        Thread {
            try {
                val apk = Updater.download(this, release) { pct ->
                    runOnUiThread {
                        updateText.text = if (pct >= 0) "Downloading ${release.tag}… $pct%" else "Downloading ${release.tag}…"
                    }
                }
                runOnUiThread { updateText.text = "Installing ${release.tag}…" }
                Updater.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread {
                    updateText.text = "Update failed: ${e.message}"
                    updateBusy = false
                    updateButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != Updater.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                confirm?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return  // the final status arrives in another intent
            }
            PackageInstaller.STATUS_SUCCESS -> updateText.text = "Update installed"
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                updateText.text = "Update failed: $msg"
            }
        }
        updateBusy = false
        updateButton.isEnabled = true
    }

    // ---------------------------------------------------------------------------------
    // Service + permissions

    private fun toggleService() {
        // Ask only when the engine is actually being started; simply opening this screen must
        // not throw permission dialogs at the user.
        if (!EngineState.running) requestNeededPermissions()
        if (EngineState.running) {
            startService(Intent(this, EqService::class.java).setAction(EqService.ACTION_STOP))
        } else if (settings.dspEngine) {
            requestDspStart()
        } else {
            startForegroundService(Intent(this, EqService::class.java))
        }
        handler.postDelayed({ refreshStatus() }, 300)
    }

    /** Asks for audio + capture permission, then starts (or upgrades) the service with DSP. */
    private fun requestDspStart() {
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Whole display: "single app" sharing would limit audio capture to that one app.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_RECORD_AUDIO) return
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) {
            requestDspStart()
        } else {
            Toast.makeText(this, "Audio permission denied — starting with Android EQ", Toast.LENGTH_LONG).show()
            startForegroundService(Intent(this, EqService::class.java))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        val intent = Intent(this, EqService::class.java)
        if (resultCode == RESULT_OK && data != null) {
            intent.putExtra(EqService.EXTRA_PROJECTION_CODE, resultCode)
            intent.putExtra(EqService.EXTRA_PROJECTION_DATA, data)
        } else {
            Toast.makeText(this, "Capture not allowed — using Android EQ", Toast.LENGTH_LONG).show()
        }
        startForegroundService(intent)
        handler.postDelayed({ refreshStatus() }, 500)
    }

    private fun refreshBlockedButton() {
        val blocked = settings.captureBlockedPackages
        blockedButton.text = "Reset apps that block capture (${blocked.size})"
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filterNot { hasPerm(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------------------------
    // Helpers

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)
    private fun signed(v: Float) = String.format(Locale.US, "%+.1f", v)

    companion object {
        private const val REQ_RECORD_AUDIO = 2
        private const val REQ_PROJECTION = 3
        private const val PREAMP_MIN = -12f
        private const val PREAMP_MAX = 6f
        private const val PREAMP_STEP = 0.5f

        // 31, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k
        private val PRESETS = listOf(
            "Flat" to FloatArray(Bands.COUNT),
            "Bass" to floatArrayOf(6f, 5.5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Vocal" to floatArrayOf(-2f, -2f, -1f, 0f, 1.5f, 3f, 3f, 2f, 0f, 0f),
            "Loudness" to floatArrayOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 1.5f, 3f, 3f),
        )
    }
}
