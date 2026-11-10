@file:OptIn(UnstableApi::class)

package dev.alamo3.cabineq.player

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.alamo3.cabineq.FilePlayer
import dev.alamo3.cabineq.MediaCache
import dev.alamo3.cabineq.Taste
import dev.alamo3.cabineq.Track
import dev.alamo3.cabineq.TrackResolver
import dev.alamo3.cabineq.addon.AddonNotReady
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The Media3 player behind [FilePlayer]'s controls (Settings → Playback → Player engine).
 *
 * Why it exists: the app's own player shares its queue and state between five threads, and the
 * gaps between them showed up in the car as silences and dead buttons. Here one thread — the
 * main looper — owns the playlist and the playback state. Every control arrives as a command
 * posted to that thread and is handled in order; Media3 does the fetching, buffering, decoding
 * and the hand-over between songs. What the rest of the app reads (state, position, the current
 * song) is published through [FilePlayer.Bridge].
 *
 * What stays ours:
 *  - the DSP: [DspAudioProcessor] runs the same native engine on the decoded audio;
 *  - finding the audio: [resolve] turns a queue entry into a stream link (Nebula, TorBox);
 *  - "still caching, play something else": [checkAhead] looks at the next two songs before
 *    their turn and moves one that TorBox has not finished aside, bringing it back when ready.
 *
 * Not carried over yet: loudness matching (the gain shows as off with this engine).
 */
internal class ExoEngine(private val owner: FilePlayer, private val bridge: FilePlayer.Bridge) {

    private val context: Context = bridge.context
    private val main = Handler(Looper.getMainLooper())
    val processor = DspAudioProcessor(bridge)

    // ---- main-thread state ---------------------------------------------------------------
    private var player: ExoPlayer? = null
    /** Queue keys in playlist order: the mirror of the player's queue, for index look-ups. */
    private val keys = ArrayList<String>()
    private var lastTrack: Track? = null
    private var lastDurationMs = 0L
    private var failures = 0
    /** Guards against a slow queue build landing after the queue was replaced. */
    private var startToken = 0
    private var rebuildRetries = 0
    private var radioAsks = 0
    private var ticking = false

    // ---- shared with the loader threads ----------------------------------------------------
    private val tracksByKey = ConcurrentHashMap<String, Track>()
    private class Link(val url: String, val quality: String?, val at: Long)
    private val links = ConcurrentHashMap<String, Link>()
    /** Songs moved aside while TorBox caches them (queue key → when). */
    private val deferred = ConcurrentHashMap<String, Long>()
    private val checking = ConcurrentHashMap.newKeySet<String>()
    private val checkPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "CabinEQ-Ahead").apply { isDaemon = true }
    }

    // For lyrics: the position at the last tick and when that was, so it can be read between ticks.
    @Volatile private var tickPositionMs = 0L
    @Volatile private var tickAt = 0L
    @Volatile private var advancing = false

    // ============================================================ commands (any thread)

    /** Starts the player's current queue at [index]. */
    fun start(index: Int, startAtMs: Long) = main.post {
        val (tracks, queueKeys) = bridge.snapshot()
        if (tracks.isEmpty()) return@post
        val p = ensurePlayer()
        lastTrack = null          // FilePlayer.play already reported what this replaces
        keys.clear()
        keys.addAll(queueKeys)
        tracksByKey.clear()
        deferred.clear()
        failures = 0
        radioAsks = 0
        bridge.error(null)
        // Building an item asks the disk cache whether the song is already on the phone, which is
        // file I/O: never on the main thread, and dropped if the queue is replaced meanwhile.
        val token = ++startToken
        checkPool.execute {
            val local = tracks.map { runCatching { MediaCache.fileFor(context, it) }.getOrNull() }
            main.post {
                if (token != startToken || player !== p) return@post
                val items = tracks.mapIndexed { i, t -> item(queueKeys[i], t, local[i]) }
                p.setMediaItems(items, index.coerceIn(0, items.lastIndex), startAtMs)
                p.repeatMode = repeatMode(owner.repeat)
                p.prepare()
                p.play()
                Log.i(TAG, "start: ${items.size} songs from $index at ${startAtMs}ms")
                startTicking()
            }
        }
    }

    fun pause() = main.post { player?.pause() }

    fun resume() = main.post {
        val p = player ?: return@post
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    fun skipTo(index: Int) = main.post {
        val p = player ?: return@post
        if (index !in 0 until p.mediaItemCount) return@post
        reportLeaving(skipped = true)
        p.seekToDefaultPosition(index)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    fun seekTo(ms: Long) = main.post { player?.seekTo(ms) }

    fun repeat(mode: FilePlayer.Repeat) = main.post { player?.repeatMode = repeatMode(mode) }

    /** Queue edits, in the order [FilePlayer] made them. */
    fun added(tracks: List<Track>, newKeys: List<String>) = main.post {
        val p = player ?: return@post
        val first = p.mediaItemCount
        keys.addAll(newKeys)
        p.addMediaItems(tracks.mapIndexed { i, t ->
            item(newKeys[i], t, runCatching { MediaCache.fileFor(context, t) }.getOrNull())
        })
        // The queue had run out and this is what it was waiting for (the radio's next pick).
        if (p.playbackState == Player.STATE_ENDED) {
            Log.i(TAG, "queue grew after it ended: playing \"${tracks.first().title}\"")
            radioAsks = 0
            p.seekToDefaultPosition(first)
            p.play()
        }
        checkAhead()
    }

    fun removed(index: Int) = main.post {
        val p = player ?: return@post
        if (index !in 0 until p.mediaItemCount) return@post
        val gone = keys.removeAt(index)
        tracksByKey.remove(gone)
        deferred.remove(gone)
        p.removeMediaItem(index)
    }

    fun moved(from: Int, to: Int) = main.post {
        val p = player ?: return@post
        if (from !in 0 until p.mediaItemCount || to !in 0 until p.mediaItemCount) return@post
        keys.add(to, keys.removeAt(from))
        p.moveMediaItem(from, to)
    }

    /** The output rate may have moved (new route, or the setting): let the DSP re-plan. */
    fun routeChanged() {
        main.removeCallbacks(rebuild)
        main.postDelayed(rebuild, 300)
    }

    private val rebuild = object : Runnable {
        override fun run() {
            val p = player ?: return
            val sourceRate = p.audioFormat?.sampleRate
            if (sourceRate == null) {
                // The route changed before the first format arrived: retry shortly (bounded).
                if (rebuildRetries++ < REBUILD_RETRIES) main.postDelayed(this, 300)
                else Log.i(TAG, "route changed, but there is no audio format to rebuild for yet")
                return
            }
            rebuildRetries = 0
            rebuildFor(p, sourceRate)
        }
    }

    private fun rebuildFor(p: ExoPlayer, sourceRate: Int) {
        val want = bridge.targetRate(sourceRate)
        if (processor.outputRate == null || processor.outputRate == want) {
            processor.refreshRouteDependentParams()
            return
        }
        // The engine is built for the old rate. Stopping and preparing again keeps the playlist
        // and the position, and makes Media3 configure the audio chain afresh.
        Log.i(TAG, "output rate moved to $want Hz: rebuilding the audio chain")
        val playing = p.playWhenReady
        p.stop()
        p.prepare()
        p.playWhenReady = playing
    }

    /** Stops and releases. The other engine, or nothing, plays next. */
    fun shutdown() = main.post {
        ticking = false
        // Only this engine's own timers: a start() posted right after must still run.
        main.removeCallbacks(ticker)
        main.removeCallbacks(askAgain)
        main.removeCallbacks(rebuild)
        reportLeaving(skipped = true)
        startToken++
        rebuildRetries = 0
        player?.release()
        player = null
        keys.clear()
        tracksByKey.clear()
        deferred.clear()
        links.clear()
        advancing = false
    }

    fun audiblePositionMs(): Long {
        val base = tickPositionMs
        return if (advancing) base + (SystemClock.elapsedRealtime() - tickAt).coerceIn(0L, 1_000L) else base
    }

    // ============================================================ the player (main thread)

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                // The DSP is the only processor. Float decoding stays off: Media3 would bypass
                // processors for float input, and the engine must never be bypassed.
                .setAudioProcessors(arrayOf(processor))
                .build()
        }
        val built = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_SYSTEM)
                    .build(),
                // FilePlayer already requests and abandons focus and reacts to losses (that is
                // the logic the car's projection host needed). Two focus managers fight: a
                // transient loss paused us twice, and abandoning on pause left Media3's manager
                // believing it still owned focus.
                /* handleAudioFocus = */ false,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLooper(Looper.getMainLooper())
            .build()
        built.addListener(listener)
        player = built
        return built
    }

    private fun item(key: String, track: Track, local: File?): MediaItem {
        tracksByKey[key] = track
        // Already on the phone (a download, or the other engine's cache): play the file.
        val uri = if (local != null && local.isFile) Uri.fromFile(local) else Uri.parse("$SCHEME://t/$key")
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(uri)
            // The stream links are short-lived tickets: cache by the song, not by the link.
            .setCustomCacheKey("song:" + track.mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()
            )
            .build()
    }

    private fun repeatMode(mode: FilePlayer.Repeat): Int = when (mode) {
        FilePlayer.Repeat.OFF -> Player.REPEAT_MODE_OFF
        FilePlayer.Repeat.ALL -> Player.REPEAT_MODE_ALL
        FilePlayer.Repeat.ONE -> Player.REPEAT_MODE_ONE
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onQueueEnded()
            publish("state $playbackState")
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish("playWhenReady=$playWhenReady ($reason)")

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = publish("suppression $playbackSuppressionReason")

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                failures = 0
                owner.setNote(null)
            }
            publish(null)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A song that played to its end is the strongest positive signal; anything else that
            // moved us on was reported as a skip by the command that caused it.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) reportLeaving(skipped = false)
            onSongChanged()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val format = player?.audioFormat ?: return
            bridge.format(format.sampleMimeType ?: "audio/unknown", format.sampleRate, format.channelCount)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            // Songs without catalogue artwork: the cover and tags embedded in the file itself.
            val track = currentTrack() ?: return
            if (track.artworkUrl != null) return
            val art = mediaMetadata.artworkData?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            bridge.info(
                FilePlayer.FileInfo(
                    art,
                    mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() },
                    mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() },
                    mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() },
                )
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val title = currentTrack()?.title ?: "song"
            val why = error.cause?.message ?: error.errorCodeName
            Log.w(TAG, "\"$title\" failed: ${error.errorCodeName}: $why")
            bridge.error("$title: $why")
            // The link it had may be what failed: never reuse it.
            keys.getOrNull(p.currentMediaItemIndex)?.let { links.remove(it) }
            failures++
            if (failures >= MAX_FAILURES || !p.hasNextMediaItem()) {
                advancing = false
                bridge.state(FilePlayer.State.ERROR, "playback error")
                return
            }
            // One bad song must not stop the music: move on, as the other engine does.
            p.seekToNextMediaItem()
            p.prepare()
            p.play()
        }
    }

    private fun currentTrack(): Track? {
        val p = player ?: return null
        return keys.getOrNull(p.currentMediaItemIndex)?.let { tracksByKey[it] }
    }

    private fun onSongChanged() {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val track = currentTrack()
        lastTrack = track
        lastDurationMs = track?.durationMs ?: 0L
        radioAsks = 0
        owner.setNote(null)
        bridge.clearFormat()
        bridge.info(null)
        bridge.duration(track?.durationMs ?: 0L, exact = false)
        bridge.index(index)
        bridge.position(p.currentPosition.coerceAtLeast(0))
        keys.getOrNull(index)?.let { deferred.remove(it) }
        // Tops the radio up (two ahead) and saves the queue.
        bridge.trackStarted()
        checkAhead()
    }

    /** Taste: where the listener left the song that is ending. */
    private fun reportLeaving(skipped: Boolean) {
        val track = lastTrack ?: return
        lastTrack = null
        val p = player
        val duration = lastDurationMs
        if (skipped) {
            val at = p?.currentPosition ?: tickPositionMs
            if (p != null && p.playbackState != Player.STATE_IDLE && at > 0) Taste.onSkip(context, track, at, duration)
        } else {
            Taste.onComplete(context, track, duration, duration)
        }
    }

    /** Turns Media3's state into the one the rest of the app shows. */
    private fun publish(why: String?) {
        val p = player ?: return
        val state = when {
            p.playerError != null -> FilePlayer.State.ERROR
            p.playbackState == Player.STATE_IDLE -> FilePlayer.State.IDLE
            p.playbackState == Player.STATE_ENDED ->
                if (waitingForMore()) FilePlayer.State.LOADING else FilePlayer.State.IDLE
            !p.playWhenReady -> FilePlayer.State.PAUSED
            p.playbackState == Player.STATE_BUFFERING -> FilePlayer.State.LOADING
            p.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> FilePlayer.State.PAUSED
            else -> FilePlayer.State.PLAYING
        }
        if (state != owner.state) bridge.state(state, why)
        tick()
    }

    // ---- the end of the queue --------------------------------------------------------------

    private fun waitingForMore(): Boolean = (bridge.radioOn || deferred.isNotEmpty()) && radioAsks <= RADIO_ASKS

    /**
     * Nothing left to play. With the radio on (or songs still caching) that is a pause, not the
     * end: ask for the next pick and wait. [added] starts it the moment it lands, on this same
     * thread, so an arrival can never fall between "the queue ended" and "is anything queued".
     */
    private fun onQueueEnded() {
        reportLeaving(skipped = false)
        advancing = false
        if (!bridge.radioOn && deferred.isEmpty()) return
        radioAsks = 0
        owner.setNote("Finding the next song…")
        main.removeCallbacks(askAgain)
        main.post(askAgain)
    }

    private val askAgain: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState != Player.STATE_ENDED) return
            if (radioAsks >= RADIO_ASKS && !bridge.radioLooking) {
                // Nothing came. Say so by going idle; play or next starts again from here.
                radioAsks = RADIO_ASKS + 1
                owner.setNote(null)
                publish("nothing more to play")
                return
            }
            if (bridge.radioOn && !bridge.radioLooking) {
                radioAsks++
                bridge.askRadio()
            }
            recheckDeferred()
            main.postDelayed(this, ASK_EVERY_MS)
        }
    }

    // ---- position --------------------------------------------------------------------------

    private fun startTicking() {
        if (ticking) return
        ticking = true
        main.post(ticker)
    }

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            if (!ticking || player == null) {
                ticking = false
                return
            }
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private var lastDeferredCheck = 0L

    private fun tick() {
        val p = player ?: return
        val position = p.currentPosition.coerceAtLeast(0)
        tickPositionMs = position
        tickAt = SystemClock.elapsedRealtime()
        advancing = p.isPlaying
        if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) {
            bridge.position(position)
            val duration = p.duration
            if (duration != C.TIME_UNSET && duration > 0) {
                lastDurationMs = duration
                bridge.duration(duration, exact = true)
            }
        }
        if (deferred.isNotEmpty() && tickAt - lastDeferredCheck > DEFERRED_RECHECK_MS) {
            lastDeferredCheck = tickAt
            recheckDeferred()
        }
    }

    // ============================================================ finding the audio

    /**
     * Where a song's bytes come from. Local files are read directly. Everything else goes
     * through Media3's disk cache (a song plays while it downloads, a part-downloaded one is
     * continued, a complete one needs no network), and below that the resolver, which is asked
     * for a link only when bytes actually have to come from the network.
     */
    private fun dataSources(): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("CabinEQ")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val resolving = ResolvingDataSource.Factory(http) { spec -> resolve(spec) }
        val remote: DataSource.Factory = cache(context)?.let { cache ->
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(resolving)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: resolving
        val files = FileDataSource.Factory()
        return DataSource.Factory { BySchemeDataSource(files.createDataSource(), remote.createDataSource()) }
    }

    /**
     * Called on a Media3 loading thread, just before a request: swaps the queue entry's own
     * address for a real stream link. Waiting on TorBox happens here for a song that is wanted
     * now (its progress shows as the player's note); Media3 interrupts this thread when the
     * song is skipped, which ends the wait at once.
     */
    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SCHEME) return spec
        val key = spec.uri.lastPathSegment ?: throw IOException("no queue entry in ${spec.uri.scheme} address")
        val track = tracksByKey[key] ?: throw IOException("that song is no longer in the queue")
        val now = SystemClock.elapsedRealtime()
        val known = links[key]?.takeIf { now - it.at < LINK_TTL_MS }
        val link = known ?: try {
            val resolved = TrackResolver.resolve(
                context,
                track,
                onNote = { owner.setNote(it) },
                isCancelled = { Thread.currentThread().isInterrupted },
            )
            Link(resolved.url, resolved.quality, SystemClock.elapsedRealtime()).also { links[key] = it }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // e.g. "Couldn't find it on Nebula": a load error, so the player moves on.
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
        link.quality?.let { q -> main.post { if (currentTrack() === track) bridge.quality(q) } }
        return spec.withUri(Uri.parse(link.url))
    }

    /** Local files straight from disk; everything else through the cache and the resolver. */
    private class BySchemeDataSource(private val file: DataSource, private val remote: DataSource) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            file.addTransferListener(transferListener)
            remote.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = if (dataSpec.uri.scheme == "file" || dataSpec.uri.scheme == null) file else remote
            active = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            (active ?: throw IOException("not open")).read(buffer, offset, length)

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }

    // ============================================================ still caching? play another

    /**
     * Looks at the next two songs before their turn. One that is ready gets its link remembered,
     * so its start needs no lookup. One that TorBox is still caching is moved two places back
     * (the request itself is what started the caching) and brought forward again when ready.
     */
    private fun checkAhead() {
        val p = player ?: return
        val at = p.currentMediaItemIndex
        for (index in at + 1..at + 2) {
            val key = keys.getOrNull(index) ?: continue
            val track = tracksByKey[key] ?: continue
            if (p.getMediaItemAt(index).localConfiguration?.uri?.scheme != SCHEME) continue
            if (deferred.containsKey(key)) continue
            probe(key, track, bringBack = false)
        }
    }

    private fun recheckDeferred() {
        val now = SystemClock.elapsedRealtime()
        for ((key, since) in deferred) {
            val track = tracksByKey[key]
            if (track == null || now - since > DEFERRED_MAX_AGE_MS) {
                deferred.remove(key)
                continue
            }
            probe(key, track, bringBack = true)
        }
    }

    private fun probe(key: String, track: Track, bringBack: Boolean) {
        val fresh = links[key]?.let { SystemClock.elapsedRealtime() - it.at < LINK_TTL_MS } == true
        if (fresh && !bringBack) return
        if (!checking.add(key)) return
        checkPool.execute {
            try {
                val resolved = TrackResolver.resolve(context, track, { }, { false }, 20_000L, waitIfPending = false)
                links[key] = Link(resolved.url, resolved.quality, SystemClock.elapsedRealtime())
                if (bringBack) main.post { bringForward(key, track) }
            } catch (e: AddonNotReady) {
                if (!bringBack) main.post { setAside(key, track) }
            } catch (e: Exception) {
                // Not found, or the network: left for its turn, where it fails and is skipped.
                Log.i(TAG, "look-ahead for \"${track.title}\": ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                checking.remove(key)
            }
        }
    }

    private fun setAside(key: String, track: Track) {
        val p = player ?: return
        val index = keys.indexOf(key)
        if (index <= p.currentMediaItemIndex) return          // its turn already came
        if (deferred.putIfAbsent(key, SystemClock.elapsedRealtime()) != null) return
        Log.i(TAG, "\"${track.title}\" is still caching on TorBox: playing others first, will come back to it")
        if (index < keys.lastIndex) {
            // Through the player's own queue editing, so the lists the app shows move with it.
            owner.moveUpcoming(key, minOf(index + 2, keys.lastIndex))
        } else if (bridge.radioOn) {
            bridge.askRadio()        // it was the last one: get something to play before it
        }
    }

    private fun bringForward(key: String, track: Track) {
        val p = player ?: return
        if (deferred.remove(key) == null) return
        val index = keys.indexOf(key)
        val next = p.currentMediaItemIndex + 1
        if (index < 0) return
        Log.i(TAG, "\"${track.title}\" is ready now: playing it next")
        if (index > next) owner.moveUpcoming(key, next)
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekToDefaultPosition(keys.indexOf(key))
            p.play()
        }
    }

    companion object {
        private const val TAG = "CabinEQ/Exo"
        /** The address a queue entry has until [resolve] swaps in a real link. */
        private const val SCHEME = "cabineq"
        private const val TICK_MS = 250L
        private const val MAX_FAILURES = 3
        private const val RADIO_ASKS = 4
        private const val ASK_EVERY_MS = 3_000L
        /** How long a resolved link is reused. They are short-lived tickets. */
        private const val LINK_TTL_MS = 90_000L
        private const val DEFERRED_RECHECK_MS = 20_000L
        private const val DEFERRED_MAX_AGE_MS = 10 * 60_000L
        private const val REBUILD_RETRIES = 10

        @Volatile private var sharedCache: SimpleCache? = null

        /** One cache per process (Media3 allows a single instance per folder). Null when the song cache is off. */
        private fun cache(context: Context): SimpleCache? {
            val settings = dev.alamo3.cabineq.Settings(context)
            if (!settings.cacheEnabled) return null
            return sharedCache ?: synchronized(this) {
                sharedCache ?: runCatching {
                    SimpleCache(
                        File(context.cacheDir, "media3"),
                        LeastRecentlyUsedCacheEvictor(settings.cacheMaxMb.toLong() * 1024 * 1024),
                        StandaloneDatabaseProvider(context),
                    )
                }.onFailure { Log.w(TAG, "no disk cache: ${it.message}") }.getOrNull()?.also { sharedCache = it }
            }
        }
    }
}
