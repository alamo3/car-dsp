package dev.alamo3.cabineq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Media buttons when nothing is playing yet (steering wheel, headset, the car asking us to
 * start). Without this the system has no session of ours to route the key to after the process
 * died; it starts [EqService], which dispatches the key to the session.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val key = intent.mediaButtonKey() ?: return
        val service = Intent(context, EqService::class.java)
            .setAction(EqService.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, key)
        // Media buttons are a user action, so starting the foreground service is allowed.
        runCatching { context.startForegroundService(service) }
    }

    private fun Intent.mediaButtonKey(): KeyEvent? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_KEY_EVENT)
}
