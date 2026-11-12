@file:OptIn(UnstableApi::class)

package dev.alamo3.cabineq.player

import dev.alamo3.cabineq.DLog
import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.alamo3.cabineq.FilePlayer
import dev.alamo3.cabineq.MediaCache
import dev.alamo3.cabineq.Taste
import dev.alamo3.cabineq.BuildConfig
import dev.alamo3.cabineq.Track
import dev.alamo3.cabineq.Settings
import dev.alamo3.cabineq.TrackResolver
import dev.alamo3.cabineq.YouTubeCompanion
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
 * Everything that touches the player runs on the main thread: the command methods post, and the
 * mutable fields of this class are main-thread state (the shared maps say so). A command that
 * arrives before the player can take it is not dropped — it waits in [Intents] and
 * [applyIntents] carries it out. The queue is mirrored in [keys], changed only by the four
 * functions that also change the player's playlist, and compared against it in debug builds.
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
    /**
     * The DSP sink of the player that exists now, if any. One per player, never reused: a sink
     * holds an AudioTrack and the thread that built it, and handing the previous one to a new
     * player is what made Media3 report "accessed on multiple threads" after stop and play.
     */
    @Volatile private var sinkRef: DspAudioSink? = null
    val sink: DspAudioSink? get() = sinkRef

    /** What the decoder is feeding the DSP, for the status report (null before the first song). */
    val inputDescription: String? get() = sinkRef?.inputDescription

    // ---- main-thread state ---------------------------------------------------------------
    private var player: ExoPlayer? = null
    /**
     * Queue keys in playlist order, together with the player's own playlist: both are changed by
     * the same call (see [QueueMirror]), so they cannot drift apart.
     */
    private val queue = QueueMirror<MediaItem>(object : QueueMirror.Playlist<MediaItem> {
        override val itemCount: Int get() = player?.mediaItemCount ?: 0
        override fun add(items: List<MediaItem>) { player?.addMediaItems(items) }
        override fun removeAt(index: Int) { player?.removeMediaItem(index) }
        override fun move(from: Int, to: Int) { player?.moveMediaItem(from, to) }
    })
    private val keys: List<String> get() = queue.keys
    private var lastTrack: Track? = null
    private var lastDurationMs = 0L
    private var failures = 0
    /** Guards against a slow queue build landing after the queue was replaced. */
    private var startToken = 0
    private var rebuildRetries = 0
    private var radioAsks = 0
    private var ticking = false

    // ---- the connection ------------------------------------------------------------------
    /** The song failed because there is no connection: holding its place until there is one. */
    private var waitingForNetwork = false
    /** Failures in a row for the playing song while online (a flaky link, a slow server). */
    private var networkRetries = 0
    /** Times the playing song's refused link was swapped for a fresh one (once is enough). */
    private var linkRetries = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ---- the end of the queue ---------------------------------------------------------------
    /** When the queue ran out, and whether the wait for a next song was given up. */
    private var endedAt = 0L
    private var gaveUp = false

    /**
     * Commands that arrived before the player could take them: a start still reading the disk
     * cache, or no player built yet. Nothing is dropped; see [Intents].
     */
    private val intents = Intents()

    // ---- shared with the loader threads ----------------------------------------------------
    private val tracksByKey = ConcurrentHashMap<String, Track>()
    private class Link(val url: String, val quality: String?, val at: Long, val headers: Map<String, String> = emptyMap(), val via: String = "TorBox") {
        fun fresh(now: Long = SystemClock.elapsedRealtime()) = now - at < TrackResolver.ttlFor(via)
    }
    private val links = ConcurrentHashMap<String, Link>()
    /** Songs moved aside while TorBox caches them (queue key → when). */
    private val deferred = ConcurrentHashMap<String, Long>()
    private val checking = ConcurrentHashMap.newKeySet<String>()
    /** TorBox said all its download slots are busy: no look-ahead asks until this time. */
    @Volatile private var torboxBusyUntil = 0L
    /** When the playing song started waiting with nothing on screen to say why (0: not waiting). */
    private var quietWaitSince = 0L
    private val checkPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "CabinEQ-Ahead").apply { isDaemon = true }
    }

    /** The queue key of the song playing now; read by the loader threads (see [resolve]). */
    @Volatile private var currentKey: String? = null

    // Whole-song downloads of the next few songs, on their own threads: a song takes minutes on a
    // slow link and must never hold up the quick look-ups on [checkPool].
    private val prefetching = ConcurrentHashMap.newKeySet<String>()
    /** Songs already cached whole: not looked at again at every song change. */
    private val prefetched = ConcurrentHashMap.newKeySet<String>()
    private val writers = ConcurrentHashMap<String, CacheWriter>()
    private val prefetchPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "CabinEQ-Prefetch").apply { isDaemon = true }
    }

    private val http: DefaultHttpDataSource.Factory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent("CabinEQ")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
    }

    // For lyrics: the position at the last tick and when that was, so it can be read between ticks.
    @Volatile private var tickPositionMs = 0L
    /** How much of the playing song is buffered past the playhead, for the downloads to wait on. */
    @Volatile private var bufferedAheadMs = 0L
    @Volatile private var tickAt = 0L
    @Volatile private var advancing = false

    // ============================================================ commands (any thread)

    /** Starts the player's current queue at [index]. */
    fun start(index: Int, startAtMs: Long) {
        val (tracks, queueKeys) = bridge.snapshot()
        if (tracks.isEmpty()) return
        // play() is called from a background thread, so the question "is this song already on the
        // phone?" (file I/O) is answered right here, rather than after two more hops through a
        // pool and the main thread, each of which waits its turn behind whatever the UI is doing.
        // Called from the main thread it still must not touch the disk: that case takes the pool.
        val local = if (Looper.myLooper() == Looper.getMainLooper()) null else localFiles(tracks)
        main.post { begin(tracks, queueKeys, local, index, startAtMs) }
    }

    private fun localFiles(tracks: List<Track>): List<File?> =
        tracks.map { runCatching { MediaCache.fileFor(context, it) }.getOrNull() }

    /** Main thread: resets what belongs to the old queue and starts the new one. */
    private fun begin(tracks: List<Track>, queueKeys: List<String>, local: List<File?>?, index: Int, startAtMs: Long) {
        onMain()
        val p = ensurePlayer()
        lastTrack = null          // FilePlayer.play already reported what this replaces
        queue.loaded(queueKeys)
        cancelAllPrefetch()
        tracksByKey.clear()
        qualities.clear()
        deferred.clear()
        publishWaiting()
        failures = 0
        radioAsks = 0
        gaveUp = false
        intents.start(index, startAtMs)   // the items exist only when load() runs
        stopWaitingForNetwork()
        bridge.error(null)
        // Dropped if the queue is replaced while the files are being looked up.
        val token = ++startToken
        if (local != null) {
            load(p, tracks, queueKeys, local, index, startAtMs)
            return
        }
        checkPool.execute {
            val files = localFiles(tracks)
            main.post {
                if (token != startToken || player !== p) return@post
                load(p, tracks, queueKeys, files, index, startAtMs)
            }
        }
    }

    private fun load(
        p: ExoPlayer,
        tracks: List<Track>,
        queueKeys: List<String>,
        local: List<File?>,
        index: Int,
        startAtMs: Long,
    ) {
        onMain()
        val items = tracks.mapIndexed { i, t -> item(queueKeys[i], t, local[i]) }
        val onPhone = local.count { it != null && it.isFile }
        DLog.i(TAG, "queue of ${items.size} songs: $onPhone on the phone, ${items.size - onPhone} streamed")
        // Anything asked for while the queue was being built is folded in here: a skip tapped
        // before there were items starts the queue on that song, a pause keeps it paused.
        val ready = intents.onQueueReady()
        val at = (ready?.index ?: index).coerceIn(0, items.lastIndex)
        val position = ready?.positionMs ?: startAtMs
        p.setMediaItems(items, at, position)
        p.repeatMode = repeatMode(owner.repeat)
        p.prepare()
        if (ready?.play == false) p.pause() else p.play()
        DLog.i(TAG, "queue: ${items.size} songs, starting at $at from ${position}ms" +
            if (ready?.play == false) ", paused" else "")
        queue.check("load", BuildConfig.DEBUG)
        startTicking()
        applyIntents()           // commands that landed while the items were being built
    }

    /**
     * Does the slow parts of the first start ahead of time: the disk cache opens a database (off
     * the main thread), then the player itself is built (on it). Nothing plays.
     */
    fun warmUp() {
        checkPool.execute {
            runCatching { cache(context) }
            main.post { ensurePlayer() }
        }
    }

    fun pause() = main.post { intents.setPlaying(false); applyIntents() }

    fun resume() = main.post { intents.setPlaying(true); applyIntents() }

    fun skipTo(index: Int) = main.post {
        if (index < 0) return@post
        intents.skipTo(index)
        applyIntents()
    }

    fun seekTo(ms: Long) = main.post { intents.seekTo(ms); applyIntents() }

    /**
     * Carries out whatever is waiting, as far as the player can take it. Anything it cannot take
     * yet stays in [Intents] — that is the point — and the next call (after a load, a command, a
     * song change) tries again.
     */
    private fun applyIntents() {
        onMain()
        val p = player
        if (p == null || p.mediaItemCount == 0) {
            if (!intents.isEmpty) DLog.i(TAG, "held until the queue exists: ${intents.describe()}")
            return
        }
        intents.takeSkip()?.let { raw ->
            val index = raw.coerceIn(0, p.mediaItemCount - 1)
            DLog.i(TAG, "skip to $index")
            reportLeaving(skipped = true)
            stopWaitingForNetwork()
            p.seekToDefaultPosition(index)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
        intents.takeSeek()?.let { ms ->
            DLog.i(TAG, "seek to $ms ms")
            p.seekTo(ms)
        }
        intents.takePlaying()?.let { play ->
            DLog.i(TAG, if (play) "play" else "pause")
            if (play) {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.play()
            } else {
                p.pause()
            }
        }
    }

    /** Everything that touches the player runs here; a caller on another thread posts first. */
    private fun onMain() {
        if (BuildConfig.DEBUG) check(Looper.myLooper() == Looper.getMainLooper()) {
            "player state touched off the main thread"
        }
    }


    fun repeat(mode: FilePlayer.Repeat) = main.post { player?.repeatMode = repeatMode(mode) }

    /** Queue edits, in the order [FilePlayer] made them. */
    fun added(tracks: List<Track>, newKeys: List<String>) = main.post {
        val p = player
        if (p == null || p.mediaItemCount == 0) {
            // Nothing is loaded (the queue is still being built, or was stopped). The next start
            // reads FilePlayer's own list, which already has these; say so rather than desync.
            DLog.i(TAG, "queue edit before the queue exists: ${tracks.size} songs wait for the next start")
            return@post
        }
        val items = tracks.mapIndexed { i, t ->
            item(newKeys[i], t, runCatching { MediaCache.fileFor(context, t) }.getOrNull())
        }
        val first = queue.added(items, newKeys)
        // The queue had run out and this is what it was waiting for (the radio's next pick).
        if (p.playbackState == Player.STATE_ENDED) {
            DLog.i(TAG, "queue grew after it ended: playing \"${tracks.first().title}\"")
            radioAsks = 0
            gaveUp = false
            p.seekToDefaultPosition(first)
            p.play()
        }
        queue.check("added", BuildConfig.DEBUG)
        checkAhead()
    }

    fun removed(index: Int) = main.post {
        val p = player
        if (p == null || index !in 0 until p.mediaItemCount) {
            DLog.i(TAG, "remove of $index ignored: the queue is not in the player")
            return@post
        }
        val gone = queue.removedAt(index)
        if (gone == null) {
            DLog.i(TAG, "remove of $index ignored: it is not in the queue mirror")
            return@post
        }
        tracksByKey.remove(gone)
        if (deferred.remove(gone) != null) publishWaiting()
        cancelPrefetch(gone)
        queue.check("removed", BuildConfig.DEBUG)
    }

    fun moved(from: Int, to: Int) = main.post {
        val p = player
        if (p == null || from !in 0 until p.mediaItemCount || to !in 0 until p.mediaItemCount) {
            DLog.i(TAG, "move $from -> $to ignored: the queue is not in the player")
            return@post
        }
        if (!queue.moved(from, to)) {
            DLog.i(TAG, "move $from -> $to ignored: it is not in the queue mirror")
            return@post
        }
        queue.check("moved", BuildConfig.DEBUG)
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
                else DLog.i(TAG, "route changed, but there is no audio format to rebuild for yet")
                return
            }
            rebuildRetries = 0
            rebuildFor(p, sourceRate)
        }
    }

    private fun rebuildFor(p: ExoPlayer, sourceRate: Int) {
        val sink = sinkRef ?: return
        val want = bridge.targetRate(sourceRate)
        if (sink.outputRate == null || sink.outputRate == want) {
            sink.refreshRouteDependentParams()
            return
        }
        // The engine is built for the old rate. Stopping and preparing again keeps the playlist
        // and the position, and makes Media3 configure the audio chain afresh.
        DLog.i(TAG, "output rate moved to $want Hz: rebuilding the audio chain")
        val playing = p.playWhenReady
        p.stop()
        p.prepare()
        p.playWhenReady = playing
    }

    /** Stops and releases. The other engine, or nothing, plays next. */
    fun shutdown() = main.post {
        onMain()
        ticking = false
        // Only this engine's own timers: a start() posted right after must still run.
        main.removeCallbacks(ticker)
        main.removeCallbacks(askAgain)
        main.removeCallbacks(rebuild)
        reportLeaving(skipped = true)
        startToken++
        rebuildRetries = 0
        stopWaitingForNetwork()
        unregisterNetworkCallback()
        player?.release()
        player = null
        sinkRef = null
        queue.cleared()
        cancelAllPrefetch()
        tracksByKey.clear()
        deferred.clear()
        intents.clear()
        publishWaiting()
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
        val freshSink = DspAudioSink(bridge)
        sinkRef = freshSink
        val renderers = object : DefaultRenderersFactory(context) {
            // The DSP is the sink itself (see DspAudioSink): float in, float out, no processor
            // chain for Media3 to skip.
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = freshSink
        }
        // Audio is cheap to hold: keep a minute or more buffered, and refill early, so a slow
        // stretch of road is ridden out of the buffer, not heard.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 180_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val built = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
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
        // The next song is prepared and its first seconds buffered while this one plays, so a skip
        // (or the end of a song) moves into audio that is already waiting.
        built.preloadConfiguration = ExoPlayer.PreloadConfiguration(PRELOAD_NEXT_US)
        built.addListener(listener)
        player = built
        registerNetworkCallback()
        return built
    }

    /** Quality labels by queue entry, as their links reported them. */
    private val qualities = ConcurrentHashMap<String, String>()

    /** For a song playing from a file on the phone: which kind of copy it is. */
    private fun localLabel(p: Player, index: Int): String? {
        val uri = runCatching { p.getMediaItemAt(index).localConfiguration?.uri }.getOrNull() ?: return null
        if (uri.scheme != "file") return null
        val path = uri.path.orEmpty()
        return when {
            "/upgrades/" in path -> "Upgraded · lossless"
            "/downloads" in path -> "Downloaded"
            else -> "On the phone"
        }
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
                networkRetries = 0
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
            val why = error.cause?.let { SourceTrouble.of(it).describe(it) } ?: error.errorCodeName
            DLog.w(TAG, "\"$title\" failed: ${error.errorCodeName}: $why")
            // The link it had may be what failed: never reuse it.
            keys.getOrNull(p.currentMediaItemIndex)?.let { key ->
                links.remove(key)
                tracksByKey[key]?.let { TrackResolver.forget(it) }
            }
            // A link that has expired is refused: that is not a bad song. Ask for a fresh link and
            // try the same song once more, from the same second, before treating it as failed.
            if (linkRetries < 1 && currentIsRemote(p) && wasLinkRefused(error)) {
                linkRetries++
                DLog.i(TAG, "\"$title\": its link was refused (expired?): asking for a fresh one")
                // Forget the refused link everywhere, or the retry is handed the same one again
                // (YouTube links are kept for hours, and they die early when the phone's network
                // changes from Wi-Fi to mobile data).
                currentKey?.let { k ->
                    links.remove(k)
                    tracksByKey[k]?.let { TrackResolver.forget(it) }
                }
                p.prepare()
                return
            }
            // No road signal is not a bad song: do not burn through the queue (or give up) for it.
            if (isConnectivityError(p, error)) {
                onConnectionLost(p, title)
                return
            }
            // A YouTube pick that will not play may be the wrong video: let the companion search
            // again next time instead of returning the same one.
            currentKey?.let { k -> if (links[k]?.via == "YouTube") tracksByKey[k]?.let { dev.alamo3.cabineq.YouTubeCompanion.forget(context, it) } }
            bridge.error("$title: $why")
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

    // ---- losing and regaining the connection ---------------------------------------------

    /** The server answered the song's link with "no": expired, or no longer valid. */
    private fun wasLinkRefused(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth++ < 6) {
            if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode in REFUSED_LINK_CODES) return true
            cause = cause.cause
        }
        return false
    }

    /** Whether the playing entry is fetched over the network (not a file on the phone). */
    private fun currentIsRemote(p: ExoPlayer): Boolean =
        p.currentMediaItem?.localConfiguration?.uri?.scheme.let { it != null && it != "file" }

    /**
     * A failure of the network, not of the song: offline, or one of the usual network errors in
     * the cause chain. A local file that fails is never this.
     */
    private fun isConnectivityError(p: ExoPlayer, error: PlaybackException): Boolean {
        if (!currentIsRemote(p)) return false
        if (!MediaCache.isOnline(context)) return true
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> return true
        }
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth++ < 6) {
            if (cause is java.net.UnknownHostException || cause is java.net.SocketTimeoutException ||
                cause is java.net.ConnectException || cause is java.net.NoRouteToHostException
            ) return true
            cause = cause.cause
        }
        return false
    }

    /** Whether entry [index] plays with no network: a file on the phone, or cached whole. */
    private fun playableOffline(p: ExoPlayer, index: Int): Boolean {
        val item = p.getMediaItemAt(index)
        if (item.localConfiguration?.uri?.scheme == "file") return true
        val track = tracksByKey[item.mediaId] ?: return false
        return cachedWhole(context, track)
    }

    /**
     * The song needed the network and there is none. Songs that need none (downloads, ones
     * already cached whole) play in the meantime, in front of the rest; when there are none, this
     * holds the song and its position and carries on from the same second once the connection is
     * back. While online, a song that keeps failing is given up on after a few tries.
     */
    private fun onConnectionLost(p: ExoPlayer, title: String) {
        val online = MediaCache.isOnline(context)
        if (online && ++networkRetries > MAX_NETWORK_RETRIES) {
            DLog.w(TAG, "\"$title\" keeps timing out while online: giving up on it")
            networkRetries = 0
            bridge.error("$title: the connection keeps failing")
            failures++
            if (failures >= MAX_FAILURES || !p.hasNextMediaItem()) {
                advancing = false
                bridge.state(FilePlayer.State.ERROR, "playback error")
                return
            }
            p.seekToNextMediaItem()
            p.prepare()
            p.play()
            return
        }
        val at = p.currentMediaItemIndex
        val offlineAt = (at + 1 until p.mediaItemCount).firstOrNull { playableOffline(p, it) }
        if (offlineAt != null) {
            val key = keys.getOrNull(offlineAt)
            DLog.i(TAG, "no connection for \"$title\": playing a song that needs none first")
            if (key != null && offlineAt > at + 1) owner.moveUpcoming(key, at + 1)
            // Posted behind the move above, which is itself posted: it is in place by then.
            main.post {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
            return
        }
        waitingForNetwork = true
        advancing = false
        owner.setNote(if (online) "Connection trouble — retrying…" else "No connection — will carry on when it is back")
        publish("waiting for the connection")
        main.removeCallbacks(networkRetry)
        main.postDelayed(networkRetry, if (online) minOf(2_000L shl (networkRetries - 1).coerceAtLeast(0), 15_000L) else NETWORK_POLL_MS)
    }

    /** Tries the song again if the connection is back; otherwise looks again shortly. */
    private val networkRetry: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!waitingForNetwork) return
            if (!MediaCache.isOnline(context)) {
                main.postDelayed(this, NETWORK_POLL_MS)
                return
            }
            DLog.i(TAG, "connection is back: carrying on")
            waitingForNetwork = false
            owner.setNote(null)
            // Same song, same position; it plays if it was meant to (a pause stays a pause).
            p.prepare()
            publish("connection back")
        }
    }

    private fun stopWaitingForNetwork() {
        waitingForNetwork = false
        networkRetries = 0
        main.removeCallbacks(networkRetry)
    }

    /** Told the moment a network appears, rather than finding out on the next poll. */
    private val onNetworkBack = Runnable {
        if (waitingForNetwork) {
            main.removeCallbacks(networkRetry)
            main.post(networkRetry)
        }
        // The queue ran out while there was no connection: ask the radio again now.
        val p = player
        if (p != null && p.playbackState == Player.STATE_ENDED && !gaveUp) {
            main.removeCallbacks(askAgain)
            main.post(askAgain)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post(onNetworkBack)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { DLog.w(TAG, "no connection callback: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    private fun currentTrack(): Track? {
        val p = player ?: return null
        return keys.getOrNull(p.currentMediaItemIndex)?.let { tracksByKey[it] }
    }

    private fun onSongChanged() {
        onMain()
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val track = currentTrack()
        currentKey = keys.getOrNull(index)
        lastTrack = track
        lastDurationMs = track?.durationMs ?: 0L
        radioAsks = 0
        gaveUp = false
        networkRetries = 0
        linkRetries = 0
        quietWaitSince = 0L
        owner.setNote(null)
        bridge.clearFormat()
        // What this song is: the label its link came with (looked up ahead, often before its
        // turn), or where its file came from when it plays from the phone.
        currentKey?.let { k -> (qualities[k] ?: localLabel(p, index))?.let { bridge.quality(it) } }
        bridge.info(null)
        bridge.duration(track?.durationMs ?: 0L, exact = false)
        bridge.index(index)
        bridge.position(p.currentPosition.coerceAtLeast(0))
        if (keys.getOrNull(index)?.let { deferred.remove(it) } != null) publishWaiting()
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
        val error = p.playerError
        val state = when {
            waitingForNetwork -> FilePlayer.State.LOADING
            // Media3 reports "idle, with an error" just before onPlayerError, which is where a lost
            // connection is dealt with: it must not show as a failure for the moment in between.
            error != null && isConnectivityError(p, error) -> FilePlayer.State.LOADING
            error != null -> FilePlayer.State.ERROR
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

    private fun waitingForMore(): Boolean = (bridge.radioOn || deferred.isNotEmpty()) && !gaveUp

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
        gaveUp = false
        endedAt = SystemClock.elapsedRealtime()
        owner.setNote("Finding the next song…")
        main.removeCallbacks(askAgain)
        main.post(askAgain)
    }

    private val askAgain: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState != Player.STATE_ENDED) return
            // Keep asking, quickly at first and then every few seconds, and the moment a network
            // appears: a dead spot ends, and the music should carry on by itself when it does.
            // Only after a long time with nothing does it go idle (play or next starts it again).
            if (SystemClock.elapsedRealtime() - endedAt > END_WAIT_MS && !bridge.radioLooking) {
                gaveUp = true
                owner.setNote(null)
                publish("nothing more to play")
                return
            }
            owner.setNote(
                if (MediaCache.isOnline(context)) "Finding the next song…"
                else "No connection — will carry on when it is back"
            )
            if (bridge.radioOn && !bridge.radioLooking) {
                radioAsks++
                bridge.askRadio()
            }
            recheckDeferred()
            main.postDelayed(this, if (radioAsks < RADIO_ASKS) ASK_EVERY_MS else ASK_SLOW_MS)
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
        bufferedAheadMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
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
        // Waiting with nothing on screen to say why (the source answered, the bytes are slow):
        // after a few seconds, say so, rather than sit on 0:00 in silence.
        if (p.playbackState == Player.STATE_BUFFERING && p.playWhenReady && owner.note == null) {
            if (quietWaitSince == 0L) quietWaitSince = tickAt
            if (tickAt - quietWaitSince > QUIET_WAIT_MS) {
                owner.setNote(
                    if (!MediaCache.isOnline(context)) "No connection — waiting for it"
                    else if (links[currentKey ?: ""] != null) "Downloading — slow connection"
                    else "Waiting on Nebula…"
                )
            }
        } else if (p.playbackState != Player.STATE_BUFFERING) {
            quietWaitSince = 0L
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
        val known = links[key]?.takeIf { it.fresh(now) }
        val link = known ?: try {
            val asked = SystemClock.elapsedRealtime()
            // The next songs are prepared while this one plays: only the one being played gets
            // to say what it is waiting for, or the player would announce a song that is not playing.
            val note: (String?) -> Unit = { if (currentKey == key) owner.setNote(it) }
            note("Finding a source on Nebula…")
            var resolved: TrackResolver.Resolved? = null
            while (resolved == null) {
                try {
                    resolved = TrackResolver.resolve(context, track, onNote = note, isCancelled = { Thread.currentThread().isInterrupted })
                } catch (e: Exception) {
                    // All TorBox slots busy is temporary: for the song that is wanted now, wait for
                    // a free slot (saying so) instead of skipping it. A queued one just fails.
                    val waited = SystemClock.elapsedRealtime() - asked
                    if (SourceTrouble.of(e) != SourceTrouble.TORBOX_FULL || currentKey != key || waited > TORBOX_FULL_WAIT_MS) throw e
                    torboxBusyUntil = SystemClock.elapsedRealtime() + TORBOX_BUSY_MS
                    note("TorBox is at its download limit — waiting for a free slot")
                    DLog.i(TAG, "\"${track.title}\": TorBox download limit, retrying in ${TORBOX_RETRY_MS / 1000} s")
                    Thread.sleep(TORBOX_RETRY_MS)
                }
            }
            val got = resolved!!
            note("Connecting to ${got.via}…")
            // Played from YouTube: look for a better copy on Nebula for next time (Wi-Fi only).
            if (got.via == "YouTube") runCatching { dev.alamo3.cabineq.Upgrades.consider(context, track) }
            Link(got.url, got.quality, SystemClock.elapsedRealtime(), got.headers, got.via).also {
                links[key] = it
                DLog.i(TAG, "link for \"${track.title}\" in ${it.at - asked} ms")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled")
        } catch (e: IOException) {
            if (e is InterruptedIOException) throw e
            // Kept as the cause: the connection checks look through it.
            throw IOException(SourceTrouble.of(e).describe(e), e)
        } catch (e: Exception) {
            // e.g. "Couldn't find it on Nebula": a load error, so the player moves on, saying why.
            throw IOException(SourceTrouble.of(e).describe(e), e)
        }
        link.quality?.let { q ->
            qualities[key] = q
            main.post { if (currentTrack() === track) bridge.quality(q) }
        }
        val out = spec.withUri(Uri.parse(link.url))
        return if (link.headers.isEmpty()) out else out.withAdditionalHeaders(link.headers)
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
        // Asking for a song's link is also what makes TorBox start caching it, so the further
        // ahead this looks, the more head start a slow song gets.
        // YouTube has no download slots to protect: with it first, prepare much further ahead, so
        // even fast skipping lands on songs whose links are already there.
        val ahead = if (YouTubeCompanion.usable(context) && Settings(context).youtubeSource == Settings.YOUTUBE_FIRST) AHEAD_PROBE_YOUTUBE else AHEAD_PROBE
        for (index in at + 1..at + ahead) {
            val key = keys.getOrNull(index) ?: continue
            val track = tracksByKey[key] ?: continue
            if (p.getMediaItemAt(index).localConfiguration?.uri?.scheme != SCHEME) continue
            // A song set aside earlier is looked at again when its turn comes round: still not
            // ready means back it goes, rather than playing it and sitting through the wait.
            probe(key, track, bringBack = false)
            if (index <= at + AHEAD_PREFETCH) startPrefetch(key, track)
        }
    }

    /** A look-up just found [key] ready: if it is near enough to play, start downloading it. */
    private fun prefetchIfNear(key: String, track: Track) {
        val p = player ?: return
        val index = keys.indexOf(key)
        val at = p.currentMediaItemIndex
        if (index <= at || index > at + AHEAD_PREFETCH) return
        startPrefetch(key, track)
    }

    /**
     * Downloads a whole song into the Media3 cache before its turn: skipping to it is instant,
     * and it plays through a dead spot on the road. Needs the link a look-up already found (the
     * look-up calls this again once it has one), honours the Wi-Fi-only and free-space settings,
     * and carries on from whatever part is already cached.
     */
    private fun startPrefetch(key: String, track: Track) {
        if (deferred.containsKey(key) || key in prefetched) return
        val link = links[key]?.takeIf { it.fresh() } ?: return
        val cache = cache(context) ?: return
        if (!prefetching.add(key)) return
        prefetchPool.execute {
            try {
                val settings = dev.alamo3.cabineq.Settings(context)
                if (settings.cacheWifiOnly && !MediaCache.onUnmeteredNetwork(context)) return@execute
                if (context.cacheDir.usableSpace < MIN_FREE_BYTES) return@execute
                // The song playing now comes first: let it build a cushion before the next one
                // takes a share of the connection (on a slow link the two otherwise fight, and
                // the one you are listening to loses).
                val giveUp = SystemClock.elapsedRealtime() + HEADROOM_WAIT_MS
                while (bufferedAheadMs < HEADROOM_MS && SystemClock.elapsedRealtime() < giveUp) {
                    if (!tracksByKey.containsKey(key)) return@execute       // left the queue meanwhile
                    Thread.sleep(300)
                }
                if (!tracksByKey.containsKey(key)) return@execute
                val source = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(http)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
                // The same key the player reads under (see [item]), so what this stores is what it plays.
                val spec = DataSpec.Builder().setUri(Uri.parse(link.url)).setKey("song:" + track.mediaId)
                    .setHttpRequestHeaders(link.headers).build()
                val writer = CacheWriter(source, spec, null, null)
                writers[key] = writer
                val started = SystemClock.elapsedRealtime()
                writer.cache()
                prefetched.add(key)
                DLog.i(TAG, "cached \"${track.title}\" ahead of its turn (${SystemClock.elapsedRealtime() - started} ms)")
            } catch (e: InterruptedIOException) {
                // Cancelled: the song left the queue, or the queue was replaced.
            } catch (e: Exception) {
                DLog.i(TAG, "could not cache \"${track.title}\" ahead: ${e.javaClass.simpleName}: ${e.message}")
                // A refused link must not be handed out again by the next look-ahead.
                if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in REFUSED_LINK_CODES) {
                    links.remove(key)
                    TrackResolver.forget(track)
                }
            } finally {
                writers.remove(key)
                prefetching.remove(key)
            }
        }
    }

    private fun cancelPrefetch(key: String) {
        writers.remove(key)?.cancel()
        prefetched.remove(key)
    }

    private fun cancelAllPrefetch() {
        for (writer in writers.values) writer.cancel()
        writers.clear()
        prefetched.clear()
    }

    private fun recheckDeferred() {
        val now = SystemClock.elapsedRealtime()
        for ((key, since) in deferred) {
            val track = tracksByKey[key]
            if (track == null || now - since > DEFERRED_MAX_AGE_MS) {
                deferred.remove(key)
                publishWaiting()
                continue
            }
            probe(key, track, bringBack = true)
        }
    }

    private fun probe(key: String, track: Track, bringBack: Boolean) {
        val fresh = links[key]?.fresh() == true
        if (fresh && !bringBack) return
        // Every ask can start a TorBox download: while all its slots are busy, asking again only
        // keeps them busy. Wait it out (the song that is playing still asks; see resolve).
        if (SystemClock.elapsedRealtime() < torboxBusyUntil) return
        if (!checking.add(key)) return
        checkPool.execute {
            try {
                val resolved = TrackResolver.resolve(context, track, { }, { false }, 20_000L, waitIfPending = false)
                links[key] = Link(resolved.url, resolved.quality, SystemClock.elapsedRealtime(), resolved.headers, resolved.via)
                resolved.quality?.let { qualities[key] = it }
                if (bringBack) main.post { bringForward(key, track) } else main.post { prefetchIfNear(key, track) }
            } catch (e: AddonNotReady) {
                if (!bringBack) main.post { setAside(key, track) }
            } catch (e: Exception) {
                // Not found, or the network: left for its turn, where it fails and is skipped.
                val why = SourceTrouble.of(e)
                DLog.i(TAG, "look-ahead for \"${track.title}\": ${why.describe()} (${e.javaClass.simpleName}: ${e.message})")
                when (why) {
                    SourceTrouble.TORBOX_FULL -> torboxBusyUntil = SystemClock.elapsedRealtime() + TORBOX_BUSY_MS
                    // Nebula has nothing to play for it: waiting will not change that. Stop
                    // asking; when its turn comes it is tried once more and skipped with a reason.
                    SourceTrouble.NO_SOURCE -> if (deferred.remove(key) != null) {
                        DLog.i(TAG, "\"${track.title}\": no source on Nebula, no longer waiting for it")
                        main.post { publishWaiting() }
                    }
                    else -> Unit
                }
            } finally {
                checking.remove(key)
            }
        }
    }

    /** Tells the queue lists which entries are waiting on the source, so they can say why. */
    private fun publishWaiting() = bridge.waiting(deferred.keys.toHashSet())

    private fun setAside(key: String, track: Track) {
        val p = player ?: return
        val index = keys.indexOf(key)
        if (index <= p.currentMediaItemIndex) return          // its turn already came
        if (deferred.put(key, SystemClock.elapsedRealtime()) == null) {
            DLog.i(TAG, "\"${track.title}\" is still caching on TorBox: playing others first, will come back to it")
        }
        publishWaiting()
        if (index < keys.lastIndex) {
            // To the end of the queue, through the player's own queue editing so the lists the
            // app shows move with it. The deferred re-check brings it forward the moment it is ready.
            owner.moveUpcoming(key, keys.lastIndex)
        } else if (bridge.radioOn) {
            bridge.askRadio()        // it was the last one: get something to play before it
        }
    }

    private fun bringForward(key: String, track: Track) {
        val p = player ?: return
        if (deferred.remove(key) == null) return
        publishWaiting()
        val index = keys.indexOf(key)
        val next = p.currentMediaItemIndex + 1
        if (index < 0) return
        DLog.i(TAG, "\"${track.title}\" is ready now: playing it next")
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
        /** Quick asks for the next song at the end of the queue, before slowing down. */
        private const val RADIO_ASKS = 4
        private const val ASK_EVERY_MS = 3_000L
        private const val ASK_SLOW_MS = 10_000L
        /** How long an ended queue keeps looking for something to play before going idle. */
        private const val END_WAIT_MS = 10 * 60_000L
        private const val NETWORK_POLL_MS = 2_000L
        /** Failures in a row, while connected, before one song is given up on. */
        private const val MAX_NETWORK_RETRIES = 6
        /**
         * How long a resolved link is reused. TorBox documents 3 hours (some report less, around
         * half an hour), so ten minutes is well inside; a link refused anyway is retried fresh.
         */
        /** Responses that mean "this link is no longer good", as opposed to "this song is bad". */
        private val REFUSED_LINK_CODES = setOf(400, 401, 403, 410)
        private const val DEFERRED_RECHECK_MS = 10_000L
        private const val TORBOX_BUSY_MS = 60_000L
        private const val TORBOX_RETRY_MS = 10_000L
        private const val TORBOX_FULL_WAIT_MS = 3 * 60_000L
        private const val QUIET_WAIT_MS = 4_000L
        /** How many songs ahead get their link looked up (which is what starts TorBox caching them). */
        private const val AHEAD_PROBE = 4
        private const val AHEAD_PROBE_YOUTUBE = 10
        /** How many songs ahead are downloaded whole, ready before their turn. */
        private const val AHEAD_PREFETCH = 3
        /** How much of the next song is prepared and buffered while the current one plays. */
        private const val PRELOAD_NEXT_US = 15_000_000L
        /** The playing song needs this much buffered before the next one starts downloading... */
        private const val HEADROOM_MS = 20_000L
        /** ...or this long has passed (a short song, or one that cannot get ahead). */
        private const val HEADROOM_WAIT_MS = 45_000L
        /** Below this much free space nothing is downloaded ahead. */
        private const val MIN_FREE_BYTES = 300L * 1024 * 1024
        private const val DEFERRED_MAX_AGE_MS = 10 * 60_000L
        private const val REBUILD_RETRIES = 10

        @Volatile private var sharedCache: SimpleCache? = null

        /** True when the whole song is in the Media3 cache, so it plays with no network at all. */
        internal fun cachedWhole(context: Context, track: Track): Boolean {
            val cache = cache(context) ?: return false
            val key = "song:" + track.mediaId
            val length = cache.getContentMetadata(key).get(ContentMetadata.KEY_CONTENT_LENGTH, C.LENGTH_UNSET.toLong())
            return length > 0 && cache.isCached(key, 0, length)
        }

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
                }.onFailure { DLog.w(TAG, "no disk cache: ${it.message}") }.getOrNull()?.also { sharedCache = it }
            }
        }
    }
}
