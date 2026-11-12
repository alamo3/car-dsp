package dev.alamo3.cabineq

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Playback diagnostics, sent to the app's Firebase Realtime Database (the one Jam uses, plain
 * REST, no SDK) so a problem seen in the car can be read afterwards without a cable and adb.
 *
 * Each install writes under `diag/<id>`, where the id is random and long: the rules let anyone
 * who knows an id read it, but nobody can list the ids. Settings shows a link to the log, which
 * is what gets shared when something goes wrong.
 *
 * Never sent: links (every URL is cut down to its host: TorBox links and the Nebula link carry
 * the key) and the TorBox key itself, wherever it appears. Off switch in Settings → Playback.
 */
object Diag {
    private const val TAG = "CabinEQ/Diag"
    private const val DB = "https://cabineq-jam-81cd8-default-rtdb.firebaseio.com"
    private const val MAX_BUFFER = 2000
    private const val BATCH = 200
    private const val FLUSH_EVERY_MS = 15_000L
    private const val SOON_MS = 2_000L
    private const val PER_MINUTE = 150
    private const val MAX_MESSAGE = 500

    @Volatile private var app: Context? = null
    private val lock = Any()
    private val buffer = ArrayDeque<JSONObject>()
    private var lastKeyMs = 0L
    private var keyCounter = 0
    private var minuteStart = 0L
    private var inMinute = 0
    private var dropped = 0
    @Volatile private var flushQueued = false
    @Volatile private var metaSent = false
    // Read from Settings at start and on every flush, not per log line (which runs on audio paths).
    @Volatile private var on = true
    @Volatile private var secrets: List<String> = emptyList()

    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "CabinEQ-Diag").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private val urlPattern = Regex("""https?://([^/\s"'?#]+)[^\s"']*""")

    /** Safe to call from anywhere, any number of times. */
    fun init(context: Context) {
        if (app != null) return
        synchronized(lock) {
            if (app != null) return
            app = context.applicationContext
        }
        refresh(context.applicationContext)
        io.scheduleWithFixedDelay({ runCatching { flush() } }, FLUSH_EVERY_MS, FLUSH_EVERY_MS, TimeUnit.MILLISECONDS)
        record("I", TAG, "app start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    fun enabled(context: Context): Boolean = Settings(context).diagnosticsEnabled

    fun setEnabled(context: Context, value: Boolean) {
        Settings(context).diagnosticsEnabled = value
        on = value
        if (!value) synchronized(lock) { buffer.clear() }
    }

    private fun refresh(ctx: Context) {
        val s = Settings(ctx)
        on = s.diagnosticsEnabled
        secrets = listOf(s.torboxApiKey, s.addonLink).filter { it.length >= 8 }
    }

    /** The random id this install writes under (created on first use). */
    fun id(context: Context): String {
        val s = Settings(context)
        s.diagnosticsId.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        s.diagnosticsId = id
        return id
    }

    /** What to share: the newest events, readable in a browser. */
    fun link(context: Context): String =
        "$DB/diag/${id(context)}/events.json?orderBy=%22%24key%22&limitToLast=400&print=pretty"

    /**
     * Deletes everything this install has sent and starts over under a new id, so an old link
     * (or an id that got out) reads nothing from now on.
     */
    fun clear(context: Context) {
        val old = id(context)
        synchronized(lock) { buffer.clear() }
        Settings(context).diagnosticsId = ""
        metaSent = false
        io.execute {
            http("DELETE", "diag/$old/events.json", null)
            http("DELETE", "diag/$old/meta.json", null)
        }
    }

    fun record(level: String, tag: String, message: String, tr: Throwable? = null) {
        if (!on) return
        val now = System.currentTimeMillis()
        var text = message
        if (tr != null) text += " | ${tr.javaClass.simpleName}: ${tr.message}"
        val event = JSONObject()
        synchronized(lock) {
            val minute = SystemClock.elapsedRealtime() / 60_000L
            if (minute != minuteStart) {
                if (dropped > 0) buffer.addLast(entry(now, "W", TAG, "$dropped events not sent (too many in a minute)"))
                minuteStart = minute
                inMinute = 0
                dropped = 0
            }
            if (++inMinute > PER_MINUTE) { dropped++; return }
            event.put("k", key(now))
            event.put("at", now)
            event.put("l", level)
            event.put("t", tag.removePrefix("CabinEQ/").take(40))
            event.put("m", scrub(text).take(MAX_MESSAGE))
            buffer.addLast(event)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
        if (level == "W" || level == "E") flushSoon()
    }

    /** Sends what is buffered now-ish: a warning is worth having before the app is killed. */
    fun flushSoon() {
        if (flushQueued) return
        flushQueued = true
        io.schedule({ flushQueued = false; runCatching { flush() } }, SOON_MS, TimeUnit.MILLISECONDS)
    }

    private fun entry(now: Long, level: String, tag: String, m: String) = JSONObject()
        .put("k", key(now)).put("at", now).put("l", level).put("t", tag.removePrefix("CabinEQ/")).put("m", m)

    /** Sortable and unique per install: the time, then a counter for events in the same ms. */
    private fun key(now: Long): String {
        if (now == lastKeyMs) keyCounter++ else { lastKeyMs = now; keyCounter = 0 }
        return "%013d-%03d".format(now, keyCounter % 1000)
    }

    private fun scrub(text: String): String {
        var out = text
        // Secrets first (the Nebula link is itself a URL), then every remaining link.
        secrets.forEach { secret -> out = out.replace(secret, "<secret>") }
        return urlPattern.replace(out) { "<${it.groupValues[1]}>" }
    }

    private fun flush() {
        val ctx = app ?: return
        refresh(ctx)
        if (!on) { synchronized(lock) { buffer.clear() }; return }
        val id = id(ctx)
        if (!metaSent) metaSent = sendMeta(ctx, id)
        while (true) {
            val batch = synchronized(lock) {
                val list = ArrayList<JSONObject>(minOf(BATCH, buffer.size))
                val it = buffer.iterator()
                while (it.hasNext() && list.size < BATCH) list += it.next()
                list
            }
            if (batch.isEmpty()) return
            val body = JSONObject()
            // The database refuses events more than two weeks old (a phone offline that long):
            // left in, they would get the whole batch refused.
            val oldest = System.currentTimeMillis() - 13 * 24 * 3600_000L
            batch.filter { it.optLong("at") >= oldest }.forEach { e ->
                // Scrubbed again: a line logged before the secrets were known is caught here.
                val copy = JSONObject(e.toString()).apply { remove("k"); put("m", scrub(optString("m"))) }
                body.put(e.getString("k"), copy)
            }
            // HttpURLConnection has no PATCH; Firebase accepts the override on a POST.
            val ok = http("POST", "diag/$id/events.json?x-http-method-override=PATCH", body.toString())
            if (!ok) return          // offline: keep them for the next round
            synchronized(lock) { repeat(batch.size) { if (buffer.isNotEmpty()) buffer.removeFirst() } }
            if (batch.size < BATCH) return
        }
    }

    private fun sendMeta(ctx: Context, id: String): Boolean {
        val s = Settings(ctx)
        val meta = JSONObject()
            .put("version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
            .put("android", Build.VERSION.SDK_INT)
            .put("engine", if (s.media3Player) "media3" else "built-in")
            .put("endless", s.endlessPlayback)
            .put("progressive", s.progressivePlayback)
            .put("floatDecoding", s.floatDecoding)
            .put("updatedAt", System.currentTimeMillis())
        return http("PUT", "diag/$id/meta.json", meta.toString())
    }

    private fun http(method: String, path: String, body: String?): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$DB/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 8_000
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { it.readBytes() }
                true
            } else {
                // Plain Log on purpose: logging this through Diag would feed the failure back in.
                Log.w(TAG, "$method diag -> HTTP $code")
                runCatching { conn.errorStream?.close() }
                code in 400..499 && code != 408 && code != 429   // a rejected batch is dropped, not retried forever
            }
        } catch (e: Exception) {
            conn?.disconnect()
            false
        }
    }
}

/**
 * Drop-in for android.util.Log in the playback code: logs to logcat exactly as before, and also
 * records info, warnings and errors for [Diag].
 */
object DLog {
    fun d(tag: String, msg: String, tr: Throwable? = null): Int =
        if (tr != null) Log.d(tag, msg, tr) else Log.d(tag, msg)

    fun i(tag: String, msg: String, tr: Throwable? = null): Int {
        Diag.record("I", tag, msg, tr)
        return if (tr != null) Log.i(tag, msg, tr) else Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null): Int {
        Diag.record("W", tag, msg, tr)
        return if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null): Int {
        Diag.record("E", tag, msg, tr)
        return if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
}
