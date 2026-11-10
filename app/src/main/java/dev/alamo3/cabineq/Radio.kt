package dev.alamo3.cabineq

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.alamo3.cabineq.addon.EclipseAddon
import dev.alamo3.cabineq.music.AddonMatch
import dev.alamo3.cabineq.music.Discover

/**
 * "Keep going from this song, one track at a time" — towards music you haven't heard.
 *
 * Candidates come from four places:
 *
 *  A. the seed artist's top tracks — continuity with what is playing,
 *  B. playlists that match the seed artist — the other artists in them (same scene),
 *  C. Deezer: the seed artist's radio and the top songs of similar artists,
 *  D. Deezer: artists similar to your own favourites, so discovery follows your taste and not
 *     only the current song.
 *
 * Ranking is discovery-first: a song you have never heard beats one you have, an artist you
 * have no history with gets a lift, anything played in the last three days is out, and familiar
 * songs sink the more you've heard them. Your taste still decides which new things come first:
 * artist affinity, likes, skips and dislikes all count. Songs from Deezer are only an index; each
 * pick is matched to the same recording on Nebula before it is queued.
 *
 * Exactly one track is returned at a time; [FilePlayer] asks again when the queue drains.
 */
object Radio {
    private const val TAG = "CabinEQ/Radio"
    private const val CACHE_TTL_MS = 10 * 60_000L
    /** A song played this recently is not offered again, liked or not. */
    private const val RECENT_MS = 3L * 24 * 3600 * 1000
    /** Nebula lookups per pick before giving up on discovered songs for this round. */
    private const val MAX_MATCH_TRIES = 4

    /** Discovered songs the addon doesn't have (per process): not offered again. */
    private val unmatched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private class Candidate(val track: Track, val source: String)
    private class Pool(val artist: String, val at: Long, val candidates: List<Candidate>)

    @Volatile private var pool: Pool? = null

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
        val artist = primaryArtist(seed.artist) ?: return null
        val candidates = poolFor(context, artist) ?: return null
        val ranked = rank(context, candidates, exclude, sessionArtists, currentArtist, artist)
        // Discovered songs need a Nebula match before they can play. Try the best few; a song
        // the addon doesn't have is remembered and skipped from then on.
        val link = Settings(context).addonLink
        var picked: Pair<Candidate, Float>? = null
        var track: Track? = null
        var tries = 0
        for (c in ranked) {
            if (c.first.track.sourceId != null || c.first.track.streamUrl != null) {
                picked = c; track = c.first.track; break
            }
            if (link.isBlank() || tries >= MAX_MATCH_TRIES) continue
            val k = key(c.first.track)
            if (k in unmatched) continue
            tries++
            val found = runCatching { AddonMatch.find(EclipseAddon(link), c.first.track) }.getOrNull()
            if (found == null) {
                unmatched.add(k)
                Log.i(TAG, "not on Nebula: ${c.first.track.title} (${c.first.track.artist})")
                continue
            }
            // The addon's copy plays; keep the discovered cover if the addon has none.
            val resolved = found.toTrack()
            track = resolved.copy(artworkUrl = resolved.artworkUrl ?: c.first.track.artworkUrl)
            picked = c
            break
        }
        val best = picked ?: return null
        val t = track ?: return null
        val profile = Taste.profile(context)
        val currentKey = primaryArtist(currentArtist)?.lowercase()
        val source = when (best.first.source) {
            "artist" -> if (primaryArtist(t.artist)?.lowercase() == currentKey) "same artist" else "more from ${t.artist}"
            "scene" -> "another artist from the same playlists"
            "similar" -> "similar to $artist"
            else -> "similar to artists you play"
        }
        lastReason = "${t.title} — $source · ${profile.why(best.first.track, sessionArtists)}"
        Log.i(TAG, "pick: ${t.title} (${t.artist}) score=${"%.2f".format(best.second)} [$source]")
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
                Log.i(TAG, "recommend: no seed yet")
                return emptyList()
            }
        val candidates = poolFor(context, seedArtist) ?: run {
            Log.i(TAG, "recommend: no pool for $seedArtist")
            return emptyList()
        }
        // The player may not exist yet when the home page renders; the snapshot is the same queue.
        val queue = FilePlayer.peek()?.queue?.takeIf { it.isNotEmpty() }
            ?: Settings(context).queueSnapshot?.tracks.orEmpty()
        val exclude = queue.flatMap { identity(it) }.toHashSet()
        val session = queue.takeLast(5).mapNotNull { it.artist }.toSet()
        val ranked = rank(context, candidates, exclude, session, queue.lastOrNull()?.artist, seedArtist)
        Log.i(TAG, "recommend: ${queue.size} queued, ${ranked.size} ranked, first=${ranked.firstOrNull()?.first?.track?.title}")
        // One song per artist first (a discovery shelf should be a spread), then a second each
        // if the pool is thin. Discovered songs are matched to Nebula when tapped.
        val out = ArrayList<Track>(count)
        val perArtist = HashMap<String, Int>()
        for (cap in 1..2) {
            for ((candidate, _) in ranked) {
                if (out.size >= count) break
                if (candidate.track in out) continue
                if (key(candidate.track) in unmatched) continue
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
            .filter { c -> identity(c.track).none { it in exclude } }
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

    // ---------------------------------------------------------------- pool

    private val buildLock = Any()

    private fun poolFor(context: Context, artist: String): List<Candidate>? = synchronized(buildLock) {
        val now = SystemClock.elapsedRealtime()
        pool?.let {
            if (it.artist.equals(artist, true) && now - it.at < CACHE_TTL_MS) return it.candidates
        }
        val link = Settings(context).addonLink
        if (link.isBlank()) return null
        val addon = EclipseAddon(link)
        val results = runCatching { addon.search(artist, 10) }.getOrNull() ?: return null
        val detail = results.artists.firstOrNull { it.name.equals(artist, ignoreCase = true) }
            ?.let { runCatching { addon.artist(it.id) }.getOrNull() }

        val out = ArrayList<Candidate>(160)
        fun add(from: List<EclipseAddon.Track>, source: String) {
            from.forEach { out += Candidate(it.toTrack(), source) }
        }
        fun addFound(from: List<Track>, source: String) {
            from.forEach { out += Candidate(it, source) }
        }

        // A. the seed's own best-known songs (no album deep-dives: that's recycling).
        add(detail?.topTracks.orEmpty(), "artist")
        add(results.tracks.filter { it.artist.contains(artist, ignoreCase = true) }, "artist")
        val sameArtistCount = out.size

        // B. playlists that match the seed: keep the *other* artists in them (a playlist that is
        // entirely the seed artist contributes nothing here, which is the point).
        val scene = results.playlists.take(3).flatMap { pl ->
            runCatching { addon.playlist(pl.id).tracks }.getOrDefault(emptyList())
        }.filterNot { it.artist.contains(artist, ignoreCase = true) }
        add(scene, "scene")

        // C. Deezer around the seed: its radio, and the top songs of similar artists.
        val seedId = runCatching { Discover.artistId(artist) }.getOrNull()
        if (seedId != null) {
            addFound(runCatching { Discover.radio(seedId) }.getOrDefault(emptyList()), "similar")
            val similar = runCatching { Discover.related(seedId, 10) }.getOrDefault(emptyList())
            addFound(Discover.topOf(similar.take(8), 4), "similar")
        }

        // D. Deezer around your own favourites: discovery that follows your taste.
        val profile = Taste.profile(context)
        val seedKey = primaryArtist(artist)?.lowercase()
        val tasteArtists = profile.topArtists(6)
            .mapNotNull { primaryArtist(it.first) }
            .filter { it.lowercase() != seedKey }
            .take(2)
        tasteArtists.forEach { name ->
            val id = runCatching { Discover.artistId(name) }.getOrNull() ?: return@forEach
            val similar = runCatching { Discover.related(id, 6) }.getOrDefault(emptyList())
                .filter { s -> out.none { primaryArtist(it.track.artist)?.equals(s.name, true) == true } }
            addFound(Discover.topOf(similar.take(4), 3), "taste")
        }

        val distinct = out.distinctBy { key(it.track) }
        if (distinct.isEmpty()) return null
        Log.i(
            TAG,
            "pool for $artist: ${distinct.size} songs — same artist=$sameArtistCount, " +
                "other artists=${distinct.size - sameArtistCount}, taste seeds=$tasteArtists"
        )
        pool = Pool(artist, now, distinct)
        return distinct
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
