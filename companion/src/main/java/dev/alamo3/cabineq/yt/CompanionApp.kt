package dev.alamo3.cabineq.yt

import android.app.Application
import android.content.Context

class CompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val app = this
        Thread({
            Yt.init(app)
            // yt-dlp tracks YouTube's changes in its releases: take the newest once a day.
            val prefs = getSharedPreferences("yt", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong("updatedAt", 0) > DAY_MS) runCatching { Yt.update(app) }
            // And the companion itself, from GitHub (daily, on Wi-Fi).
            runCatching { SelfUpdate.check(app, manual = false) }
        }, "CabinEQYt-Init").start()
    }

    companion object {
        const val DAY_MS = 24 * 3600_000L
    }
}
