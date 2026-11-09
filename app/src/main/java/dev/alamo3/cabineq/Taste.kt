package dev.alamo3.cabineq

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.exp

/**
 * The deterministic taste engine.
 *
 * Nothing here is learned from a corpus: it is *your* listening, recorded as events and decayed
 * so recent behaviour dominates. Signals, strongest first:
 *
 *   dislike (−8)  ·  like (+6)  ·  finished a track (+1)  ·  started a track (+0.25)
 *   ·  skipped early (−1.2)  ·  skipped late (small negative)
 *
 * Events aggregate into three affinities — track (ISRC), artist and album — with a 14-day decay
 * time constant, so a binge fades instead of owning the profile forever. Everything is plain JSON
 * in `filesDir/taste.json`: inspectable, deletable, and the only reason a pick can be explained.
 */
object Taste {
    private const val TAG = "CabinEQ/Taste"
    private const val FILE_NAME = "taste.json"
    /** 14 days: a play from two weeks ago counts ~37 % of one from today. */
    private const val TAU_MS = 14.0 * 24 * 3600 * 1000
    private const val MAX_EVENTS = 4000

    enum class Kind { PLAY, COMPLETE, SKIP, LIKE, DISLIKE }

    class Event(
        val at: Long,
        val key: String,
        val artist: String?,
        val album: String?,
        val kind: Kind,
        /** Completion ratio for COMPLETE/SKIP; unused otherwise. */
        val value: Float,
    )

    private class Store(val file: File) {
        val events = ArrayList<Event>()
        val liked = HashSet<String>()
        val disliked = HashSet<String>()
    }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Taste").apply { isDaemon = true }
    }
    private val ui = Handler(Looper.getMainLooper())
    private val saveTask = Runnable { io.execute { persist() } }

    @Volatile private var store: Store? = null
    @Volatile private var version = 0
    @Volatile private var cached: Profile? = null
    @Volatile private var cachedVersion = -1

    /** Same identity the queue uses: a recording, not a release. */
    fun key(t: Track): String = t.isrc?.lowercase()
        ?: "${t.title.trim().lowercase()}|${t.artist?.trim()?.lowercase().orEmpty()}"

    // ---------------------------------------------------------------- recording

    fun onPlay(context: Context, track: Track) = record(context, track, Kind.PLAY, 1f)

    fun onComplete(context: Context, track: Track, atMs: Long, durationMs: Long) {
        val ratio = if (durationMs > 0) (atMs.toFloat() / durationMs).coerceIn(0f, 1f) else 1f
        if (ratio < 0.85f) return   // "finished" only when it really got to the end
        // Repeat-one can finish the same track a dozen times an hour; one completion per 20
        // minutes is what the listener actually told us.
        val k = key(track)
        val now = System.currentTimeMillis()
        val duplicate = synchronized(store(context)) {
            store(context).events.lastOrNull { it.key == k && it.kind == Kind.COMPLETE }
                ?.let { now - it.at < 20 * 60_000L } == true
        }
        if (duplicate) return
        record(context, track, Kind.COMPLETE, ratio)
    }

    fun onSkip(context: Context, track: Track, atMs: Long, durationMs: Long) {
        val ratio = if (durationMs > 0) (atMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        record(context, track, Kind.SKIP, ratio)
    }

    fun like(context: Context, track: Track) {
        val s = store(context)
        val k = key(track)
        synchronized(s) {
            s.liked.add(k)
            s.disliked.remove(k)
            // Drop a standing dislike for the same artist if this is a change of heart.
            val artist = track.artist?.trim()?.lowercase()
            if (artist != null) s.disliked.removeAll { it.startsWith("artist:$artist") }
        }
        record(context, track, Kind.LIKE, 1f)
    }

    fun dislike(context: Context, track: Track) {
        val s = store(context)
        val k = key(track)
        synchronized(s) {
            s.disliked.add(k)
            s.liked.remove(k)
        }
        record(context, track, Kind.DISLIKE, 1f)
    }

    fun isLiked(context: Context, track: Track): Boolean =
        key(track) in synchronized(store(context)) { HashSet(store(context).liked) }

    fun reset(context: Context) {
        val s = store(context)
        synchronized(s) {
            s.events.clear()
            s.liked.clear()
            s.disliked.clear()
        }
        version++
        cached = null
        persist()
    }

    private fun record(context: Context, track: Track, kind: Kind, value: Float) {
        if (track.title.isBlank()) return
        val s = store(context)
        synchronized(s) {
            s.events.add(
                Event(
                    at = System.currentTimeMillis(),
                    key = key(track),
                    artist = track.artist?.trim()?.takeIf { it.isNotBlank() },
                    album = track.album?.trim()?.takeIf { it.isNotBlank() },
                    kind = kind,
                    value = value,
                )
            )
            if (s.events.size > MAX_EVENTS) {
                s.events.subList(0, s.events.size - MAX_EVENTS).clear()
            }
        }
        version++
        ui.removeCallbacks(saveTask)
        ui.postDelayed(saveTask, 1500)    // coalesce bursts into one write
    }

    // ---------------------------------------------------------------- profile

    fun profile(context: Context): Profile {
        val v = version
        cached?.let { if (cachedVersion == v) return it }
        val s = store(context)
        val now = System.currentTimeMillis()
        val tracks = HashMap<String, Float>()
        val artists = HashMap<String, Float>()
        val albums = HashMap<String, Float>()
        val liked: Set<String>
        val disliked: Set<String>
        val skippedRecently = HashSet<String>()
        synchronized(s) {
            liked = HashSet(s.liked)
            disliked = HashSet(s.disliked)
            for (e in s.events) {
                // What you just skipped is the loudest "not this" there is, for this session.
                if (e.kind == Kind.SKIP && e.value < 0.5f && now - e.at < 30 * 60_000L) {
                    skippedRecently.add(e.key)
                }
            }
            for (e in s.events) {
                val w = weight(e, now)
                if (w == 0f) continue
                tracks[e.key] = (tracks[e.key] ?: 0f) + w
                e.artist?.lowercase()?.let { a -> artists[a] = (artists[a] ?: 0f) + w * 0.45f }
                e.album?.lowercase()?.let { a -> albums[a] = (albums[a] ?: 0f) + w * 0.15f }
            }
        }
        // Artists with two or more disliked tracks are out of the pool entirely.
        val bannedArtists = HashSet<String>()
        synchronized(s) {
            for (e in s.events) {
                if (e.kind == Kind.DISLIKE) e.artist?.lowercase()?.let { bannedArtists.add(it) }
            }
        }
        bannedArtists.removeAll { a -> (artists[a] ?: 0f) > 4f }
        skippedRecently.removeAll(liked)
        val p = Profile(tracks, artists, albums, liked, disliked, bannedArtists, skippedRecently)
        cached = p
        cachedVersion = v
        return p
    }

    private fun weight(e: Event, now: Long): Float {
        val age = (now - e.at).coerceAtLeast(0)
        val decay = exp(-age / TAU_MS).toFloat()
        val base = when (e.kind) {
            Kind.PLAY -> 0.25f
            Kind.COMPLETE -> 1.0f * (0.5f + 0.5f * e.value)
            Kind.SKIP -> if (e.value < 0.25f) -1.2f else -0.4f * (1f - e.value)
            Kind.LIKE -> 6f
            Kind.DISLIKE -> -8f
        }
        return base * decay
    }

    // ---------------------------------------------------------------- persistence

    private fun store(context: Context): Store {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val s = Store(file)
            if (file.exists()) {
                runCatching {
                    val o = JSONObject(file.readText())
                    o.optJSONArray("events")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            val kind = runCatching { Kind.valueOf(e.optString("k")) }.getOrNull() ?: continue
                            s.events.add(
                                Event(
                                    at = e.optLong("t"),
                                    key = e.optString("id"),
                                    artist = e.optString("a").takeIf { it.isNotBlank() },
                                    album = e.optString("al").takeIf { it.isNotBlank() },
                                    kind = kind,
                                    value = e.optDouble("v", 0.0).toFloat(),
                                )
                            )
                        }
                    }
                    o.optJSONArray("liked")?.let { for (i in 0 until it.length()) s.liked.add(it.optString(i)) }
                    o.optJSONArray("disliked")?.let { for (i in 0 until it.length()) s.disliked.add(it.optString(i)) }
                }.onFailure { Log.w(TAG, "Couldn't read the taste file: ${it.message}") }
            }
            store = s
            return s
        }
    }

    private fun persist() {
        store?.let { s ->
            val text = synchronized(s) {
                JSONObject()
                    .put("version", 1)
                    .put("events", JSONArray().apply {
                        s.events.forEach { e ->
                            put(
                                JSONObject()
                                    .put("t", e.at)
                                    .put("id", e.key)
                                    .putOpt("a", e.artist)
                                    .putOpt("al", e.album)
                                    .put("k", e.kind.name)
                                    .put("v", e.value.toDouble())
                            )
                        }
                    })
                    .put("liked", JSONArray(s.liked.toList()))
                    .put("disliked", JSONArray(s.disliked.toList()))
                    .toString()
            }
            runCatching { s.file.writeText(text) }
                .onFailure { Log.w(TAG, "Couldn't save taste: ${it.message}") }
        }
    }

    /** Signal count, for the settings summary. */
    fun eventCount(context: Context): Int = synchronized(store(context)) { store(context).events.size }
}

/** Immutable view of the profile at one point in time. */
class Profile(
    private val tracks: Map<String, Float>,
    private val artists: Map<String, Float>,
    private val albums: Map<String, Float>,
    private val liked: Set<String>,
    private val disliked: Set<String>,
    private val bannedArtists: Set<String>,
    private val skippedRecently: Set<String>,
) {
    fun isDisliked(t: Track): Boolean = Taste.key(t) in disliked || t.artist?.trim()?.lowercase() in bannedArtists

    fun skippedRecently(t: Track): Boolean = Taste.key(t) in skippedRecently && Taste.key(t) !in liked

    /**
     * How much this track matches the profile. Roughly: "how much do I want this right now",
     * independent of what is playing (the caller adds the session term).
     */
    fun score(t: Track): Float {
        val k = Taste.key(t)
        if (k in disliked) return -10f
        if (t.artist?.trim()?.lowercase() in bannedArtists) return -8f
        // Skipped in the last half hour: don't offer it again in this session.
        if (k in skippedRecently && k !in liked) return -6f
        var s = tracks[k] ?: 0f
        t.artist?.trim()?.lowercase()?.let { a -> s += (artists[a] ?: 0f) * 0.8f }
        t.album?.trim()?.lowercase()?.let { a -> s += (albums[a] ?: 0f) * 0.2f }
        if (k in liked) s += 6f
        return s
    }

    fun artistScore(name: String?): Float =
        name?.trim()?.lowercase()?.let { artists[it] } ?: 0f

    /** Short, human explanation of why this track was picked. */
    fun why(t: Track, sessionArtists: Set<String>): String {
        val k = Taste.key(t)
        val artist = t.artist?.trim()
        if (k in liked) return "you liked this track"
        if (artist != null && artist.lowercase() in sessionArtists) {
            return if ((artists[artist.lowercase()] ?: 0f) > 1f) {
                "same artist as what you're playing, and you finish it"
            } else {
                "same artist as what you're playing"
            }
        }
        val a = artistScore(artist)
        return when {
            a >= 4f -> "one of your favourites right now ($artist)"
            (tracks[k] ?: 0f) >= 1.2f -> "you keep finishing this one"
            a >= 1f -> "you've been playing $artist lately"
            else -> "new to you"
        }
    }

    /** Artists ranked by decayed affinity, for the transparency panel. */
    fun topArtists(n: Int = 5): List<Pair<String, Float>> =
        artists.entries.filter { it.value > 0f }
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }
}
