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
        setContentView(ScrollView(this).apply { addView(col) })
        showStatus(null)
    }

    private fun showStatus(extra: String?) {
        val prefs = getSharedPreferences("yt", Context.MODE_PRIVATE)
        status.text = buildString {
            append(Yt.initError?.let { "yt-dlp failed to start: $it" } ?: "yt-dlp: installed")
            prefs.getString("updateText", null)?.let { append("\nLast update: $it") }
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
