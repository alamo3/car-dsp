package dev.alamo3.cabineq

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.alamo3.cabineq.addon.EclipseAddon

/**
 * "Keep going from this song, one track at a time."
 *
 * The engine is **song-first**: every candidate is scored as a track (ISRC affinity from what you
 * finish/skip/like), and artists only modulate that score. Candidates come from three places, so
 * a pick is not limited to the seed's artist:
 *
 *  A. the seed's catalogue (top tracks + two albums) — continuity with what is playing,
 *  B. playlists that match the seed artist — the other artists in those playlists (same scene),
 *  C. your own top artists by decayed affinity — cross-artist, driven by song-level evidence.
 *
 * Exactly one track is returned at a time; [FilePlayer] asks again when the queue drains, so the
 * lookahead stays at one and nothing is prefetched in bulk.
 */
object Radio {
    private const val TAG = "CabinEQ/Radio"
    private const val CACHE_TTL_MS = 10 * 60_000L

    private class Candidate(val track: Track, val source: String)
    private class Pool(val artist: String, val at: Long, val candidates: List<Candidate>)

    @Volatile private var pool: Pool? = null

    /** Why the last pick was made, shown in settings so the engine is never a black box. */
    @Volatile var lastReason: String? = null
        private set

    /** One recording = one entry (same identity [Settings.recordPlayed] uses). */
    fun key(t: Track): String = t.isrc?.lowercase()
        ?: "${t.title.trim().lowercase()}|${t.artist?.trim()?.lowercase().orEmpty()}"

    /**
     * Every identity this track can be known by. The addon does not attach an ISRC to artist
     * top-tracks but does to search results, so the same song can arrive with only one of the
     * two; exclusion has to match on either.
     */
    fun identity(t: Track): List<String> {
        val isrc = t.isrc?.lowercase()?.takeIf { it.isNotBlank() }
        val name = "${t.title.trim().lowercase()}|${t.artist?.trim()?.lowercase().orEmpty()}"
        return if (isrc != null) listOf(isrc, name) else listOf(name)
    }

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
        val best = ranked.firstOrNull() ?: return null
        val t = best.first.track
        val profile = Taste.profile(context)
        val currentKey = primaryArtist(currentArtist)?.lowercase()
        val source = when (best.first.source) {
            "artist" -> if (primaryArtist(t.artist)?.lowercase() == currentKey) "same artist" else "more from ${t.artist}"
            "scene" -> "another artist from the same playlists"
            else -> "new artist you might like"
        }
        lastReason = "${t.title} — $source · ${profile.why(t, sessionArtists)}"
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
        val out = ArrayList<Track>(count)
        val perArtist = HashMap<String, Int>()
        for ((candidate, _) in ranked) {
            val a = primaryArtist(candidate.track.artist)?.lowercase().orEmpty()
            if ((perArtist[a] ?: 0) >= 2) continue      // at most two per artist in the row
            perArtist[a] = (perArtist[a] ?: 0) + 1
            out += candidate.track
            if (out.size >= count) break
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
        val current = primaryArtist(currentArtist)?.lowercase()
        val sessionKeys = sessionArtists.mapNotNull { primaryArtist(it)?.lowercase() }.toSet()
        val seedKey = primaryArtist(seedArtist)?.lowercase()
        // How many of the last few picks were the seed artist? Used to break out of a streak.
        val seedRun = sessionKeys.count { it == seedKey }
        return candidates
            .filter { c -> identity(c.track).none { it in exclude } }
            .filter { !profile.isDisliked(it.track) && !profile.skippedRecently(it.track) }
            .map { candidate ->
                val a = primaryArtist(candidate.track.artist)?.lowercase()
                var score = profile.score(candidate.track)
                // Continuity: the same artist is a small plus, but never a lock-in.
                if (a != null && a in sessionKeys) score += 0.8f
                if (a != null && a == current) score -= 1.5f
                // Monotony: the more this artist already appears, the less we want another one.
                if (a != null) score -= 1.0f * sessionKeys.count { it == a }
                // Exploration: an artist with no history gets a nudge, not a guarantee.
                if (profile.artistScore(candidate.track.artist) < 0.5f) score += 0.8f
                // Break out of an artist streak: after two picks from the seed artist, a track
                // by somebody else wins even with no history at all.
                if (a != null && a != seedKey && seedRun >= 2) score += 1.2f
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

        val out = ArrayList<Candidate>(96)
        fun add(from: List<EclipseAddon.Track>, source: String) {
            from.forEach { out += Candidate(it.toTrack(), source) }
        }

        // A. the seed's own catalogue.
        add(detail?.topTracks.orEmpty(), "artist")
        add(results.tracks.filter { it.artist.contains(artist, ignoreCase = true) }, "artist")
        detail?.albums.orEmpty().take(2).forEach { album ->
            add(runCatching { addon.album(album.id).tracks }.getOrDefault(emptyList()), "artist")
        }
        val sameArtistCount = out.size

        // B. playlists that match the seed: keep the *other* artists in them (a playlist that is
        // entirely the seed artist contributes nothing here, which is the point).
        val scene = results.playlists.take(3).flatMap { pl ->
            runCatching { addon.playlist(pl.id).tracks }.getOrDefault(emptyList())
        }.filterNot { it.artist.contains(artist, ignoreCase = true) }
        add(scene, "scene")

        // C. your own taste: top artists by decayed affinity, so the engine can leave the seed's
        //    catalogue entirely. Song affinity still dominates the ranking.
        val profile = Taste.profile(context)
        val seedKey = primaryArtist(artist)?.lowercase()
        val tasteArtists = profile.topArtists(6)
            .map { it.first }
            .filter { primaryArtist(it)?.lowercase() != seedKey }
            .take(3)
        tasteArtists.forEach { name ->
            val prefix = primaryArtist(name) ?: return@forEach
            val r = runCatching { addon.search(prefix, 6) }.getOrNull() ?: return@forEach
            val d = r.artists.firstOrNull { it.name.equals(prefix, ignoreCase = true) }
                ?.let { runCatching { addon.artist(it.id) }.getOrNull() }
            add(d?.topTracks?.take(10) ?: r.tracks, "taste")
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
