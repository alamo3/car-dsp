package dev.alamo3.cabineq.jam

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.alamo3.cabineq.FilePlayer
import dev.alamo3.cabineq.Settings
import dev.alamo3.cabineq.Track
import dev.alamo3.cabineq.addon.EclipseAddon
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Host side of the Jam session.
 *
 * The phone is authoritative: it owns the queue, the credentials and the playback. The relay
 * (Firebase Realtime Database) only carries guest commands and state snapshots, addressed by an
 * unguessable session id:
 *
 *   jam/<sid>/info      "{createdAt}"                       (created once)
 *   jam/<sid>/ops/<key> guest command (append, then deleted by the host)
 *   jam/<sid>/state     snapshot the guest page renders      (host writes)
 *   jam/<sid>/results/<reqId>  search results for one guest request
 *
 * No Firebase SDK, no API key: plain REST over HTTPS, so guests need no account and no app.
 */
object JamHost {
    private const val TAG = "CabinEQ/Jam"
    private const val MAX_SEEN = 2000
    private const val MAX_QUEUE = 500
    private const val BASE_DELAY_MS = 700L
    private const val RATE_WINDOW_MS = 10_000L
    private const val RATE_MAX_OPS = 12
    private const val DB = "https://cabineq-jam-81cd8-default-rtdb.firebaseio.com"
    const val WEB = "https://cabineq-jam-81cd8.web.app"

    @Volatile var running = false
        private set
    @Volatile var sessionId: String? = null
        private set
    /** Guests may skip to the next track. */
    @Volatile var canSkip = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Jam").apply { isDaemon = true }
    }
    private val seen = HashSet<String>()
    /** Search answers we published this session; deleted when it ends. */
    private val reqIds = HashSet<String>()

    /** Set when an op changed something, so the next loop iteration publishes state at once. */
    @Volatile private var dirty = false

    /** Simple per-guest budget: one guest cannot drown the queue in commands. */
    private class Budget(var windowStart: Long, var count: Int)
    private val budgets = HashMap<String, Budget>()

    private fun withinBudget(guest: String): Boolean = synchronized(budgets) {
        val now = SystemClock.elapsedRealtime()
        val b = budgets.getOrPut(guest.ifBlank { "?" }) { Budget(now, 0) }
        if (now - b.windowStart > RATE_WINDOW_MS) {
            b.windowStart = now
            b.count = 0
        }
        ++b.count <= RATE_MAX_OPS
    }

    fun sessionUrl(sid: String): String = "$WEB/#$sid"

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        appContext = app
        val settings = Settings(app)
        val sid = randomId()
        sessionId = sid
        seen.clear()
        running = true
        val orphan = settings.lastJamSession?.takeIf { it != sid }
        settings.lastJamSession = sid
        io.execute {
            // A previous session may have been orphaned by a crash: the database has no
            // enumeration (by design), so the phone remembers what it created and wipes it.
            orphan?.let {
                Log.i(TAG, "Wiping orphaned session $it")
                wipe(it)
            }
            put("$sid/info.json", JSONObject().put("createdAt", System.currentTimeMillis()))
            var lastState = 0L
            var failures = 0
            while (running && sessionId == sid) {
                var ok = false
                try {
                    ok = pollOps(app, sid)
                    val now = System.currentTimeMillis()
                    // An applied op must show up immediately, not on the next 900 ms tick: the
                    // guest is looking at "adding…" until this state lands.
                    if (dirty || now - lastState > 900) {
                        dirty = false
                        lastState = now
                        ok = publishState(sid) && ok
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "loop: ${e.javaClass.simpleName}: ${e.message}")
                }
                // Back off while the relay is unreachable instead of hammering it twice a second.
                failures = if (ok) 0 else (failures + 1).coerceAtMost(4)
                Thread.sleep(BASE_DELAY_MS shl failures)
            }
            Log.i(TAG, "Session $sid stopped")
        }
        Log.i(TAG, "Session $sid started: ${sessionUrl(sid)}")
    }

    fun stop() {
        val sid = sessionId
        running = false
        sessionId = null
        if (sid != null) {
            appContext?.let { Settings(it).lastJamSession = null }
            Log.i(TAG, "Ending session $sid")
            cleanIo.execute { wipe(sid) }
        }
    }

    /** Holds the application context for the cleanup above (the relay itself is stateless). */
    private var appContext: Context? = null

    /**
     * Removes every child the rules let us delete, so ended sessions don't pile up. The node
     * itself has no write rule (that would hand guests a kill switch), so collections are
     * enumerated and removed entry by entry.
     */
    private fun wipe(sid: String) {
        val ops = keysOf("$sid/ops.json")
        ops.forEach { delete("$sid/ops/$it.json") }
        val guests = keysOf("$sid/guests.json")
        guests.forEach { delete("$sid/guests/$it.json") }
        val results = reqIds.toList()
        results.forEach { delete("$sid/results/$it.json") }
        // DELETE bodies are "null" on success, so ask the HTTP layer for success instead.
        val stateOk = delete("$sid/state.json") != null
        val infoOk = delete("$sid/info.json") != null
        Log.i(TAG, "Wiped $sid: ${ops.size} ops, ${guests.size} guests, ${results.size} results, " +
            "state=$stateOk, info=$infoOk")
    }

    private fun keysOf(path: String): List<String> {
        val body = get(path) ?: return emptyList()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        return o.keys().asSequence().toList()
    }

    // ---------------------------------------------------------------- ops

    private fun pollOps(app: Context, sid: String): Boolean {
        val body = get("$sid/ops.json") ?: return false
        val all = runCatching { JSONObject(body) }.getOrNull() ?: return false
        if (seen.size > MAX_SEEN) seen.clear()
        for (key in all.keys()) {
            if (!seen.add(key)) continue
            val op = all.optJSONObject(key) ?: continue
            if (!withinBudget(op.optString("guest"))) {
                Log.w(TAG, "op $key dropped: guest ${op.optString("guest")} over budget")
            } else {
                runCatching { apply(app, sid, op) }
                    .onFailure { Log.w(TAG, "op $key (${op.optString("op")}) failed: ${it.message}") }
            }
            // Consumed either way: keep the node small.
            delete("$sid/ops/$key.json")
        }
        return true
    }

    private fun apply(app: Context, sid: String, op: JSONObject) {
        val player = FilePlayer.get(app)
        dirty = true
        when (op.optString("op")) {
            "add" -> op.optJSONObject("track")?.let {
                if (player.queue.size >= MAX_QUEUE) {
                    Log.w(TAG, "queue full ($MAX_QUEUE), add ignored")
                } else {
                    player.enqueue(listOf(trackFrom(it)))
                    // A jam must not go quiet because the last queued song finished: if the
                    // player is idle (not paused by someone), start the song that was just added.
                    if (player.state == FilePlayer.State.IDLE && !player.userPaused) {
                        player.skipTo(player.queue.lastIndex)
                    }
                }
            }
            "remove" -> player.removeUpcoming(op.optString("key"))
            "move" -> player.moveUpcoming(op.optString("key"), op.optInt("to", 0))
            "vote" -> {
                // A vote nudges the track one place up, at most.
                val keys = player.queuedTracks().map { it.key }
                val i = keys.indexOf(op.optString("key"))
                if (i > player.currentIndex + 1) player.moveUpcoming(op.optString("key"), i - 1)
            }
            "skip" -> if (canSkip) player.next()
            "search" -> answerSearch(app, sid, op)
        }
        player.version.let { }   // no-op: keeps the linter honest about the instance use
    }

    /**
     * Cleanup must not queue behind the polling loop (which can be asleep in a backoff), so it
     * gets its own thread: pressing "End jam" kills the session immediately.
     */
    private val cleanIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-JamClean").apply { isDaemon = true }
    }

    /** Searches can take seconds; keep them off the polling loop so ops stay responsive. */
    private val searchIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-JamSearch").apply { isDaemon = true }
    }

    private fun answerSearch(app: Context, sid: String, op: JSONObject) {
        searchIo.execute { answerSearchNow(app, sid, op) }
    }

    private fun answerSearchNow(app: Context, sid: String, op: JSONObject) {
        val link = Settings(app).addonLink
        val reqId = op.optString("reqId")
        if (link.isBlank() || reqId.isBlank()) return
        val query = op.optString("q")
        reqIds.add(reqId)
        val tracks = runCatching { EclipseAddon(link).search(query).tracks }
            .getOrDefault(emptyList())
        val items = JSONArray()
        tracks.take(20).forEach { t ->
            items.put(
                JSONObject()
                    .put("title", t.title)
                    .put("artist", t.artist)
                    .put("album", t.album ?: JSONObject.NULL)
                    .put("art", t.artworkUrl ?: JSONObject.NULL)
                    .put("dur", (t.durationSec ?: 0) * 1000L)
                    .put("source", "ADDON")
                    .put("id", t.id)
                    .put("url", t.streamUrl ?: JSONObject.NULL)
            )
        }
        put(
            "$sid/results/$reqId.json",
            JSONObject().put("at", System.currentTimeMillis()).put("items", items)
        )
    }

    // ---------------------------------------------------------------- state

    private fun publishState(sid: String): Boolean {
        val player = FilePlayer.peek() ?: return false
        val queue = JSONArray()
        player.queuedTracks().forEach { entry ->
            queue.put(trackTo(entry.track).put("key", entry.key))
        }
        val state = JSONObject()
            .put("playing", player.state == FilePlayer.State.PLAYING)
            .put("canSkip", canSkip)
            // The page shows only what is still ahead; it needs to know where that starts.
            .put("currentIndex", player.currentIndex)
            .put("queue", queue)
            .put("updatedAt", System.currentTimeMillis())
        player.currentTrack?.let {
            state.put("nowPlaying", trackTo(it))
            state.put("positionMs", player.positionMs)
        }
        return put("$sid/state.json", state) != null
    }

    // ---------------------------------------------------------------- json

    private fun trackTo(t: Track): JSONObject = JSONObject()
        .put("title", t.title)
        .put("artist", t.artist ?: JSONObject.NULL)
        .put("album", t.album ?: JSONObject.NULL)
        .put("art", t.artworkUrl ?: JSONObject.NULL)
        .put("dur", t.durationMs)
        .put("source", t.source.name)
        .put("isrc", t.isrc ?: JSONObject.NULL)

    private fun trackFrom(o: JSONObject): Track = Track(
        title = o.optString("title"),
        artist = o.optString("artist").takeIf { it.isNotBlank() },
        album = o.optString("album").takeIf { it.isNotBlank() },
        artworkUrl = o.optString("art").takeIf { it.isNotBlank() },
        durationMs = o.optLong("dur"),
        source = if (o.optString("source") == "TORBOX") Track.Source.TORBOX else Track.Source.ADDON,
        sourceId = o.optString("id").takeIf { it.isNotBlank() },
        // Deliberately ignore a guest-supplied "url": only ids are honoured, so the phone never
        // downloads something an arbitrary guest chose.
        isrc = o.optString("isrc").takeIf { it.isNotBlank() },
    )

    // ---------------------------------------------------------------- rest

    private fun randomId(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun get(path: String): String? = http("GET", path)
    private fun put(path: String, body: JSONObject): String? = http("PUT", path, body.toString())
    private fun delete(path: String): String? = http("DELETE", path)

    private fun http(method: String, path: String, body: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$DB/jam/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 5000
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else {
                Log.w(TAG, "$method $path -> HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
