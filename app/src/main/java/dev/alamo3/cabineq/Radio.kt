package dev.alamo3.cabineq

import android.content.Context
import android.os.SystemClock
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.music.AddonMatch
import dev.alamo3.cabineq.music.Discover
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Keep going from this song, one track at a time" — towards music you haven't heard.
 *
 * Candidates come from Deezer's public catalogue (fast, no key), fetched in parallel:
 *
 *  A. the seed artist's top tracks — continuity with what is playing,
 *  C. the seed artist's Deezer radio and the top songs of similar artists,
 *  D. artists similar to your own favourites, so discovery follows your taste and not only the
 *     current song.
 *
 * Nebula is used for one thing only: finding the playable copy of a chosen song. That lookup is
 * the slow part (Nebula asks Tidal, which can take seconds or time out), so it is done ahead of
 * time: while a radio is on, [prepare] keeps the best-ranked candidates matched in the
 * background, a few a minute, and a pick takes an already-matched song without any request.
 * Pools for several artists, and every match, are kept on disk, so a restart starts warm.
 *
 * Ranking is discovery-first: a song you have never heard beats one you have, an artist you
 * have no history with gets a lift, anything played in the last three days is out, and familiar
 * songs sink the more you've heard them. Your taste still decides which new things come first:
 * artist affinity, likes, skips and dislikes all count. Songs from Deezer are only an index; each
 * pick is matched to the same recording on Nebula before it is queued.
 *
 * Exactly one track is returned at a time; [FilePlayer] chains calls to keep a deep queue.
 */
object Radio {
    private const val TAG = "CabinEQ/Radio"
    /** How long an artist's candidate pool is reused before it is fetched again. */
    private const val CACHE_TTL_MS = 6 * 3600_000L
    /** Artists whose pools are kept (in memory and on disk). */
    private const val POOLS_KEPT = 8
    /** Matched songs the background matcher keeps ready among the best-ranked candidates. */
    private const val READY_TARGET = 30
    /** A pick prefers a ready song among this many of the best-ranked ones. */
    private const val READY_WINDOW = 15
    /** Pause between background matches, and after Nebula fails to answer. */
    private const val MATCH_GAP_MS = 2_500L
    private const val MATCH_BACKOFF_MS = 30_000L
    /** Nebula's answer time for radio matching; past it the next candidate is tried. */
    private const val MATCH_TIMEOUT_MS = 12_000
    /** A song Nebula did not have is not looked for again for a week. */
    private const val UNMATCHED_TTL_MS = 7L * 24 * 3600_000
    /** A song played this recently is not offered again, liked or not. */
    private const val RECENT_MS = 3L * 24 * 3600 * 1000
    /** Nebula lookups per pick before giving up on discovered songs for this round. */
    private const val MAX_MATCH_TRIES = 4

    /** Discovered songs Nebula answered without (key -> when): not offered again for a while. */
    private val unmatched = ConcurrentHashMap<String, Long>()
    /** Discovered songs matched to Nebula (key -> the playable copy): picked without a request. */
    private val matched: MutableMap<String, Track> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Track>(256, 0.75f, false) {
            // Oldest matches go first once there are 2000: the newest are the useful ones.
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?) = size > 2000
        })

    private class Candidate(val track: Track, val source: String)
    private class Pool(val artist: String, val at: Long, val candidates: List<Candidate>)

    /** Pools by artist (lowercase), least recently used first. Guarded by [buildLock]. */
    private val pools = object : LinkedHashMap<String, Pool>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pool>?) = size > POOLS_KEPT
    }

    /** Why each recent pick was made, by song: what the player shows under "Why this song". */
    private val reasons = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 60
    }

    /** Why the radio queued [t], or null when the listener chose it themselves. */
    fun reasonFor(t: Track): String? = synchronized(reasons) { Taste.keys(t).firstNotNullOfOrNull { reasons[it] } }

    /** Why the last pick was made, shown in settings so the engine is never a black box. */
    @Volatile var lastReason: String? = null
        private set

    /** One recording = one entry (same identity the taste engine uses). */
    fun key(t: Track): String = Taste.key(t)

    /**
     * Every identity this track can be known by. The addon does not attach an ISRC to artist
     * top-tracks but does to search results, and Deezer never does, so the same song can arrive
     * with only one of the two; exclusion has to match on either.
     */
    fun identity(t: Track): List<String> = Taste.keys(t)

    /**
     * The best next track for this session, or null when the pool is exhausted. Blocking (a
     * handful of addon calls, cached per seed artist for 10 minutes), so call it off the audio
     * thread.
     *
     * @param sessionArtists artists heard in the last few tracks ("what is playing now")
     * @param currentArtist  the artist of the track that just started, so we don't chain albums
     */
    fun next(
        context: Context,
        seed: Track,
        exclude: Set<String>,
        sessionArtists: Set<String> = emptySet(),
        currentArtist: String? = null,
    ): Track? {
        // A queue with no artist anywhere (untagged files) goes by what this phone plays most.
        val artist = primaryArtist(seed.artist)
            ?: Taste.profile(context).topArtists(1).firstOrNull()?.let { primaryArtist(it.first) }
            ?: run {
                DLog.i(TAG, "next: no artist to go on (seed \"${seed.title}\")")
                return null
            }
        val candidates = poolFor(context, artist) ?: run {
            DLog.i(TAG, "next: no related songs for $artist")
            return null
        }
        val ranked = rank(context, candidates, exclude, sessionArtists, currentArtist, artist)
        DLog.i(TAG, "next: $artist, ${candidates.size} related, ${ranked.size} left after excluding ${exclude.size} queued")
        // Keep the background matcher on this artist: it is what makes the next pick instant.
        prepare(context, seed)
        // 1. A song already matched (in the background, or earlier) among the best few: no
        //    request at all. Slightly lower-ranked but ready beats best-ranked but unknown.
        var picked: Pair<Candidate, Float>? = null
        var track: Track? = null
        for (c in ranked.take(READY_WINDOW)) {
            val ready = readyCopy(c.first.track) ?: continue
            picked = c; track = ready; break
        }
        // 2. Nothing ready yet (a radio just started): match live, the best few, with a short
        //    timeout. Only a real miss writes a song off; a timeout just moves on.
        val link = Settings(context).addonLink
        var tries = 0
        if (picked == null) for (c in ranked) {
            readyCopy(c.first.track)?.let { picked = c; track = it }
            if (picked != null) break
            if (link.isBlank() || tries >= MAX_MATCH_TRIES) break
            val k = key(c.first.track)
            if (isUnmatched(k)) continue
            tries++
            when (val r = AddonMatch.match(EclipseAddon(link, MATCH_TIMEOUT_MS), c.first.track)) {
                is AddonMatch.Outcome.Found -> {
                    val copy = remember(c.first.track, r.track)
                    // Matched to a song that is already queued under another name: not again.
                    if (identity(copy).none { it in exclude }) { track = copy; picked = c }
                }
                AddonMatch.Outcome.NotFound -> {
                    unmatched[k] = System.currentTimeMillis()
                    DLog.i(TAG, "not on Nebula: ${c.first.track.title} (${c.first.track.artist})")
                }
                is AddonMatch.Outcome.Failed ->
                    DLog.i(TAG, "Nebula did not answer for ${c.first.track.title}: ${r.error.javaClass.simpleName}")
            }
            if (picked != null) break
        }
        val best = picked ?: run {
            DLog.i(TAG, "next: none of the top picks are on Nebula ($tries tried)")
            return null
        }
        val t = track ?: return null
        val profile = Taste.profile(context)
        val currentKey = primaryArtist(currentArtist)?.lowercase()
        val source = when (best.first.source) {
            "artist" -> if (primaryArtist(t.artist)?.lowercase() == currentKey) "same artist" else "more from ${t.artist}"
            "scene" -> "another artist from the same playlists"
            "similar" -> "similar to $artist"
            else -> "similar to artists you play"
        }
        val why = "$source · ${profile.why(best.first.track, sessionArtists)}"
        lastReason = "${t.title} — $why"
        synchronized(reasons) { reasons[key(t)] = why }
        DLog.i(TAG, "pick: ${t.title} (${t.artist}) score=${"%.2f".format(best.second)} [$source]")
        return t
    }

    /**
     * Song-level recommendations for a home shelf: the same ranking as [next], but spaced so one
     * artist cannot fill the row. Blocking like [next] — call it off the main thread.
     */
    fun recommend(context: Context, count: Int = 12): List<Track> {
        val seedArtist = Settings(context).radioSeed?.let { primaryArtist(it.artist) }
            ?: Taste.profile(context).topArtists(1).firstOrNull()?.let { primaryArtist(it.first) }
            ?: run {
                DLog.i(TAG, "recommend: no seed yet")
                return emptyList()
            }
        val candidates = poolFor(context, seedArtist) ?: run {
            DLog.i(TAG, "recommend: no pool for $seedArtist")
            return emptyList()
        }
        // The player may not exist yet when the home page renders; the snapshot is the same queue.
        val queue = FilePlayer.peek()?.queue?.takeIf { it.isNotEmpty() }
            ?: Settings(context).queueSnapshot?.tracks.orEmpty()
        val exclude = queue.flatMap { identity(it) }.toHashSet()
        val session = queue.takeLast(5).mapNotNull { it.artist }.toSet()
        val ranked = rank(context, candidates, exclude, session, queue.lastOrNull()?.artist, seedArtist)
        DLog.i(TAG, "recommend: ${queue.size} queued, ${ranked.size} ranked, first=${ranked.firstOrNull()?.first?.track?.title}")
        // One song per artist first (a discovery shelf should be a spread), then a second each
        // if the pool is thin. Discovered songs are matched to Nebula when tapped.
        val out = ArrayList<Track>(count)
        val perArtist = HashMap<String, Int>()
        for (cap in 1..2) {
            for ((candidate, _) in ranked) {
                if (out.size >= count) break
                if (candidate.track in out) continue
                if (isUnmatched(key(candidate.track))) continue
                val a = primaryArtist(candidate.track.artist)?.lowercase().orEmpty()
                if ((perArtist[a] ?: 0) >= cap) continue
                perArtist[a] = (perArtist[a] ?: 0) + 1
                out += candidate.track
            }
        }
        return out
    }

    /** The scoring rules, shared by the one-ahead pick and the shelf. */
    private fun rank(
        context: Context,
        candidates: List<Candidate>,
        exclude: Set<String>,
        sessionArtists: Set<String>,
        currentArtist: String?,
        seedArtist: String?,
    ): List<Pair<Candidate, Float>> {
        val profile = Taste.profile(context)
        val now = System.currentTimeMillis()
        val current = primaryArtist(currentArtist)?.lowercase()
        val sessionKeys = sessionArtists.mapNotNull { primaryArtist(it)?.lowercase() }.toSet()
        val seedKey = primaryArtist(seedArtist)?.lowercase()
        // How many of the last few picks were the seed artist? Used to break out of a streak.
        val seedRun = sessionKeys.count { it == seedKey }
        val jitter = java.util.Random()
        return candidates
            // Excluded by what will actually be queued too: a Deezer entry and its Nebula copy can
            // carry different keys (an ISRC, "feat." in the title or artist), and checking only the
            // Deezer one let the same matched song be queued again and again.
            .filter { c -> (identity(c.track) + readyIdentity(c.track)).none { it in exclude } }
            .filter { !profile.isDisliked(it.track) && !profile.skippedRecently(it.track) }
            // Played in the last three days: not again yet, however much you like it.
            .filter { now - profile.lastPlayedAt(it.track) > RECENT_MS }
            .map { candidate ->
                val t = candidate.track
                val a = primaryArtist(t.artist)?.lowercase()
                // Taste fit: how much you like this artist and what you've told us about the
                // song. Capped, so a favourite artist can't drown out everything new.
                var score = (profile.artistScore(t.artist) * 0.6f).coerceIn(-4f, 3f)
                val affinity = profile.trackAffinity(t)
                if (affinity < 0f) score += affinity            // skips count in full
                // Novelty: the point of the radio. Unheard beats heard; heard sinks with use.
                val heard = profile.heardCount(t)
                score += if (heard == 0) 3f else -1.5f - 0.3f * heard.coerceAtMost(5)
                if (profile.isLiked(t)) score += 1.5f              // a liked song may come back, rarely
                if (!profile.knowsArtist(t.artist)) score += 1.5f  // an artist you've never played
                score += when (candidate.source) {
                    "similar", "taste" -> 1.0f
                    "scene" -> 0.6f
                    else -> 0f
                }
                // Continuity: the same artist is a small plus, but never a lock-in.
                if (a != null && a in sessionKeys) score += 0.5f
                if (a != null && a == current) score -= 1.5f
                // Monotony: the more this artist already appears, the less we want another one.
                if (a != null) score -= 1.0f * sessionKeys.count { it == a }
                // Break out of an artist streak.
                if (a != null && a != seedKey && seedRun >= 2) score += 1.2f
                // A little shuffle so the same seed doesn't give the same order every time.
                score += jitter.nextFloat() * 0.6f
                candidate to score
            }
            .sortedByDescending { it.second }
    }

    /**
     * Builds the pool ahead of time (called when a car session starts), so the first "For you"
     * render does not spend a few seconds on a dozen addon calls.
     */
    fun warm(context: Context) {
        val seedArtist = Settings(context).radioSeed?.let { primaryArtist(it.artist) }
            ?: Taste.profile(context).topArtists(1).firstOrNull()?.let { primaryArtist(it.first) }
            ?: return
        runCatching { poolFor(context, seedArtist) }
    }

    // ---------------------------------------------------------------- ready songs

    private fun isUnmatched(k: String): Boolean {
        val at = unmatched[k] ?: return false
        if (System.currentTimeMillis() - at < UNMATCHED_TTL_MS) return true
        unmatched.remove(k)
        return false
    }

    private fun readyIdentity(t: Track): List<String> = matched[key(t)]?.let { identity(it) }.orEmpty()

    /** The playable copy of [t] if there is one without a request: its own, or a match. */
    private fun readyCopy(t: Track): Track? {
        if (t.sourceId != null || t.streamUrl != null) return t
        return matched[key(t)]
    }

    /** Stores a match; the addon's copy plays, the discovered cover is kept if it has none. */
    private fun remember(discovered: Track, found: EclipseAddon.Track): Track {
        val resolved = found.toTrack()
        val copy = resolved.copy(artworkUrl = resolved.artworkUrl ?: discovered.artworkUrl)
        matched[key(discovered)] = copy
        return copy
    }

    @Volatile private var preparing: String? = null
    @Volatile private var matcherRunning = false
    private val matcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-RadioPrep").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /**
     * Gets the radio for [seed] ready in the background: the pool built (in parallel, from
     * Deezer) and the best-ranked candidates matched on Nebula, a few a minute, until
     * [READY_TARGET] are ready. Returns at once; safe to call as often as wanted. Stops by itself
     * when the radio is off.
     */
    fun prepare(context: Context, seed: Track?) {
        val artist = primaryArtist(seed?.artist)
            ?: Taste.profile(context).topArtists(1).firstOrNull()?.let { primaryArtist(it.first) }
            ?: return
        preparing = artist
        if (matcherRunning) return
        matcherRunning = true
        val app = context.applicationContext
        matcher.execute {
            try { runMatcher(app) } catch (e: Exception) {
                DLog.w(TAG, "background matching stopped", e)
            } finally { matcherRunning = false }
        }
    }

    private fun runMatcher(context: Context) {
        loadCache(context)
        var sinceSave = 0
        while (true) {
            val artist = preparing ?: break
            // Only while a radio is on: nothing is fetched for a queue that will just end.
            if (FilePlayer.peek()?.radioActive != true) break
            val link = Settings(context).addonLink.takeIf { it.isNotBlank() } ?: break
            val pool = poolFor(context, artist) ?: break
            val queue = FilePlayer.peek()?.queue.orEmpty()
            val exclude = queue.flatMap { identity(it) }.toHashSet()
            val top = rank(context, pool, exclude, emptySet(), null, artist)
                .take(READY_TARGET + READY_WINDOW).map { it.first.track }
            val ready = top.count { readyCopy(it) != null }
            val next = top.firstOrNull { readyCopy(it) == null && !isUnmatched(key(it)) }
            if (ready >= READY_TARGET || next == null) {
                if (sinceSave > 0) { saveCache(context); sinceSave = 0 }
                Thread.sleep(MATCH_BACKOFF_MS)        // enough ready: look again as the queue moves
                continue
            }
            when (val r = AddonMatch.match(EclipseAddon(link, MATCH_TIMEOUT_MS), next)) {
                is AddonMatch.Outcome.Found -> { remember(next, r.track); sinceSave++ }
                AddonMatch.Outcome.NotFound -> { unmatched[key(next)] = System.currentTimeMillis(); sinceSave++ }
                is AddonMatch.Outcome.Failed -> {
                    // Nebula is struggling: back off rather than add to its load.
                    DLog.i(TAG, "background match: Nebula did not answer (${r.error.javaClass.simpleName}); pausing")
                    Thread.sleep(MATCH_BACKOFF_MS)
                    continue
                }
            }
            if (sinceSave >= 5) { saveCache(context); sinceSave = 0 }
            Thread.sleep(MATCH_GAP_MS)
        }
        saveCache(context)
    }

    // ---------------------------------------------------------------- disk cache

    @Volatile private var loaded = false

    private fun cacheFile(context: Context) = File(context.filesDir, "radio-cache.json")

    private fun loadCache(context: Context) {
        if (loaded) return
        synchronized(buildLock) {
            if (loaded) return
            loaded = true
            runCatching {
                val o = JSONObject(cacheFile(context).readText())
                val now = System.currentTimeMillis()
                o.optJSONArray("pools")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        // Wall-clock age on disk, converted to this boot's clock for the TTL check.
                        val age = now - p.optLong("savedAt")
                        if (age !in 0 until CACHE_TTL_MS) continue
                        val cands = p.optJSONArray("candidates") ?: continue
                        val list = (0 until cands.length()).mapNotNull { j ->
                            val c = cands.optJSONObject(j) ?: return@mapNotNull null
                            val t = c.optJSONObject("track")?.let(Track::fromJson) ?: return@mapNotNull null
                            Candidate(t, c.optString("source", "similar"))
                        }
                        val artist = p.optString("artist")
                        if (list.isNotEmpty() && artist.isNotBlank()) {
                            pools[artist.lowercase()] = Pool(artist, SystemClock.elapsedRealtime() - age, list)
                        }
                    }
                }
                o.optJSONObject("matched")?.let { m ->
                    m.keys().forEach { k -> m.optJSONObject(k)?.let(Track::fromJson)?.let { matched[k] = it } }
                }
                o.optJSONObject("unmatched")?.let { u ->
                    u.keys().forEach { k -> val at = u.optLong(k); if (now - at < UNMATCHED_TTL_MS) unmatched[k] = at }
                }
                DLog.i(TAG, "cache: ${pools.size} pools, ${matched.size} matched, ${unmatched.size} not on Nebula")
            }
        }
    }

    private fun saveCache(context: Context) {
        runCatching {
            val now = System.currentTimeMillis()
            val clock = SystemClock.elapsedRealtime()
            val o = JSONObject()
            val arr = JSONArray()
            synchronized(buildLock) {
                pools.values.forEach { p ->
                    arr.put(JSONObject()
                        .put("artist", p.artist)
                        .put("savedAt", now - (clock - p.at))
                        .put("candidates", JSONArray().apply {
                            p.candidates.forEach { c -> put(JSONObject().put("source", c.source).put("track", c.track.toJson())) }
                        }))
                }
            }
            o.put("pools", arr)
            // The newest matches only: a few thousand would be pointless weight.
            val matches = synchronized(matched) { matched.entries.map { it.key to it.value } }
            o.put("matched", JSONObject().apply { matches.forEach { (k, t) -> put(k, t.toJson()) } })
            o.put("unmatched", JSONObject().apply { unmatched.forEach { (k, at) -> put(k, at) } })
            val f = cacheFile(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }.onFailure { DLog.w(TAG, "could not save the radio cache", it) }
    }

    // ---------------------------------------------------------------- pool

    private val buildLock = Any()

    private val builders = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "CabinEQ-RadioPool").apply { isDaemon = true }
    }

    private fun poolFor(context: Context, artist: String): List<Candidate>? {
        loadCache(context)
        val k = artist.lowercase()
        synchronized(buildLock) {
            pools[k]?.let { if (SystemClock.elapsedRealtime() - it.at < CACHE_TTL_MS) return it.candidates }
        }
        // Built outside the lock: a build is a few seconds of network, and the lock also guards
        // reads and saves that must not wait behind it (a radio pick, the background matcher).
        val started = SystemClock.elapsedRealtime()
        val built = buildPool(context, artist) ?: return null
        synchronized(buildLock) { pools[k] = Pool(artist, started, built) }
        DLog.i(TAG, "pool for $artist built in ${SystemClock.elapsedRealtime() - started} ms")
        saveCache(context)
        return built
    }

    /**
     * Deezer around [artist], all requests at once: the whole build takes about as long as its
     * slowest request (a second or two), not the sum of two dozen. Nebula is only used when
     * Deezer does not know the artist at all.
     */
    private fun buildPool(context: Context, artist: String): List<Candidate>? {
        val seedId = runCatching { Discover.artistId(artist) }.getOrNull()
        if (seedId == null) return nebulaPool(context, artist)

        val profile = Taste.profile(context)
        val seedKey = primaryArtist(artist)?.lowercase()
        val tasteArtists = profile.topArtists(6)
            .mapNotNull { primaryArtist(it.first) }
            .filter { it.lowercase() != seedKey }
            .take(2)

        val parts = ArrayList<Callable<List<Candidate>>>()
        // A. the seed's own best-known songs.
        parts += Callable { Discover.top(seedId, 20).map { Candidate(it, "artist") } }
        // C. Deezer around the seed: its radio, and the top songs of similar artists.
        parts += Callable { Discover.radio(seedId).map { Candidate(it, "similar") } }
        parts += Callable {
            Discover.topOf(Discover.related(seedId, 10).take(8), 4).map { Candidate(it, "similar") }
        }
        // D. Deezer around your own favourites: discovery that follows your taste.
        tasteArtists.forEach { name ->
            parts += Callable {
                val id = Discover.artistId(name) ?: return@Callable emptyList<Candidate>()
                Discover.topOf(Discover.related(id, 6).take(4), 3).map { Candidate(it, "taste") }
            }
        }
        val futures = parts.map { builders.submit(it) }
        val out = futures.flatMap { f -> runCatching { f.get(20, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
        val distinct = out.distinctBy { key(it.track) }
        if (distinct.isEmpty()) return nebulaPool(context, artist)
        DLog.i(
            TAG,
            "pool for $artist: ${distinct.size} songs — same artist=${distinct.count { it.source == "artist" }}, " +
                "other artists=${distinct.count { it.source != "artist" }}, taste seeds=$tasteArtists"
        )
        return distinct
    }

    /** The old way, for an artist Deezer does not know: Nebula's search and artist page. */
    private fun nebulaPool(context: Context, artist: String): List<Candidate>? {
        val link = Settings(context).addonLink
        if (link.isBlank()) return null
        val addon = EclipseAddon(link, MATCH_TIMEOUT_MS)
        val results = runCatching { addon.search(artist, 10) }.getOrNull() ?: return null
        val detail = results.artists.firstOrNull { it.name.equals(artist, ignoreCase = true) }
            ?.let { runCatching { addon.artist(it.id) }.getOrNull() }
        val out = (detail?.topTracks.orEmpty() + results.tracks.filter { it.artist.contains(artist, ignoreCase = true) })
            .map { Candidate(it.toTrack(), "artist") }
            .distinctBy { key(it.track) }
        DLog.i(TAG, "pool for $artist (Nebula, not on Deezer): ${out.size} songs")
        return out.ifEmpty { null }
    }

    /** "Daft Punk, Julian Casablancas" / "X feat. Y" -> "Daft Punk" (search needs the primary). */
    private fun primaryArtist(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val head = raw
            .split(',').first()
            .split(Regex("(?i)\\s+feat\\.?\\s+")).first()
            .split('&').first()
            .trim()
        return head.ifBlank { raw.trim() }.ifBlank { null }
    }
}
