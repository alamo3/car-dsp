package dev.alamo3.cabineq

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Deliberately plain, framework-only UI for milestone 1: big controls that are easy to
 * hit, a live status panel, and a "simulate speed" slider for testing while parked.
 */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val bandControls = ArrayList<Pair<SeekBar, TextView>>()

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
        requestNeededPermissions()
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

        startStopButton = Button(this).apply {
            textSize = 18f
            minHeight = dp(56)
            setOnClickListener { toggleService() }
        }
        root.addView(startStopButton)

        statusText = TextView(this).apply {
            setPadding(0, dp(12), 0, dp(12))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
        }
        root.addView(statusText)

        // --- EQ ---
        root.addView(header("Equalizer"))
        for (i in 0 until Bands.COUNT) {
            val pair = slider(
                root,
                format = { "${Bands.label(i)}  ${signed(it)} dB" },
                min = -Bands.MAX_BAND_DB, max = Bands.MAX_BAND_DB, step = 0.5f,
                initial = settings.bandGain(i),
            ) { settings.setBandGain(i, it) }
            bandControls.add(pair)
        }
        slider(
            root, format = { "Preamp  ${signed(it)} dB" },
            min = -12f, max = 6f, step = 0.5f, initial = settings.preampDb,
        ) { settings.preampDb = it }

        root.addView(Button(this).apply {
            text = "Reset EQ"
            setOnClickListener {
                settings.resetEq()
                recreate()
            }
        })

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

        return ScrollView(this).apply { addView(root) }
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

    private fun refreshStatus() {
        val s = EngineState
        startStopButton.text = if (s.running) "Stop CabinEQ" else "Start CabinEQ"

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
                    sb.append("  #$id  $pkg$lost\n")
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
        }
        statusText.text = sb.toString()
        statusText.gravity = Gravity.START
    }

    // ---------------------------------------------------------------------------------
    // Service + permissions

    private fun toggleService() {
        if (EngineState.running) {
            startService(Intent(this, EqService::class.java).setAction(EqService.ACTION_STOP))
        } else {
            startForegroundService(Intent(this, EqService::class.java))
        }
        handler.postDelayed({ refreshStatus() }, 300)
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
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
}
