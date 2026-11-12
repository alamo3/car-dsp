package dev.alamo3.cabineq.yt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the companion itself current from CabinEQ's GitHub releases (each carries
 * cabineq-youtube-v0.1.<run>.apk; versionCode = <run>). Checked once a day on an unmetered
 * connection, or on demand from the status screen.
 *
 * Android asks the user to confirm the first update (the browser or file manager installed this
 * copy). After that the companion is the installer of record and Android 12+ applies its updates
 * without asking; when it does ask, a notification opens the confirmation.
 */
object SelfUpdate {
    private const val TAG = "CabinEQYt/Update"
    private const val LATEST = "https://api.github.com/repos/alamo3/car-dsp/releases/latest"
    private const val CHANNEL = "updates"
    const val ACTION_STATUS = "dev.alamo3.cabineq.yt.UPDATE_STATUS"

    @Volatile var lastResult: String? = null
        private set

    /** Returns what happened, for the status screen. Blocking. */
    fun check(context: Context, manual: Boolean): String {
        val prefs = context.getSharedPreferences("yt", Context.MODE_PRIVATE)
        if (!manual) {
            if (System.currentTimeMillis() - prefs.getLong("appCheckedAt", 0) < CompanionApp.DAY_MS) return "checked today"
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm?.isActiveNetworkMetered != false) return "waiting for Wi-Fi"
        }
        prefs.edit().putLong("appCheckedAt", System.currentTimeMillis()).apply()
        val result = runCatching { checkNow(context) }.getOrElse { "app update failed: ${it.message}" }
        lastResult = result
        Log.i(TAG, result)
        return result
    }

    private fun checkNow(context: Context): String {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CabinEQ-YouTube-updater")
        }
        val json = try {
            if (conn.responseCode != 200) return "no release found (HTTP ${conn.responseCode})"
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
        val tag = json.getString("tag_name")
        val code = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return "unreadable release tag $tag"
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        if (code <= installed) return "app is up to date ($tag)"
        val assets = json.getJSONArray("assets")
        val url = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").let { n -> n.endsWith(".apk") && "youtube" in n } }
            ?.getString("browser_download_url") ?: return "$tag has no companion APK"
        val apk = download(context, url)
        install(context, apk)
        return "installing $tag"
    }

    private fun download(context: Context, url: String): File {
        val dest = File(context.cacheDir, "update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CabinEQ-YouTube-updater")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("download HTTP ${conn.responseCode}")
            conn.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        } finally { conn.disconnect() }
        return dest
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = Intent(context, StatusReceiver::class.java).setAction(ACTION_STATUS)
            val pending = PendingIntent.getBroadcast(context, id, status,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    /** The installer's answer: done, failed, or "the user has to confirm" (then a notification). */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                    notifyConfirm(context, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "updated")
                else -> Log.w(TAG, "update failed ($s): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    private fun notifyConfirm(context: Context, confirm: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, 1, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("CabinEQ YouTube update ready")
            .setContentText("Tap to install it")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build())
    }
}
