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

    enum class Kind {
        PLAY, COMPLETE, SKIP, LIKE, DISLIKE,
        /** A track found in an imported playlist: a hint, never as loud as an explicit like. */
        IMPORT,
    }

    class Event(
        val at: Long,
        val key: String,
        /**
         * The title|artist key when [key] is an ISRC. The addon only sends ISRCs with some
         * results, so the same song arrives keyed either way; events carry both.
         */
        val alt: String? = null,
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
    fun key(t: Track): String = t.isrc?.lowercase()?.takeIf { it.isNotBlank() } ?: nameKey(t)

    fun nameKey(t: Track): String = nameKey(t.title, t.artist)

    /** Title|primary-artist in the shared normal form ([SongKey]). */
    fun nameKey(title: String, artist: String?): String = dev.alamo3.cabineq.music.SongKey.of(title, artist)

    /** Primary artist, simplified: how artists are matched across catalogues. */
    fun artistKey(name: String?): String? = dev.alamo3.cabineq.music.SongKey.artist(name)

    /** Keys saved before [nameKey] was normalised: same form as new ones (ISRCs unchanged). */
    private fun normalizeStored(k: String): String {
        val bar = k.lastIndexOf('|')
        return if (bar < 0) k else nameKey(k.substring(0, bar), k.substring(bar + 1))
    }

    /** Every key this song can be recorded under: ISRC (when known) and title|artist. */
    fun keys(t: Track): List<String> {
        val isrc = t.isrc?.lowercase()?.takeIf { it.isNotBlank() }
        return if (isrc != null) listOf(isrc, nameKey(t)) else listOf(nameKey(t))
    }

    private fun altKey(t: Track): String? = if (t.isrc.isNullOrBlank()) null else nameKey(t)

    private fun Event.matches(ks: Collection<String>): Boolean = key in ks || (alt != null && alt in ks)

    // ---------------------------------------------------------------- recording

    fun onPlay(context: Context, track: Track) = record(context, track, Kind.PLAY, 1f)

    fun onComplete(context: Context, track: Track, atMs: Long, durationMs: Long) {
        val ratio = if (durationMs > 0) (atMs.toFloat() / durationMs).coerceIn(0f, 1f) else 1f
        if (ratio < 0.85f) return   // "finished" only when it really got to the end
        // Repeat-one can finish the same track a dozen times an hour; one completion per 20
        // minutes is what the listener actually told us.
        val ks = keys(track)
        val now = System.currentTimeMillis()
        val duplicate = synchronized(store(context)) {
            store(context).events.lastOrNull { it.kind == Kind.COMPLETE && it.matches(ks) }
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
        val ks = keys(track)
        synchronized(s) {
            s.liked.addAll(ks)
            s.disliked.removeAll(ks.toSet())
        }
        record(context, track, Kind.LIKE, 1f)
    }

    fun dislike(context: Context, track: Track) {
        val s = store(context)
        val ks = keys(track)
        synchronized(s) {
            s.disliked.addAll(ks)
            s.liked.removeAll(ks.toSet())
        }
        record(context, track, Kind.DISLIKE, 1f)
    }

    fun isLiked(context: Context, track: Track): Boolean {
        val ks = keys(track)
        return synchronized(store(context)) { ks.any { it in store(context).liked } }
    }

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

    /**
     * Seeds taste from an imported playlist. Tracks already imported are skipped, so importing
     * the same playlist twice does not double its weight.
     */
    fun importPlaylist(context: Context, tracks: List<Track>, playlistName: String): Int {
        if (tracks.isEmpty()) return 0
        val s = store(context)
        val now = System.currentTimeMillis()
        val already = HashSet<String>()
        synchronized(s) {
            for (e in s.events) if (e.kind == Kind.IMPORT) { already.add(e.key); e.alt?.let(already::add) }
        }
        var added = 0
        synchronized(s) {
            for (t in tracks) {
                if (t.title.isBlank()) continue
                val k = key(t)
                if (k in already || keys(t).any { it in already }) continue
                already.addAll(keys(t))
                s.events.add(
                    Event(
                        at = now,
                        key = k,
                        alt = altKey(t),
                        artist = t.artist?.trim()?.takeIf { it.isNotBlank() },
                        album = t.album?.trim()?.takeIf { it.isNotBlank() },
                        kind = Kind.IMPORT,
                        value = 1f,
                    )
                )
                added++
            }
            if (s.events.size > MAX_EVENTS) {
                s.events.subList(0, s.events.size - MAX_EVENTS).clear()
            }
        }
        if (added > 0) {
            Settings(context).addImportedPlaylist(playlistName)
            version++
            ui.removeCallbacks(saveTask)
            ui.postDelayed(saveTask, 1500)
        }
        return added
    }

    /** Undoes every import in one go, keeping what was actually listened to. */
    fun forgetImports(context: Context): Int {
        val s = store(context)
        var removed = 0
        synchronized(s) {
            val it = s.events.iterator()
            while (it.hasNext()) {
                if (it.next().kind == Kind.IMPORT) { it.remove(); removed++ }
            }
        }
        Settings(context).clearImportedPlaylists()
        version++
        ui.removeCallbacks(saveTask)
        ui.postDelayed(saveTask, 1500)
        return removed
    }

    fun importedPlaylists(context: Context): List<String> = Settings(context).importedPlaylists()

    private fun record(context: Context, track: Track, kind: Kind, value: Float) {
        if (track.title.isBlank()) return
        val s = store(context)
        synchronized(s) {
            s.events.add(
                Event(
                    at = System.currentTimeMillis(),
                    key = key(track),
                    alt = altKey(track),
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
        /** Last time each song was played (any key), for the "not again so soon" rule. */
        val lastPlayed = HashMap<String, Long>()
        /** Songs you already know: played, liked or in an imported playlist. */
        val heard = HashMap<String, Int>()
        val knownArtists = HashSet<String>()
        /** Key -> the name as last seen, for display and for searching. */
        val artistNames = HashMap<String, String>()
        synchronized(s) {
            liked = HashSet(s.liked)
            disliked = HashSet(s.disliked)
            for (e in s.events) {
                val ks = listOfNotNull(e.key, e.alt)
                // What you just skipped is the loudest "not this" there is, for this session.
                if (e.kind == Kind.SKIP && e.value < 0.5f && now - e.at < 30 * 60_000L) {
                    skippedRecently.addAll(ks)
                }
                if (e.kind == Kind.PLAY || e.kind == Kind.COMPLETE) {
                    ks.forEach { k -> if (e.at > (lastPlayed[k] ?: 0L)) lastPlayed[k] = e.at }
                }
                if (e.kind == Kind.PLAY || e.kind == Kind.LIKE || e.kind == Kind.IMPORT) {
                    ks.forEach { k -> heard[k] = (heard[k] ?: 0) + 1 }
                }
                val artistK = artistKey(e.artist)
                artistK?.let { knownArtists.add(it); artistNames[it] = e.artist!!.split(',').first().trim() }
                val w = weight(e, now)
                if (w == 0f) continue
                ks.forEach { k -> tracks[k] = (tracks[k] ?: 0f) + w }
                artistK?.let { a -> artists[a] = (artists[a] ?: 0f) + w * 0.45f }
                e.album?.lowercase()?.let { a -> albums[a] = (albums[a] ?: 0f) + w * 0.15f }
            }
        }
        // No artist is ever banned outright. A disliked song is out for good; its artist only
        // takes a decaying penalty through the affinity above (-3.6 per dislike, fading over
        // weeks), which the radio caps, so their other songs can still come up.
        skippedRecently.removeAll(liked)
        val p = Profile(tracks, artists, albums, liked, disliked, skippedRecently, lastPlayed, heard, knownArtists, artistNames)
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
            // A playlist placement says "this belongs somewhere I listen", not "play this a lot".
            Kind.IMPORT -> 0.35f * e.value.coerceIn(0f, 1f)
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
                                    key = normalizeStored(e.optString("id")),
                                    alt = e.optString("n").takeIf { it.isNotBlank() }?.let(::normalizeStored),
                                    artist = e.optString("a").takeIf { it.isNotBlank() },
                                    album = e.optString("al").takeIf { it.isNotBlank() },
                                    kind = kind,
                                    value = e.optDouble("v", 0.0).toFloat(),
                                )
                            )
                        }
                    }
                    o.optJSONArray("liked")?.let { for (i in 0 until it.length()) s.liked.add(normalizeStored(it.optString(i))) }
                    o.optJSONArray("disliked")?.let { for (i in 0 until it.length()) s.disliked.add(normalizeStored(it.optString(i))) }
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
                                    .putOpt("n", e.alt)
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
    private val skippedRecently: Set<String>,
    private val lastPlayed: Map<String, Long> = emptyMap(),
    private val heard: Map<String, Int> = emptyMap(),
    private val knownArtists: Set<String> = emptySet(),
    private val artistNames: Map<String, String> = emptyMap(),
) {
    private fun artistKey(t: Track) = Taste.artistKey(t.artist)

    fun isLiked(t: Track): Boolean = Taste.keys(t).any { it in liked }

    /** This song was disliked. Its artist is not: see [Taste.profile]. */
    fun isDisliked(t: Track): Boolean = Taste.keys(t).any { it in disliked }

    fun skippedRecently(t: Track): Boolean = !isLiked(t) && Taste.keys(t).any { it in skippedRecently }

    /** When this song last played, or 0. */
    fun lastPlayedAt(t: Track): Long = Taste.keys(t).maxOf { lastPlayed[it] ?: 0L }

    /** How many times you've met this song (plays, likes, imports); 0 = new to you. */
    fun heardCount(t: Track): Int = Taste.keys(t).maxOf { heard[it] ?: 0 }

    /** Any history at all with this artist. */
    fun knowsArtist(name: String?): Boolean = Taste.artistKey(name)?.let { it in knownArtists } == true

    /** Decayed song-level evidence; the strongest of the song's keys. */
    fun trackAffinity(t: Track): Float =
        Taste.keys(t).mapNotNull { tracks[it] }.maxByOrNull { kotlin.math.abs(it) } ?: 0f

    /**
     * How much this track matches the profile. Roughly: "how much do I want this right now",
     * independent of what is playing (the caller adds the session term).
     */
    fun score(t: Track): Float {
        if (Taste.keys(t).any { it in disliked }) return -10f
        // Skipped in the last half hour: don't offer it again in this session.
        if (skippedRecently(t)) return -6f
        var s = trackAffinity(t)
        artistKey(t)?.let { a -> s += (artists[a] ?: 0f) * 0.8f }
        t.album?.trim()?.lowercase()?.let { a -> s += (albums[a] ?: 0f) * 0.2f }
        if (isLiked(t)) s += 6f
        return s
    }

    fun artistScore(name: String?): Float =
        Taste.artistKey(name)?.let { artists[it] } ?: 0f

    /** Short, human explanation of why this track was picked. */
    fun why(t: Track, sessionArtists: Set<String>): String {
        val artist = t.artist?.trim()
        if (heardCount(t) == 0) {
            return if (!knowsArtist(artist)) "new song, new artist" else "a song of theirs you haven't heard"
        }
        if (isLiked(t)) return "you liked this track"
        if (artist != null && sessionArtists.any { Taste.artistKey(it) == Taste.artistKey(artist) }) {
            return if (artistScore(artist) > 1f) {
                "same artist as what you're playing, and you finish it"
            } else {
                "same artist as what you're playing"
            }
        }
        val a = artistScore(artist)
        return when {
            a >= 4f -> "one of your favourites right now ($artist)"
            trackAffinity(t) >= 1.2f -> "you keep finishing this one"
            a >= 1f -> "you've been playing $artist lately"
            else -> "back after a while"
        }
    }

    /** Artists ranked by decayed affinity, for the transparency panel. */
    fun topArtists(n: Int = 5): List<Pair<String, Float>> =
        artists.entries.filter { it.value > 0f }
            .sortedByDescending { it.value }
            .take(n)
            .map { (artistNames[it.key] ?: it.key) to it.value }
}
