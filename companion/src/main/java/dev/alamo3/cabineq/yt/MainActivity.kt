package dev.alamo3.cabineq.yt

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Status and a manual test. CabinEQ never opens this; it talks to [ResolveService]. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { textSize = 15f }
        col.addView(TextView(this).apply {
            text = "CabinEQ YouTube companion\n\nCabinEQ asks this app for songs; nothing to do here. " +
                "Below: the state of yt-dlp, and a test search."
            textSize = 15f
        })
        col.addView(status)
        col.addView(Button(this).apply {
            text = "Check for an app update"
            setOnClickListener {
                if (!packageManager.canRequestPackageInstalls()) {
                    // One-time: Android has to allow this app to install its own updates.
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:$packageName")))
                } else run("Checking GitHub…") { SelfUpdate.check(this@MainActivity, manual = true) }
            }
        })
        col.addView(Button(this).apply {
            text = "Update yt-dlp now"
            setOnClickListener { run("Updating yt-dlp…") { Yt.update(this@MainActivity) } }
        })
        val query = EditText(this).apply { hint = "Artist - Title" }
        col.addView(query)
        col.addView(Button(this).apply {
            text = "Test"
            setOnClickListener {
                val q = query.text.toString()
                val artist = q.substringBefore(" - ", "").trim().ifBlank { null }
                val title = q.substringAfter(" - ").trim()
                run("Searching…") {
                    val t0 = System.currentTimeMillis()
                    val r = Yt.resolve(this@MainActivity, title, artist, null)
                    "${r.title}\n${r.channel} · ${r.durationSec} s · ${r.quality}\nin ${System.currentTimeMillis() - t0} ms"
                }
            }
        })
        val root = ScrollView(this).apply { addView(col) }
        // Edge to edge (target 35): keep the content clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // For "update ready, tap to install" when Android wants a confirmation.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        showStatus(null)
    }

    private fun showStatus(extra: String?) {
        val prefs = getSharedPreferences("yt", Context.MODE_PRIVATE)
        status.text = buildString {
            append(Yt.initError?.let { "yt-dlp failed to start: $it" } ?: "yt-dlp: installed")
            prefs.getString("updateText", null)?.let { append("\nLast update: $it") }
            append("\nApp version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
            SelfUpdate.lastResult?.let { append(" — $it") }
            if (!packageManager.canRequestPackageInstalls()) append("\nTap \"Check for an app update\" once to allow automatic updates.")
            extra?.let { append("\n\n$it") }
        }
    }

    private fun run(busy: String, work: () -> String) {
        showStatus(busy)
        Thread {
            val text = runCatching(work).getOrElse { "Failed: ${it.message}" }
            runOnUiThread { showStatus(text) }
        }.start()
    }
}
