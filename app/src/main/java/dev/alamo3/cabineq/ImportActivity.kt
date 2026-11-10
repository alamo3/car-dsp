package dev.alamo3.cabineq

import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Entry point for "share to CabinEQ" and `cabineq://import` links.
 *
 * It carries nothing itself: the share text (or the file's content) is handed to MusicActivity,
 * which owns the import screen, and this finishes immediately so the user never sees a blank
 * window for it.
 */
class ImportActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.takeIf { it.isNotBlank() }
        // A shared playlist file (JSON/CSV) arrives as a stream, not text. Our read grant is for
        // this activity only, so pass it on with the URI rather than reading it here on the main
        // thread.
        @Suppress("DEPRECATION")
        val stream = if (text == null && intent?.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        } else null
        if (text == null && stream == null) {
            Toast.makeText(this, "Nothing to import", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val forward = Intent(this, MusicActivity::class.java)
            .setAction(MusicActivity.ACTION_IMPORT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (text != null) {
            forward.putExtra(MusicActivity.EXTRA_IMPORT_TEXT, text)
        } else {
            forward.putExtra(Intent.EXTRA_STREAM, stream)
            forward.clipData = android.content.ClipData.newRawUri("playlist", stream)
            forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(forward)
        finish()
    }
}
