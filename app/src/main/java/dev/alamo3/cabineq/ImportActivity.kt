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
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Nothing to import", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        startActivity(
            Intent(this, MusicActivity::class.java)
                .setAction(MusicActivity.ACTION_IMPORT)
                .putExtra(MusicActivity.EXTRA_IMPORT_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}
