package dev.alamo3.cabineq

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub releases.
 *
 * CI tags each release `v0.1.<run number>` and builds it with versionCode = <run number>,
 * so the trailing number of the tag is directly comparable to the installed versionCode.
 *
 * Installation goes through a PackageInstaller session (no FileProvider needed). Once
 * CabinEQ itself has installed an update it becomes the "installer of record", and from
 * then on Android 12+ can apply updates without a confirmation prompt.
 */
object Updater {
    private const val REPO = "alamo3/car-dsp"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val ACTION_INSTALL_STATUS = "dev.alamo3.cabineq.INSTALL_STATUS"

    data class Release(val tag: String, val versionCode: Long, val apkUrl: String, val apkSize: Long)

    /**
     * True when Google Play installed this copy. Play updates it itself, and an app installed
     * from Play must not fetch and install APKs from elsewhere (it is against Play's policy and
     * the two would fight over the installer of record).
     */
    fun installedFromPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (android.os.Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Blocking; call off the main thread. Returns null if there is no usable release. */
    fun fetchLatest(): Release? {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CabinEQ-updater")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null  // 404 = no releases yet
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(tag, code, a.getString("browser_download_url"), a.optLong("size", -1))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Blocking download into the app cache. [onProgress] gets 0..100 (or -1 if unknown). */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val dest = File(context.cacheDir, "update.apk")
        // GitHub redirects asset downloads to its CDN (https -> https, followed automatically).
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CabinEQ-updater")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Download failed: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (pct != lastPct) {
                            onProgress(pct)
                            lastPct = pct
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return dest
    }

    /**
     * Hands the APK to the system installer. The result comes back to MainActivity as an
     * intent with [ACTION_INSTALL_STATUS]; if the user has to confirm, that intent carries
     * the confirmation screen to launch.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_INSTALL_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Must be mutable: the installer fills in the status extras.
            val pending = PendingIntent.getActivity(
                context, sessionId, statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
    }
}
