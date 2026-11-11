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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
    /** With the push channel up, commands are collected when it says one arrived; this is the net. */
    private const val PUSH_FALLBACK_MS = 3_000L
    /** How often the loop glances at the player while idle: local and free, no network involved. */
    private const val IDLE_TICK_MS = 400L
    /** An unchanged state is re-sent this often, so guests can tell the host is alive. */
    private const val HEARTBEAT_MS = 5_000L
    private const val RATE_WINDOW_MS = 10_000L
    private const val RATE_MAX_OPS = 12
    /** All guests together: the guest id is self-chosen, so a per-guest budget alone is no cap. */
    private const val RATE_MAX_OPS_TOTAL = 40
    private const val MAX_FIELD = 300
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

    /** Released by the push channel the moment a guest command lands, ending the loop's wait. */
    private val wake = Semaphore(0)
    @Volatile private var pushLive = false
    @Volatile private var pushConn: HttpURLConnection? = null

    /** Commands already applied, still to be removed from the relay (off the critical path). */
    private val pendingDeletes = ConcurrentLinkedQueue<String>()
    private val deleteIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-JamDelete").apply { isDaemon = true }
    }

    /** The newest search each guest asked for: older ones still queued are not worth answering. */
    private val latestSearch = ConcurrentHashMap<String, String>()
    private class Searched(val at: Long, val tracks: List<EclipseAddon.Track>)
    private val searchCache = ConcurrentHashMap<String, Searched>()
    private const val SEARCH_CACHE_MS = 120_000L

    /** Set when an op changed something, so the next loop iteration publishes state at once. */
    @Volatile private var dirty = false

    /** Simple per-guest budget: one guest cannot drown the queue in commands. */
    private class Budget(var windowStart: Long, var count: Int)
    private val budgets = HashMap<String, Budget>()

    private val total = Budget(0L, 0)

    private fun withinBudget(guest: String): Boolean = synchronized(budgets) {
        val now = SystemClock.elapsedRealtime()
        if (now - total.windowStart > RATE_WINDOW_MS) {
            total.windowStart = now
            total.count = 0
            // Inventing guest ids must not grow this map forever either.
            budgets.entries.removeAll { now - it.value.windowStart > RATE_WINDOW_MS }
        }
        if (++total.count > RATE_MAX_OPS_TOTAL) return@synchronized false
        val b = budgets.getOrPut(guest.ifBlank { "?" }.take(64)) { Budget(now, 0) }
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
        pendingDeletes.clear()
        latestSearch.clear()
        synchronized(budgets) { budgets.clear() }
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
            startPush(sid)
            var lastState = 0L
            var lastOps = 0L
            var lastVersion = -1
            var failures = 0
            var woken = true
            while (running && sessionId == sid) {
                var ok = true
                try {
                    val now = SystemClock.elapsedRealtime()
                    // Guests' commands: when the push channel says one arrived, or on a timer. The
                    // push channel is the shortcut; the timer is what keeps this correct without it.
                    if (woken || !pushLive || now - lastOps > PUSH_FALLBACK_MS) {
                        lastOps = now
                        ok = pollOps(app, sid)
                    }
                    val version = FilePlayer.peek()?.version ?: -1
                    // An applied op must show up at once: the guest is looking at "adding…" until
                    // this state lands. Otherwise: when the player changed, or as a heartbeat.
                    if (dirty || version != lastVersion || now - lastState > HEARTBEAT_MS) {
                        dirty = false
                        lastState = now
                        lastVersion = version
                        ok = publishState(sid) && ok
                    }
                    // Only now: removing applied commands is bookkeeping, and a round trip each
                    // used to sit in front of the guest's answer.
                    flushDeletes(sid)
                } catch (e: Exception) {
                    Log.w(TAG, "loop: ${e.javaClass.simpleName}: ${e.message}")
                    ok = false
                }
                // Back off while the relay is unreachable instead of hammering it twice a second.
                failures = if (ok) 0 else (failures + 1).coerceAtMost(4)
                val wait = if (failures > 0 || !pushLive) BASE_DELAY_MS shl failures else IDLE_TICK_MS
                woken = wake.tryAcquire(wait, TimeUnit.MILLISECONDS)
                wake.drainPermits()
            }
            // stop() wipes from another thread, but this loop may have been mid-publish and
            // written the state back afterwards. It is the last writer, so it cleans up last.
            delete("$sid/state.json")
            Log.i(TAG, "Session $sid stopped")
        }
        Log.i(TAG, "Session $sid started: ${sessionUrl(sid)}")
    }

    fun stop() {
        val sid = sessionId
        running = false
        sessionId = null
        wake.release()                 // the loop is waiting: let it see that it is over
        pushConn?.disconnect()         // and so is the push channel's blocking read
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
        // Enumerated like the others (the node is readable), so an orphaned session's search
        // answers go too, not just the ones this process remembers.
        val results = keysOf("$sid/results.json")
        results.forEach { delete("$sid/results/$it.json") }
        // DELETE bodies are "null" on success, so ask the HTTP layer for success instead.
        val stateOk = delete("$sid/state.json") != null
        val infoOk = delete("$sid/info.json") != null
        Log.i(TAG, "Wiped $sid: ${ops.size} ops, ${guests.size} guests, ${results.size} results, " +
            "state=$stateOk, info=$infoOk")
    }

    private fun keysOf(path: String): List<String> {
        // shallow=true: keys only, not every entry's body.
        val body = get("$path?shallow=true") ?: return emptyList()
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
            // Consumed either way: removed from the relay later, after the state is published.
            pendingDeletes.add(key)
        }
        return true
    }

    private fun flushDeletes(sid: String) {
        if (pendingDeletes.isEmpty()) return
        deleteIo.execute {
            while (true) {
                val key = pendingDeletes.poll() ?: break
                delete("$sid/ops/$key.json")
            }
        }
    }

    /**
     * Firebase can push changes over a held-open request (server-sent events). It is used only as
     * a doorbell: any new guest command wakes the loop, which then fetches and applies it as
     * before. That makes a command start in about a round trip instead of waiting out a poll
     * interval, and if this connection fails the polling timer still does the job.
     */
    private fun startPush(sid: String) {
        Thread({
            var backoff = 1_000L
            while (running && sessionId == sid) {
                try {
                    val conn = (URL("$DB/jam/$sid/ops.json").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept", "text/event-stream")
                        connectTimeout = 5_000
                        readTimeout = 70_000        // the server sends a keep-alive about every 30 s
                    }
                    pushConn = conn
                    if (conn.responseCode in 200..299) {
                        pushLive = true
                        backoff = 1_000L
                        Log.i(TAG, "push channel up")
                        conn.inputStream.bufferedReader().use { reader ->
                            var event = ""
                            while (running && sessionId == sid) {
                                val line = reader.readLine() ?: break
                                if (line.startsWith("event:")) {
                                    event = line.substring(6).trim()
                                } else if (line.startsWith("data:") && (event == "put" || event == "patch")) {
                                    // "data":null is the initial empty node, a keep-alive or a removal.
                                    if (!line.contains("\"data\":null")) wake.release()
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "push channel -> HTTP ${conn.responseCode}")
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "push channel: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    pushLive = false
                    pushConn?.disconnect()
                    pushConn = null
                }
                if (!(running && sessionId == sid)) break
                runCatching { Thread.sleep(backoff) }
                backoff = (backoff * 2).coerceAtMost(10_000L)
            }
        }, "CabinEQ-JamPush").apply { isDaemon = true; start() }
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
    private val searchIo = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "CabinEQ-JamSearch").apply { isDaemon = true }
    }

    private fun answerSearch(app: Context, sid: String, op: JSONObject) {
        // Typing asks several questions in a row. Only a guest's newest is worth answering, and
        // answering them in turn (one thread, a second or two each) made the one that mattered
        // wait behind the rest.
        val guest = op.optString("guest").take(64)
        val reqId = op.optString("reqId")
        if (reqId.isNotBlank()) latestSearch[guest] = reqId
        searchIo.execute { answerSearchNow(app, sid, op) }
    }

    private fun answerSearchNow(app: Context, sid: String, op: JSONObject) {
        val link = Settings(app).addonLink
        val reqId = op.optString("reqId")
        if (link.isBlank() || reqId.isBlank() || reqId.length > 40) return
        // Becomes part of a database path: only the characters the web client generates.
        if (!reqId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return
        val guest = op.optString("guest").take(64)
        if (latestSearch[guest] != reqId) return           // the guest has asked something newer
        val query = op.optString("q").take(200)
        // Guests in one room search for the same things: a recent answer is reused.
        val cacheKey = query.trim().lowercase()
        val cached = searchCache[cacheKey]?.takeIf { SystemClock.elapsedRealtime() - it.at < SEARCH_CACHE_MS }
        val tracks = cached?.tracks ?: runCatching { EclipseAddon(link).search(query).tracks }
            .getOrDefault(emptyList())
            .also {
                if (it.isNotEmpty()) {
                    if (searchCache.size > 40) searchCache.clear()
                    searchCache[cacheKey] = Searched(SystemClock.elapsedRealtime(), it)
                }
            }
        if (latestSearch[guest] != reqId) return           // asked something newer meanwhile
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
                // No stream URL: the relay is world-readable, a ticket URL may carry
                // credentials, and the host only honours ids anyway (see trackFrom).
            )
        }
        // The session may have ended during the search: don't recreate what stop() wiped.
        if (sessionId != sid) return
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
            // "waiting": the source is still caching it, so it was moved down; the page says why.
            queue.put(trackTo(entry.track).put("key", entry.key).put("waiting", entry.waiting))
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
        title = o.optString("title").take(MAX_FIELD),
        artist = o.optString("artist").take(MAX_FIELD).takeIf { it.isNotBlank() },
        album = o.optString("album").take(MAX_FIELD).takeIf { it.isNotBlank() },
        artworkUrl = o.optString("art").takeIf { it.startsWith("https://") && it.length <= 1000 },
        durationMs = o.optLong("dur"),
        source = if (o.optString("source") == "TORBOX") Track.Source.TORBOX else Track.Source.ADDON,
        sourceId = o.optString("id").take(MAX_FIELD).takeIf { it.isNotBlank() },
        // Deliberately ignore a guest-supplied "url": only ids are honoured, so the phone never
        // downloads something an arbitrary guest chose.
        isrc = o.optString("isrc").take(32).takeIf { it.isNotBlank() },
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
            // Read to the end and close, but do not disconnect: a finished response hands its
            // connection back to the pool, and the next request skips the DNS, TCP and TLS set-up
            // (several round trips) that disconnecting here made every single request pay.
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else {
                Log.w(TAG, "$method $path -> HTTP $code")
                runCatching { conn.errorStream?.close() }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.javaClass.simpleName}: ${e.message}")
            conn?.disconnect()      // a broken connection must not go back to the pool
            null
        }
    }
}
