package dev.alamo3.cabineq

import android.content.Context
import dev.alamo3.cabineq.music.GrowingDownload
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Plays audio files (local or streamed over HTTPS) straight through the native DSP:
 *
 *   MediaExtractor → MediaCodec (decode to PCM) → stereo float → C++ EQ/limiter → AudioTrack
 *
 * Unlike [CaptureEngine] there is no playback capture and no screen-capture prompt: the
 * engine sees the decoded source samples directly. The AudioTrack and the engine run at the
 * file's own sample rate (44.1/48/96 kHz…), so nothing is resampled on our side. Consecutive
 * tracks at the same rate share one AudioTrack, so albums play gaplessly.
 *
 * One instance per process ([get]); EqService pushes EQ updates into it with [setEq].
 * Controls are thread-safe and can be called from any thread.
 */
class FilePlayer private constructor(private val context: Context) {

    enum class State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    /** What happens when a track — or the queue — ends. */
    enum class Repeat { OFF, ALL, ONE }

    @Volatile var state = State.IDLE
        private set
    @Volatile var queueTitle: String? = null
        private set
    @Volatile var queue: List<Track> = emptyList()
        private set
    @Volatile var currentIndex = -1
        private set
    /** Stable per-entry ids for the queue, aligned with [queue]; used by the Jam session. */
    @Volatile private var queueKeys: List<String> = emptyList()
    /**
     * Entries moved down the queue because their source is still caching them (see
     * [dev.alamo3.cabineq.player.ExoEngine]): the lists say so, so a song that jumps to the end
     * does not look like it was lost.
     */
    @Volatile var waitingKeys: Set<String> = emptySet()
        private set
    @Volatile var positionMs = 0L
        private set
    /** When the decode loop last moved the play head; used to detect a dead output sink. */
    @Volatile var lastProgressAt = 0L
        private set
    @Volatile var durationMs = 0L
        private set
    /**
     * True once [durationMs] is the decoder's own length rather than what the catalogue
     * claimed. Lyrics are matched by length, so they wait for this.
     */
    @Volatile var durationExact = false
        private set
    /** Gain loudness matching is applying to this song, in dB; NaN when it is off. */
    @Volatile var loudnessGainDb = Float.NaN
        private set
    /** e.g. "FLAC 44.1 kHz / 16-bit · lossless" */
    @Volatile var formatInfo: String? = null
    // Same facts as [formatInfo], kept as numbers for the output-path diagnostics.
    @Volatile var sourceCodec: String? = null
    @Volatile var sourceSampleRate = 0
    @Volatile var sourceBits = 0

    /** The AudioTrack currently feeding the mixer, if any. */
    @Volatile private var liveOutput: Output? = null
        private set
    /** What the source claims about quality ("lossless"), appended to the decoder's line. */
    @Volatile private var declaredQuality: String? = null
    @Volatile var lastError: String? = null
        private set
    /** Tags and cover embedded in the current file (read in the background; null until known). */
    class FileInfo(val art: Bitmap?, val title: String?, val artist: String?, val album: String?)

    @Volatile var currentInfo: FileInfo? = null
        private set
    // Two threads: a stalled HTTP retriever must not hold up the next track's tags forever.
    private val infoPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "CabinEQ-Tags").apply { isDaemon = true } }
    private val infoBusy = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // The queue is persisted so a relaunch can pick up where it stopped (and, later, so the
    // car can browse and resume it). Kept off the player thread: this pool never touches audio.
    private val settings = Settings(context)
    private val persistPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Queue").apply { isDaemon = true }
    }
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveQueue() }

    /** Transient status from the track source, e.g. "Waiting on TorBox: caching" (cleared on play). */
    @Volatile var note: String? = null
        private set

    /** Called from [TrackResolver] while it waits on its source. */
    fun setNote(text: String?) {
        note = text
        version++
    }

    /** Bumped on every track / state change so observers (MediaSession, UI) can update cheaply. */
    @Volatile var version = 0
        private set

    /**
     * Brings back the last queue, paused at the saved position. State stays IDLE: nothing plays,
     * no notification appears, and no audio is touched until the user presses play ([resume]
     * starts the worker at [positionMs]).
     */
    private fun restoreQueue() {
        val snap = settings.queueSnapshot ?: return
        queue = snap.tracks
        queueKeys = snap.tracks.map { newKey() }
        radioSeed = settings.radioSeed      // the one-ahead refill survives a restart
        queueTitle = snap.title
        currentIndex = snap.index
        positionMs = snap.positionMs
        durationMs = snap.tracks.getOrNull(snap.index)?.durationMs ?: 0
        version++
        Log.i(TAG, "Restored ${snap.tracks.size} tracks at ${snap.index}, ${snap.positionMs} ms")
    }

    /** Advances OFF -> ALL -> ONE -> OFF (the phone's repeat button). */
    fun cycleRepeat(): Repeat {
        setRepeat(
            when (repeat) {
                Repeat.OFF -> Repeat.ALL
                Repeat.ALL -> Repeat.ONE
                Repeat.ONE -> Repeat.OFF
            }
        )
        return repeat
    }

    /** Also called by the car: its media UI sends SET_REPEAT_MODE on the session. */
    fun setRepeat(mode: Repeat) {
        if (repeat == mode) return
        repeat = mode
        exo?.repeat(mode)
        version++
        persistPool.execute { runCatching { settings.repeatMode = mode.name } }
    }

    val currentTitle: String? get() = queue.getOrNull(currentIndex)?.title
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)
    val isActive: Boolean get() = state == State.LOADING || state == State.PLAYING || state == State.PAUSED

    private val audioManager = context.getSystemService(AudioManager::class.java)

    // ------------------------------------------------------- output sample rate (device side)

    /** The rate Android mixes this route at; 0 until first read. */
    @Volatile private var deviceRate = 0

    /** Bumped whenever the output rate may have changed, so the worker rebuilds its sink. */
    @Volatile var routeEpoch = 0
        private set

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDeviceRate()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshDeviceRate()
    }

    private fun refreshDeviceRate() {
        val r = runCatching {
            audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        }.getOrNull() ?: return
        if (r > 0 && r != deviceRate) {
            deviceRate = r
            routeEpoch++
            if (exoActive) exo?.routeChanged()
            runCatching { refreshRouteDependentParams() }
            Log.i(TAG, "device mixes at $r Hz")
            runCatching { CarVolume.sync(context) }
        }
    }

    /**
     * ENTER/EXIT_CAR_MODE are implicit broadcasts: a manifest receiver never sees them (API 26+),
     * so register at runtime — the player is alive exactly when the volume matters. Once per
     * player: this used to sit in [refreshDeviceRate] and added a receiver per route change.
     */
    private fun registerCarModeReceiver() {
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(android.app.UiModeManager.ACTION_ENTER_CAR_MODE)
                addAction(android.app.UiModeManager.ACTION_EXIT_CAR_MODE)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: android.content.Intent) {
                    Log.i(TAG, "car mode: ${intent.action}")
                    runCatching { CarVolume.sync(c) }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }
    }

    /** Rate we emit at: the device's, so the mixer has nothing to resample. */
    private fun targetRate(sourceRate: Int): Int {
        if (!settings.matchDeviceRate || sourceRate <= 0) return sourceRate
        if (deviceRate == 0) refreshDeviceRate()
        return if (deviceRate > 0) deviceRate else sourceRate
    }

    /** Called when the user flips "Match device rate": the worker rebuilds at the new rate. */
    fun onRateSettingChanged() {
        refreshDeviceRate()
        routeEpoch++
        if (exoActive) exo?.routeChanged()
    }
    private var thread: Thread? = null

    @Volatile private var generation = 0          // incremented to retire the current worker
    @Volatile private var jumpTo = -1             // pending skip target
    @Volatile private var seekToMs = -1L          // pending seek within the current track
    /** True when the pause came from the user (button/car), not from focus or a stall. */
    @Volatile var userPaused = false
        private set
    @Volatile var repeat = Repeat.OFF
        private set

    init {
        repeat = runCatching { Repeat.valueOf(settings.repeatMode) }.getOrDefault(Repeat.OFF)
        restoreQueue()
        // The mix rate follows the route (speaker 48k, a USB DAC maybe 96k, BT often 44.1),
        // so the worker has to be told when it moves rather than guessing once at startup.
        runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, null) }
        refreshDeviceRate()
        registerCarModeReceiver()
        // Car mode may already be on (Android Auto launched us): raise the stream now, and
        // again whenever the route moves, since "external output in a car" is the case that
        // should get full scale.
        runCatching { CarVolume.sync(context) }
    }
    @Volatile private var focusPaused = false
    /** Artist-radio seed: one more track is queued when this one becomes the last entry. */
    /** Set by the stall watchdog: the worker should recreate its AudioTrack and continue. */
    @Volatile private var rebuildOutput = false
    /** A "next" pressed at the end of the queue: jump to the track the radio is fetching. */
    @Volatile private var followNewTrack = false
    private var lastStallRebuildAt = 0L

    @Volatile private var radioSeed: Track? = null
    @Volatile private var radioBusy = false
    private val radioPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CabinEQ-Radio").apply { isDaemon = true }
    }

    // EQ state and the active output's native engine, guarded by [eqLock].
    private val eqLock = Any()
    private var eqBands: FloatArray? = null
    private var eqPreampDb = 0f
    private var eqLimiterLin = 0.7943f   // -2 dBFS, see EqService.LIMITER_THRESHOLD_LIN
    private var eqVersion = 0
    @Volatile private var current: Output? = null
    private val meterScratch = FloatArray(5)

    /**
     * Designs the whole chain for [rate] (user EQ, cabin correction, limiter, Bluetooth guard)
     * and pushes it into the native engine at [handle]. One implementation for both players, so
     * the engine is configured identically whichever one feeds it. Call under [eqLock].
     */
    private fun pushEqLocked(handle: Long, rate: Int, bluetooth: Boolean): Boolean {
        val bands = eqBands ?: return false
        if (handle == 0L) return false
        val user = EqDesign.designAll(bands, rate.toDouble())
        // Cabin correction rides on the same engine, as extra biquads after the user's bands.
        val cabin = cabinCurve()
        val cabinCoeffs = if (cabin == null) DoubleArray(0) else cabin.coeffs(rate.toDouble(), settings.cabinAmount)
        // Subsonic filter first: what the car cannot reproduce must not be boosted, and must not
        // reach the limiter either — that headroom is what the audible bass needs.
        val subsonic = if (settings.subsonicFilter) EqDesign.highPass(rate.toDouble()) else DoubleArray(0)
        val coeffs = subsonic + user + cabinCoeffs
        val bandCount = subsonic.size / 5 + bands.size + (cabin?.bands?.size ?: 0)
        val preamp = eqPreampDb + (cabin?.preampFor(settings.cabinAmount) ?: 0.0).toFloat()
        val guard = if (settings.bluetoothHfGuard && bluetooth) EqDesign.lowPass(rate.toDouble(), 15500.0) else null
        NativeDsp.setParams(
            handle, coeffs, bandCount, EqService.dbToLin(preamp), eqLimiterLin,
            guard, if (guard == null) 0 else 2,
        )
        return true
    }

    /** The active cabin correction, unless it is off or bypassed. */
    private fun cabinCurve(): dev.alamo3.cabineq.cabin.CabinCorrection.Curve? {
        if (settings.cabinBypass || settings.cabinAmount <= 0f) return null
        val name = settings.cabinActive
        if (name.isBlank()) return null
        return dev.alamo3.cabineq.cabin.CabinCorrection.Curve.fromJson(settings.cabinCurve(name))
    }

    // ---------------------------------------------------------------- the Media3 engine

    /** The second player (Settings → Playback → Player engine). Created on first use. */
    @Volatile private var exo: dev.alamo3.cabineq.player.ExoEngine? = null
    /** True while the Media3 engine, not the worker below, is the one playing the queue. */
    @Volatile private var exoActive = false

    /**
     * Builds the Media3 engine before the first tap when it is the one in use: opening its disk
     * cache and creating the player took a few hundred ms of the first song's start.
     */
    private fun warmUp() {
        if (settings.media3Player) exoEngine().warmUp()
    }

    /** What the decoder feeds the Media3 sink, for the status report; null on the built-in engine. */
    val dspInputDescription: String? get() = exo?.inputDescription

    private fun exoEngine(): dev.alamo3.cabineq.player.ExoEngine =
        exo ?: synchronized(this) {
            exo ?: dev.alamo3.cabineq.player.ExoEngine(this, Bridge()).also { exo = it }
        }

    /**
     * Everything the Media3 engine may do to this player's published state. The engine runs on
     * one thread (the main looper) and reports through here; nothing else of the player is open
     * to it.
     */
    internal inner class Bridge {
        val context: Context get() = this@FilePlayer.context
        val settings: Settings get() = this@FilePlayer.settings
        val dspLock: Any get() = eqLock
        val eqVersion: Int get() = this@FilePlayer.eqVersion
        val radioOn: Boolean get() = radioSeed != null && repeat == Repeat.OFF
        val radioLooking: Boolean get() = radioBusy

        fun state(s: State, reason: String? = null) = setState(s, reason)
        fun index(i: Int) {
            currentIndex = i
            version++
        }
        fun position(ms: Long) {
            positionMs = ms
            lastProgressAt = SystemClock.elapsedRealtime()
        }
        fun duration(ms: Long, exact: Boolean) {
            durationMs = ms
            durationExact = exact
        }
        fun error(text: String?) {
            lastError = text
        }
        fun info(info: FileInfo?) {
            currentInfo = info
            version++
        }
        fun quality(q: String?) {
            declaredQuality = q
        }
        fun format(mime: String, rate: Int, channels: Int) = updateFormatInfo(mime, rate, 0, channels)
        fun clearFormat() {
            formatInfo = null
            declaredQuality = null
        }
        fun snapshot(): Pair<List<Track>, List<String>> = synchronized(eqLock) { queue to queueKeys }
        fun trackStarted() {
            extendRadioIfNeeded()
            scheduleSave()
        }
        fun askRadio() = extendRadioIfNeeded(force = true)
        fun waiting(keys: Set<String>) {
            if (keys == waitingKeys) return
            waitingKeys = keys
            version++
        }
        fun targetRate(sourceRate: Int): Int = this@FilePlayer.targetRate(sourceRate)
        fun pushEq(handle: Long, rate: Int, bluetooth: Boolean): Boolean = pushEqLocked(handle, rate, bluetooth)
        fun seedEq() = seedEqFromSettings()
        fun save() = saveQueue()
    }

    // ---------------------------------------------------------------- controls

    fun play(
        tracks: List<Track>,
        startIndex: Int = 0,
        title: String? = null,
        startAtMs: Long = 0,
        /** When set, the queue is topped up one track at a time from this seed's artist. */
        radio: Track? = null,
    ) {
        if (tracks.isEmpty()) return
        // Playing something else while a track was underway is a skip (but a resume from IDLE is
        // not: state IDLE/ERROR never reaches this branch).
        val wasPlaying = currentTrack
        val restarting = wasPlaying?.mediaId == tracks[startIndex.coerceIn(0, tracks.lastIndex)].mediaId
        if (wasPlaying != null && !restarting &&
            (state == State.PLAYING || state == State.PAUSED || state == State.LOADING)
        ) {
            Log.i(TAG, "play() replaces ${wasPlaying.title} at ${positionMs}ms (state=$state) — counted as a skip")
            Taste.onSkip(context, wasPlaying, positionMs, durationMs)
        }
        stopWorker()
        seedEqFromSettings()
        synchronized(eqLock) {
            if (tracks !== queue || queueKeys.size != tracks.size) {
                queue = tracks
                queueKeys = tracks.map { newKey() }
            }
            // else: resuming the queue we already have (e.g. after it ran out) — keep the entry
            // keys, or every guest's "up next" would refer to a track that no longer exists.
        }
        queueTitle = title
        // Whatever is played, the music carries on after its last song: an album or playlist ends
        // into a radio seeded from its last track (when the source knows it), never into silence.
        val seed = radio ?: tracks.lastOrNull()?.takeIf { it.sourceId != null }
        radioSeed = seed
        persistPool.execute { runCatching { settings.radioSeed = seed } }
        lastError = null
        userPaused = false
        focusPaused = false
        interrupted = false
        jumpTo = -1
        seekToMs = -1            // a seek pending from the previous track must not apply here
        rebuildOutput = false
        followNewTrack = false
        durationMs = tracks[startIndex.coerceIn(0, tracks.lastIndex)].durationMs
        positionMs = startAtMs.coerceAtLeast(0)
        // Leave PAUSED behind: the worker stops at the top of its loop while paused, so a track
        // picked while the player was paused (focus loss, speaker guard, prior pause) would never
        // even open — it looked like "tap a song, nothing happens".
        if (state != State.LOADING) setState(State.LOADING)
        if (settings.media3Player) {
            // The Media3 engine takes it from here: it owns fetching, buffering, decoding and
            // the hand-over between songs. The worker stays retired (stopWorker above).
            // Show the tapped song at once: the engine reports the index itself, but only after it
            // has started, and until then the lists, the notification and the car would still show
            // the previous song while this one loads.
            currentIndex = startIndex.coerceIn(0, tracks.lastIndex)
            currentInfo = null
            note = null
            version++
            exoActive = true
            exoEngine().start(startIndex.coerceIn(0, tracks.lastIndex), startAtMs.coerceAtLeast(0))
            return
        }
        if (exoActive) {
            exoActive = false
            exo?.shutdown()
        }
        val gen = ++generation
        val worker = Worker(gen, startIndex.coerceIn(0, tracks.lastIndex), startAtMs.coerceAtLeast(0))
        val begin = {
            if (gen != generation) Unit
            else {
                Log.i(TAG, "play: ${tracks.size} tracks from $startIndex at ${startAtMs}ms, title=$title")
                thread = Thread(worker, "CabinEQ-Player").apply { start() }
            }
        }
        if (requestFocus()) {
            begin()
        } else {
            // A momentary refusal (the car host or a nav prompt shuffling focus) must not turn a
            // tap into "nothing happens": retry briefly, then start when focus is granted.
            Log.w(TAG, "play: focus refused, retrying (${tracks.size} tracks, index=$startIndex)")
            requestFocusForUser { begin() }
        }
    }

    fun togglePause() {
        if (state == State.PAUSED || state == State.IDLE || state == State.ERROR) resume() else pause()
    }

    fun pause() {
        if (!isActive) return
        if (exoActive) {
            userPaused = true
            exo?.pause()
            return
        }
        userPaused = true
        interrupted = false
        setState(State.PAUSED, "paused by user")
        // No reason to hold GAIN focus while nothing is playing; resume() asks again.
        abandonFocus()
    }

    fun resume() {
        if (state == State.IDLE || state == State.ERROR) {
            // Includes a queue restored from the last session: start where it left off.
            if (queue.isNotEmpty()) {
                play(queue, max(0, currentIndex), queueTitle, startAtMs = positionMs, radio = radioSeed)
            }
            return
        }
        if (exoActive) {
            userPaused = false
            exo?.resume()
            return
        }
        userPaused = false
        focusPaused = false
        requestFocusForUser {
            Log.i(TAG, "resume: focus granted")
            if (state == State.PAUSED) setState(State.PLAYING, "resume")
        }
    }

    fun next() {
        if (queue.isEmpty()) return
        if (currentIndex < queue.lastIndex) {
            skipTo(currentIndex + 1)
            return
        }
        when (repeat) {
            Repeat.ALL -> { skipTo(0); return }
            Repeat.ONE -> { skipTo(currentIndex); return }
            Repeat.OFF -> Unit
        }
        // Last track and no loop: don't tear the session down. Ask the radio for one more and
        // follow it when it lands; without a seed just pause, keeping the queue and the session.
        if (radioSeed != null) {
            followNewTrack = true
            Log.i(TAG, "next at the end of the queue: asking the radio for one more")
            extendRadioIfNeeded(force = true)
        } else {
            pause()
        }
    }

    /** Restarts the current track if more than 3 s in, otherwise goes to the previous one. */
    fun previous() {
        if (queue.isEmpty()) return
        skipTo(if (positionMs > 3000 || currentIndex <= 0) max(0, currentIndex) else currentIndex - 1)
    }

    fun skipTo(index: Int) {
        if (index !in queue.indices) return
        val engine = exo
        // A restored session can be marked active before its engine exists: without the null
        // check, "next" right after launch found nothing to tell and did nothing at all.
        if (exoActive && isActive && engine != null) {
            userPaused = false
            engine.skipTo(index)
            return
        }
        if (thread?.isAlive == true) {
            userPaused = false
            jumpTo = index
            if (state == State.PAUSED) setState(State.LOADING)
            // The worker only looks at jumpTo between steps. While it sits inside a network open
            // or read (the extractor has no way to be interrupted), nothing looks, and the tap
            // seems dead for as long as that connection takes. If the request is still pending
            // shortly after, retire that worker and start a fresh one on the wanted track; the
            // old one finishes on its own and releases what it holds.
            if (state == State.LOADING) {
                val genAtTap = generation
                saveHandler.postDelayed({
                    if (genAtTap == generation && jumpTo == index && state == State.LOADING &&
                        index in queue.indices
                    ) {
                        Log.w(TAG, "skip to $index not picked up in ${SKIP_PICKUP_MS}ms (worker busy opening a stream): starting a fresh worker")
                        play(queue, index, queueTitle, radio = radioSeed)
                    }
                }, SKIP_PICKUP_MS)
            }
        } else {
            play(queue, index, queueTitle, radio = radioSeed)
        }
    }

    /** Seeks within the current track (to the nearest sync point at or before [ms]). */
    fun seekTo(ms: Long) {
        if (!isActive) return
        val target = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
        if (exoActive) {
            positionMs = target
            version++
            exo?.seekTo(target)
            scheduleSave()
            return
        }
        seekToMs = target
        positionMs = target
        version++
        scheduleSave()
    }

    // ---------------------------------------------------------------- queue editing

    /** A queue entry with its stable key (the Jam session addresses entries by key). */
    class QueuedTrack(val key: String, val track: Track, val waiting: Boolean = false)

    fun queuedTracks(): List<QueuedTrack> = synchronized(eqLock) {
        val q = queue
        val keys = queueKeys
        val waiting = waitingKeys
        q.mapIndexed { i, t ->
            val key = keys.getOrElse(i) { "" }
            QueuedTrack(key, t, key in waiting)
        }
    }

    private fun newKey(): String =
        "k" + java.lang.Long.toHexString(System.nanoTime() and 0xFFFFFFFFL) +
            java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000).toString(16)

    /**
     * Appends to the end of the queue. Only the tail changes, so a running worker — which reads
     * [queue] per track — never sees an index shift.
     */
    fun enqueue(tracks: List<Track>): List<String> {
        if (tracks.isEmpty()) return emptyList()
        val keys = tracks.map { newKey() }
        synchronized(eqLock) {
            queue = queue + tracks
            queueKeys = queueKeys + keys
            // Mirrored in the same order the lists change, so the two never disagree.
            if (exoActive) exo?.added(tracks, keys)
        }
        version++
        saveQueue()
        // A guest or the radio just queued the immediate next track: start caching it now.
        // (The Media3 engine buffers ahead through its own cache.)
        if (!exoActive && queue.size - tracks.size == currentIndex + 1) {
            MediaCache.prefetch(context, tracks.first())
        }
        return keys
    }

    /** Queues [track] right after the one that is playing. */
    fun playNext(track: Track) {
        val key = enqueue(listOf(track)).firstOrNull() ?: return
        moveUpcoming(key, currentIndex + 1)
    }

    /** True while the radio tops the queue up by itself. */
    val radioActive: Boolean get() = radioSeed != null

    /** Removes an upcoming entry. Refuses to touch the current or already-played entries. */
    fun removeUpcoming(key: String): Boolean {
        synchronized(eqLock) {
            val i = queueKeys.indexOf(key)
            if (i <= currentIndex) return false
            queue = queue.toMutableList().also { it.removeAt(i) }
            queueKeys = queueKeys.toMutableList().also { it.removeAt(i) }
            if (exoActive) exo?.removed(i)
        }
        version++
        saveQueue()
        return true
    }

    /** Moves an upcoming entry to [to] (an index in the queue, never at or before current). */
    fun moveUpcoming(key: String, to: Int): Boolean {
        synchronized(eqLock) {
            val from = queueKeys.indexOf(key)
            if (from <= currentIndex) return false
            val target = to.coerceIn(currentIndex + 1, queue.lastIndex)
            if (from == target) return true
            val q = queue.toMutableList()
            val k = queueKeys.toMutableList()
            val track = q.removeAt(from)
            val keyAt = k.removeAt(from)
            q.add(target, track)
            k.add(target, keyAt)
            queue = q
            queueKeys = k
            if (exoActive) exo?.moved(from, target)
        }
        version++
        saveQueue()
        return true
    }

    /**
     * Keeps exactly one track ahead: when the song that just started is the last one queued,
     * fetch the artist's next track in the background and append it. Never blocks playback.
     */
    private fun extendRadioIfNeeded(force: Boolean = false) {
        val seed = radioSeed ?: return
        if (radioBusy) return
        if (repeat != Repeat.OFF) return                   // looping is the user's chosen queue
        // Keep two songs queued ahead, not one: a single slow lookup then no longer decides
        // whether the music carries on, and the song after next can be fetched early too.
        if (!force && currentIndex < queue.lastIndex - 1) return
        if (!force && state != State.PLAYING && state != State.LOADING) return
        radioBusy = true
        val gen = generation
        radioPool.execute {
            val queued = runCatching { queuedTracks() }.getOrDefault(emptyList())
            val exclude = queued.flatMap { Radio.identity(it.track) }.toHashSet()
            val session = queued.takeLast(5).mapNotNull { it.track.artist?.trim()?.lowercase() }.toSet()
            val currentArtist = queued.getOrNull(currentIndex)?.track?.artist
            val next = runCatching { Radio.next(context, seed, exclude, session, currentArtist) }.getOrNull()
                ?: localFallback(exclude, queued.getOrNull(currentIndex)?.track)
            radioBusy = false
            if (next == null || gen != generation || radioSeed !== seed) {
                followNewTrack = false
                return@execute
            }
            Log.i(TAG, "Radio: queuing \"${next.title}\" — ${next.artist}")
            enqueue(listOf(next))
            if (!exoActive) MediaCache.prefetch(context, next)
            if (followNewTrack) {
                // The user pressed "next" at the end of the queue: jump to what just arrived.
                followNewTrack = false
                skipTo(queue.lastIndex)
            } else if (state == State.IDLE) {
                // Playback ran dry while we were fetching: pick the new track up.
                skipTo(queue.lastIndex)
            }
            extendRadioIfNeeded()   // top up to two ahead
        }
    }

    /**
     * The radio found nothing (offline, or its pool is used up): something of the listener's own
     * instead, so the music never just stops. Songs not queued yet first; if all are, any other.
     */
    private fun localFallback(exclude: Set<String>, current: Track?): Track? {
        val pool = runCatching {
            (Library.liked(context) + MediaBrowse.recentTracks(context)).distinctBy { it.mediaId }
        }.getOrDefault(emptyList())
        val fresh = pool.filter { t -> Radio.identity(t).none { it in exclude } }
        val others = pool.filter { it.mediaId != current?.mediaId }
        val pick = if (MediaCache.isOnline(context)) {
            fresh.ifEmpty { others }
        } else {
            // No connection: only what is on the phone can play, so choose from that first.
            val onPhone = others.filter { MediaCache.fileFor(context, it) != null || dev.alamo3.cabineq.player.ExoEngine.cachedWhole(context, it) }
            val freshIds = fresh.map { it.mediaId }.toSet()
            onPhone.filter { it.mediaId in freshIds }.ifEmpty { onPhone }.ifEmpty { fresh.ifEmpty { others } }
        }.randomOrNull()
        if (pick != null) Log.i(TAG, "Radio: nothing new, falling back to \"${pick.title}\" from your library")
        return pick
    }

    fun stop() {
        followNewTrack = false
        radioSeed = null
        persistPool.execute { runCatching { settings.radioSeed = null } }
        if (exoActive) {
            exoActive = false
            exo?.shutdown()
        }
        stopWorker()
        abandonFocus()
        positionMs = 0
        setState(State.IDLE)
        saveQueue()   // a stop may be the last thing before the process goes away
    }

    /**
     * A dead output sink (route changed, the car bus stopped consuming, a HAL bug) blocks
     * AudioTrack.write forever, and that looks exactly like "it just doesn't play". Detect it,
     * unblock the worker and pause with a message the user can act on.
     */
    fun recoverStalledOutput(): Boolean {
        if (exoActive) return false          // Media3 watches its own output
        if (state != State.PLAYING) return false
        val last = lastProgressAt
        val now = SystemClock.elapsedRealtime()
        if (last == 0L || now - last < STALL_MS) return false
        Log.w(TAG, "No progress for ${now - last} ms; output sink looks dead")
        // Flushing a blocked write makes it return so the worker can react at all.
        current?.let { runCatching { it.track.pause(); it.track.flush() } }
        lastProgressAt = now
        if (now - lastStallRebuildAt > STALL_REBUILD_WINDOW_MS) {
            // First stall in a while: rebuild the AudioTrack and keep playing from here. The sink
            // (not the decoder) is what died, so nothing else has to be thrown away.
            lastStallRebuildAt = now
            rebuildOutput = true
            version++
            return false
        }
        // Not the user's pause: a later play press (or a recovered route) may continue.
        if (isActive) {
            setState(State.PAUSED, "output stalled")
            abandonFocus()
        }
        version++
        return true
    }

    /** Called by EqService at 10 Hz with the smoothed EQ (user EQ + speed compensation). */
    fun setEq(bands: FloatArray, preampDb: Float, limiterThresholdLin: Float) {
        synchronized(eqLock) {
            val old = eqBands
            var changed = old == null || old.size != bands.size ||
                abs(preampDb - eqPreampDb) > 0.01f || limiterThresholdLin != eqLimiterLin
            if (!changed && old != null) {
                for (i in bands.indices) if (abs(bands[i] - old[i]) > 0.01f) { changed = true; break }
            }
            if (!changed) return
            eqBands = bands.copyOf()
            eqPreampDb = preampDb
            eqLimiterLin = limiterThresholdLin
            eqVersion++
            current?.applyEqLocked()
            if (exoActive) exo?.sink?.applyEq()
        }
    }

    /** [inPeak, outPeak, minLimiterGain] since the last call, or null if not playing. */
    fun readMeters(): FloatArray? {
        synchronized(eqLock) {
            val h = if (exoActive) exo?.sink?.handleOrZero ?: 0L else current?.handle ?: 0L
            if (h == 0L || state != State.PLAYING) return null
            NativeDsp.readMeters(h, meterScratch)
            return meterScratch.copyOf()
        }
    }

    fun routedDeviceType(): Int? = if (state == State.PLAYING) current?.track?.routedDevice?.type else null

    // ---------------------------------------------------------------- audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "focus change $change (state=${state.name}, userPaused=$userPaused)")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app took over. Not a user pause: in Android Auto the projection host
                // grabs focus around the media panel, so when focus comes back we resume by
                // ourselves. Never grab it back on a timer — that would fight a real player.
                interrupted = state == State.PLAYING || state == State.LOADING
                if (isActive) setState(State.PAUSED, "audio focus lost")
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Phone call etc.: pause, resume when it ends.
                focusPaused = true
                if (state == State.PLAYING || state == State.LOADING) {
                    setState(State.PAUSED, "audio focus transient loss")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val resume = interrupted && !userPaused && state == State.PAUSED
                interrupted = false
                focusPaused = false
                if (resume) setState(State.PLAYING, "audio focus back")
            }
            // LOSS_TRANSIENT_CAN_DUCK (navigation prompts): the system ducks us automatically.
        }
    }

    /**
     * The user (or the car's transport controls) asked for playback: if focus is momentarily
     * refused, try again a few times instead of failing immediately. Bounded, so a genuine
     * takeover by another app still wins.
     */
    private fun requestFocusForUser(then: () -> Unit) {
        val genAtRequest = generation
        if (requestFocus()) {
            then()
            return
        }
        var tries = 0
        fun attempt() {
            // The user may have paused (or started something else) while we were waiting: never
            // resurrect playback they cancelled.
            if (userPaused || generation != genAtRequest) return
            if (tries >= FOCUS_RETRIES) {
                Log.w(TAG, "focus refused ${tries + 1} times, giving up")
                lastError = "Another app holds audio focus"
                setState(State.ERROR)
                return
            }
            tries++
            focusHandler.postDelayed({
                if (userPaused || generation != genAtRequest) return@postDelayed
                if (requestFocus()) {
                    Log.i(TAG, "focus granted on retry $tries")
                    then()
                } else {
                    attempt()
                }
            }, FOCUS_RETRY_MS)
        }
        attempt()
    }

    private val focusHandler = Handler(Looper.getMainLooper())
    @Volatile private var interrupted = false

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRIBUTES)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        val ok = runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }.getOrDefault(false)
        Log.i(TAG, "abandonFocus -> $ok (state=${state.name})")
    }

    // ---------------------------------------------------------------- output

    /** One AudioTrack + one native engine at a fixed sample rate. Owned by a single worker. */
    private inner class Output(val rate: Int) {
        val track: AudioTrack
        var handle = 0L          // guarded by eqLock
        private var appliedEq = -1
        /** Frames written since creation or the last flush; compared with the play head to drain. */
        var framesWritten = 0L

        /** The processed (possibly resampled) block; [processedFrames] of them are valid. */
        var outBuf = FloatArray(0)
            private set
        var processedFrames = 0
            private set
        /** Rate the decoder produces; the engine resamples when it differs from [rate]. */
        private var inputRate = 0

        init {
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            // ~200 ms: we're streaming over mobile data, latency doesn't matter here.
            track = AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, rate / 5 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            synchronized(eqLock) {
                handle = NativeDsp.create(rate)
                applyEqLocked()
                // Never start mid-waveform at full amplitude: that step is a click.
                NativeDsp.fadeIn(handle, rate * FADE_IN_MS / 1000)
            }
            liveOutput = this
        }

        /** Rate of the track we feed: the file's rate, i.e. what the mixer has to re-sample. */
        fun trackRate(): Int? = runCatching { track.sampleRate }.getOrNull()

        fun routed(): AudioDeviceInfo? = runCatching { track.routedDevice }.getOrNull()

        fun applyEqLocked() {
            if (handle == 0L || appliedEq == eqVersion) return
            val type = runCatching { track.routedDevice?.type }.getOrNull()
            val bt = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (pushEqLocked(handle, rate, bt)) appliedEq = eqVersion
        }

        /** Re-evaluates the guard after a route change, without rebuilding the sink. */
        fun refreshRouteDependentParams() {
            synchronized(eqLock) {
                if (handle == 0L) return
                appliedEq = -1        // force the next push
                applyEqLocked()
            }
        }

        /** Tells the engine what the decoder is producing, so it can resample to [rate]. */
        fun setInputRate(r: Int) {
            if (r <= 0) return
            synchronized(eqLock) {
                if (r == inputRate) return
                inputRate = r
                if (handle != 0L) NativeDsp.setInputRate(handle, r)
            }
            Log.i(TAG, if (r == rate) "no resampling: source is $r Hz" else "resampling ${r} -> $rate Hz")
        }

        /** Runs the whole chain (resample → EQ → limiter) into [outBuf]. */
        fun process(buf: FloatArray, frames: Int) {
            val cap = synchronized(eqLock) {
                if (handle == 0L) frames else NativeDsp.maxFrames(handle, frames)
            }
            if (outBuf.size < cap * 2) outBuf = FloatArray(cap * 2)
            processedFrames = synchronized(eqLock) {
                if (handle == 0L) 0 else NativeDsp.process(handle, buf, frames, outBuf, cap)
            }
        }

        /** End of a track: the resampler's look-ahead would otherwise be dropped (a click). */
        fun flush() {
            val cap = synchronized(eqLock) { if (handle == 0L) 0 else NativeDsp.maxFrames(handle, 1024) }
            if (cap <= 0) {
                processedFrames = 0
                return
            }
            if (outBuf.size < cap * 2) outBuf = FloatArray(cap * 2)
            processedFrames = synchronized(eqLock) {
                if (handle == 0L) 0 else NativeDsp.flush(handle, outBuf, cap)
            }
        }

        fun pauseAndFlush() {
            runCatching { track.pause(); track.flush() }
            framesWritten = 0
            // Whatever plays next starts from silence, not from the middle of a waveform.
            synchronized(eqLock) {
                if (handle != 0L) NativeDsp.fadeIn(handle, rate * FADE_IN_MS / 1000)
            }
        }

        fun release() {
            if (liveOutput === this) liveOutput = null
            runCatching { track.pause(); track.flush(); track.release() }
            synchronized(eqLock) {
                if (current === this) current = null
                val h = handle
                handle = 0L
                if (h != 0L) NativeDsp.destroy(h)
            }
        }
    }

    // ---------------------------------------------------------------- worker

    private sealed class Outcome {
        object Finished : Outcome()
        object Cancelled : Outcome()
        class Jump(val index: Int) : Outcome()
        class Failed(val message: String) : Outcome()
        /** The source is still caching this song: play on, and come back when it is ready. */
        class NotReady(val message: String) : Outcome()
    }

    private inner class Worker(private val gen: Int, private val start: Int, private var startAtMs: Long) : Runnable {
        private var output: Output? = null
        private var builtEpoch = -1
        private var stereo = FloatArray(0)
        private var lastSavedAt = 0L
        /** Loudness matching for the song being decoded (null when switched off). */
        private var leveler: dev.alamo3.cabineq.music.Leveler? = null
        /** The last song's album and gain: consecutive songs of one album share a level. */
        private var lastAlbum: String? = null
        private var lastGainDb: Double? = null

        private val live: Boolean get() = gen == generation

        /** True once audio for the current track actually reached the speaker. */
        private var startedAny = false
        private var beganThisTrack = false
        /** One taste event per track: finished or skipped, never both. */
        private var tasteReported = false

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var index = start
            var consecutiveFailures = 0
            var lastOutcomeFailed = false
            // Songs the source was still caching when their turn came (media id → when they were
            // set aside). The first song and a song picked by a skip are waited for, with their
            // progress shown; one reached by itself is set aside so the music does not stop.
            val deferred = LinkedHashMap<String, Long>()
            var explicit = true
            var returning = false
            var resumeAt = -1
            try {
                while (live) {
                    // A song that was still caching comes back as soon as it is on the phone.
                    if (!returning && deferred.isNotEmpty()) {
                        val back = readyDeferredIndex(deferred)
                        if (back >= 0) {
                            Log.i(TAG, "\"${queue[back].title}\" is ready now: playing it before the rest")
                            resumeAt = index
                            index = back
                            returning = true
                            explicit = false
                        }
                    }
                    if (index !in queue.indices) {
                        // End of the queue: loop it, or stop.
                        if (repeat == Repeat.ALL && queue.isNotEmpty() && !lastOutcomeFailed && deferred.isEmpty()) {
                            index = 0
                            continue
                        }
                        // The queue ran out, but more is on its way: songs set aside while the
                        // source cached them, or the radio's next pick. The worker used to stop
                        // here; a pick that arrived a moment later then sat in the queue unplayed.
                        if (!returning && (deferred.isNotEmpty() || (radioSeed != null && repeat == Repeat.OFF))) {
                            output?.let { drain(it) }
                            val more = waitForMore(index, deferred)
                            if (more >= 0) {
                                resumeAt = queue.size
                                index = more
                                returning = true
                                explicit = false
                                continue
                            }
                            if (more == QUEUE_GREW) {
                                explicit = false
                                continue
                            }
                            val jump = jumpTo
                            if (jump >= 0 && live) {
                                jumpTo = -1
                                index = jump
                                explicit = true
                                continue
                            }
                        }
                        break
                    }
                    // Claim the index under the queue lock: removeUpcoming/moveUpcoming decide
                    // what is "upcoming" from currentIndex, so the two must not interleave.
                    val track = synchronized(eqLock) {
                        queue.getOrNull(index)?.also { currentIndex = index }
                    } ?: continue
                    beganThisTrack = false
                    tasteReported = false
                    declaredQuality = null
                    currentInfo = null
                    note = null
                    seekToMs = -1
                    positionMs = 0
                    lastProgressAt = SystemClock.elapsedRealtime()
                    // Known length from the source until the decoder reports the real one.
                    durationMs = track.durationMs
                    durationExact = false
                    formatInfo = null
                    if (state != State.PAUSED) setState(State.LOADING) else version++
                    extendRadioIfNeeded()
                    // Two ahead: a skip lands on a song that is already on the phone, and the
                    // one after it has had its TorBox caching started.
                    queue.getOrNull(index + 1)?.let { MediaCache.prefetch(context, it) }
                    queue.getOrNull(index + 2)?.let { MediaCache.prefetch(context, it) }
                    val outcome = try {
                        playTrack(track, deferIfNotReady = !explicit && queue.size > 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "Track ${queue.getOrNull(index)?.title} failed", e)
                        Outcome.Failed(e.message ?: e.javaClass.simpleName)
                    }
                    // A skip that arrived while the source was still resolving cancels that
                    // track (it fails with "cancelled"); follow the skip, not the failure.
                    val pendingJump = jumpTo
                    if (pendingJump >= 0 && outcome !is Outcome.Jump && outcome !is Outcome.Cancelled) {
                        jumpTo = -1
                        consecutiveFailures = 0
                        reportSkip()
                        index = pendingJump
                        returning = false
                        explicit = true
                        continue
                    }
                    lastOutcomeFailed = outcome is Outcome.Failed
                    when (outcome) {
                        Outcome.Finished -> {
                            consecutiveFailures = 0
                            reportComplete()
                            if (repeat != Repeat.ONE) index++   // ONE: replay the same track
                        }
                        Outcome.Cancelled -> return
                        is Outcome.Jump -> {
                            consecutiveFailures = 0
                            reportSkip()
                            index = outcome.index
                            returning = false
                            explicit = true
                        }
                        is Outcome.NotReady -> {
                            consecutiveFailures = 0
                            Log.i(TAG, "\"${track.title}\" is still caching (${outcome.message}): playing the next song, will come back to it")
                            deferred[track.mediaId] = SystemClock.elapsedRealtime()
                            // The request itself started the caching; keep fetching it in the
                            // background so it is on the phone when its turn comes round again.
                            MediaCache.prefetch(context, track, waitMs = DEFERRED_WAIT_MS)
                            index++
                        }
                        is Outcome.Failed -> {
                            lastError = "${queue.getOrNull(index)?.title}: ${outcome.message}"
                            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                if (live) { abandonFocus(); setState(State.ERROR) }
                                return
                            }
                            index++
                        }
                    }
                    if (outcome !is Outcome.Jump) {
                        explicit = false
                        // A song that came back from being set aside: carry on where the queue was.
                        if (returning) {
                            returning = false
                            index = resumeAt
                        }
                    }
                }
                // End of queue: let the last track finish playing out.
                output?.let { drain(it) }
                if (live) {
                    abandonFocus()
                    if (lastOutcomeFailed) {
                        if (!startedAny) {
                            // Nothing in this queue produced audio. Retrying the same entry
                            // forever looks like "the buttons do nothing" — say what broke.
                            setState(State.ERROR)
                            return
                        }
                        currentIndex = -1   // never retry a track that just failed
                    }
                    positionMs = 0   // a restored queue should start at the top, not at the end
                    setState(State.IDLE)
                }
            } finally {
                output?.release()
                output = null
            }
        }

        /** Returns an output at [rate], reusing the current one if the rate matches (gapless). */
        /** A finished track is the strongest positive signal we get without a like button. */
        private fun reportComplete() {
            if (tasteReported || !beganThisTrack) return
            tasteReported = true
            val t = queue.getOrNull(currentIndex) ?: return
            Taste.onComplete(context, t, positionMs, durationMs)
        }

        /** Skipped: where they gave up on it is the signal (early = strongly negative). */
        private fun reportSkip() {
            if (tasteReported || !beganThisTrack) return
            tasteReported = true
            val t = queue.getOrNull(currentIndex) ?: return
            Taste.onSkip(context, t, positionMs, durationMs)
            // Skipped inside the first quarter: it was not really played, so drop it from
            // history instead of offering it under "Recently played".
            if (durationMs > 0 && positionMs < durationMs / 4) {
                persistPool.execute { runCatching { settings.removeFromHistory(t) } }
            }
        }

        /** Index of a set-aside song that is on the phone now (removing it from [deferred]), else -1. */
        private fun readyDeferredIndex(deferred: MutableMap<String, Long>): Int {
            val q = queue
            val now = SystemClock.elapsedRealtime()
            val iter = deferred.entries.iterator()
            var found = -1
            while (iter.hasNext()) {
                val entry = iter.next()
                val i = q.indexOfFirst { it.mediaId == entry.key }
                // Gone from the queue, or set aside too long ago to still be worth waiting for.
                if (i < 0 || now - entry.value > DEFERRED_MAX_AGE_MS) {
                    iter.remove()
                    continue
                }
                if (found < 0 && MediaCache.fileFor(context, q[i]) != null) {
                    found = i
                    iter.remove()
                }
            }
            return found
        }

        /**
         * The queue has run out. Waits for what can still arrive: a set-aside song finishing its
         * caching (returns its index), or the radio adding its next pick ([QUEUE_GREW]). Returns
         * -1 on a skip, a stop, or when nothing came.
         */
        private fun waitForMore(index: Int, deferred: MutableMap<String, Long>): Int {
            val started = SystemClock.elapsedRealtime()
            var lastKick = 0L
            var lastAsk = 0L
            var asks = 0
            try {
                while (live) {
                    if (jumpTo >= 0) return -1
                    if (index in queue.indices) return QUEUE_GREW
                    val back = readyDeferredIndex(deferred)
                    if (back >= 0) return back
                    val now = SystemClock.elapsedRealtime()
                    val radio = radioSeed != null && repeat == Repeat.OFF
                    if (radio && !radioBusy && now - lastAsk > 1_500L) {
                        // Not looking right now: ask (again). A lookup can come back empty.
                        if (asks >= RADIO_ASKS && deferred.isEmpty()) return -1
                        if (asks < RADIO_ASKS) {
                            asks++
                            lastAsk = now
                            extendRadioIfNeeded(force = true)
                        }
                    }
                    if (!radio && deferred.isEmpty()) return -1
                    if (now - started > (if (deferred.isEmpty()) RADIO_WAIT_MS else DEFERRED_WAIT_MS)) return -1
                    val waitingFor = queue.firstOrNull { it.mediaId in deferred }
                    setNote(if (radio) "Finding the next song…" else "“${waitingFor?.title ?: "A song"}” is still caching on TorBox…")
                    if (state != State.PAUSED && state != State.LOADING) setState(State.LOADING)
                    if (deferred.isNotEmpty() && now - lastKick > 15_000L) {
                        lastKick = now
                        // A no-op while its download is still running; restarts one that gave up.
                        queue.filter { it.mediaId in deferred }
                            .forEach { MediaCache.prefetch(context, it, waitMs = DEFERRED_WAIT_MS) }
                    }
                    SystemClock.sleep(200)
                }
                return -1
            } finally {
                setNote(null)
            }
        }

        private fun outputFor(rate: Int): Output {
            output?.let { if (it.rate == rate) return it }
            output?.let { drain(it); it.release() }
            val o = Output(rate)
            output = o
            synchronized(eqLock) { if (live) current = o }
            return o
        }

        private fun drain(o: Output) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (live && jumpTo < 0 && SystemClock.elapsedRealtime() < deadline) {
                if (state == State.PAUSED) { SystemClock.sleep(50); continue }
                val played = o.track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (played >= o.framesWritten) break
                SystemClock.sleep(20)
            }
        }

        private fun playTrack(item: Track, deferIfNotReady: Boolean = false): Outcome {
            // Resolving can block for minutes (TorBox caching, addon 503/Retry-After), so it runs
            // here on the player thread and publishes progress through the note.
            // A pending skip must abandon a slow resolve (TorBox can wait minutes), otherwise
            // pressing next while "preparing" does nothing until it finishes.
            // Downloaded or prefetched: play the local file. Checked before resolving, so a
            // song on the phone starts at once and plays with no network at all.
            val beganAt = SystemClock.elapsedRealtime()
            val cached = MediaCache.fileFor(context, item)
            val cancelled = { !live || jumpTo >= 0 }
            // Not on the phone yet. One download serves both playing and keeping: the player
            // reads the file while it arrives. If the next-song download is already under way,
            // join it where it is instead of starting over on a second connection.
            var growing: GrowingDownload? = if (cached != null || !settings.progressivePlayback) null else
                MediaCache.activeFor(item)?.takeIf { it.awaitStart(JOIN_WAIT_MS, cancelled) }
            var url = ""
            if (cached == null && growing == null) {
                val resolved = try {
                    TrackResolver.resolve(
                        context,
                        item,
                        onNote = { setNote(it) },
                        isCancelled = cancelled,
                        waitIfPending = !deferIfNotReady,
                    )
                } catch (e: dev.alamo3.cabineq.addon.AddonNotReady) {
                    note = null
                    return Outcome.NotReady(e.message ?: "still caching")
                }
                declaredQuality = resolved.quality
                url = resolved.url
                if (live && settings.progressivePlayback && (url.startsWith("http://") || url.startsWith("https://"))) {
                    val opened = runCatching { MediaCache.open(context, item, url) }.getOrNull()
                    if (opened != null) {
                        if (opened.awaitStart(START_WAIT_MS, cancelled)) growing = opened
                        else {
                            // Could not start (too big for the cache, refused…): stream directly,
                            // exactly as before.
                            if (live && jumpTo < 0) Log.i(TAG, "\"${item.title}\": play-while-downloading unavailable (${opened.error ?: opened.state}); streaming directly")
                            MediaCache.leave(opened)
                        }
                    }
                }
            }
            val lookupDoneAt = SystemClock.elapsedRealtime()
            if (!live) {
                growing?.let { MediaCache.leave(it) }
                return Outcome.Cancelled
            }
            note = null
            // Files without source artwork (e.g. TorBox library tracks): read the cover and tags
            // embedded in the file itself, which is exact per song even on compilations.
            if (item.artworkUrl == null) {
                val g = growing
                if (g != null) readFileInfo("", false, gen, currentIndex, g)
                else readFileInfo(cached?.path ?: url, cached != null, gen, currentIndex)
            }

            leveler = if (!settings.loudnessMatch) null else {
                val album = item.album?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                dev.alamo3.cabineq.music.Leveler(
                    known = runCatching { LoudnessStore.get(context, item) }.getOrNull(),
                    albumGainDb = lastGainDb.takeIf { album != null && album == lastAlbum },
                    startGainDb = runCatching { LoudnessStore.typicalGainDb(context) }.getOrNull()
                        ?: dev.alamo3.cabineq.music.Leveler.DEFAULT_START_DB,
                )
            }
            var extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                val g = growing
                if (cached != null) {
                    Log.i(TAG, "playing cached \"${item.title}\" (${cached.length() / 1024} kB)")
                    extractor.setDataSource(cached.path)
                } else if (g != null) {
                    Log.i(TAG, "playing \"${item.title}\" while it downloads (${g.written / 1024} of ${g.total / 1024} kB so far)")
                    try {
                        extractor.setDataSource(GrowingSource(g, buffering = true))
                    } catch (e: Exception) {
                        if (!live || jumpTo >= 0) throw e
                        // The platform could not read it this way: fall back to the direct stream.
                        val direct = url.ifBlank { g.currentUrl }
                        Log.w(TAG, "\"${item.title}\": could not open the growing file (${e.javaClass.simpleName}: ${e.message}); streaming directly")
                        runCatching { extractor.release() }
                        MediaCache.leave(g)
                        growing = null
                        extractor = MediaExtractor()
                        extractor.setDataSource(direct, emptyMap<String, String>())
                    }
                } else {
                    extractor.setDataSource(url, emptyMap<String, String>())
                }
                // Where the time between songs goes, for when a gap needs explaining.
                Log.i(TAG, "\"${item.title}\" opened in ${SystemClock.elapsedRealtime() - beganAt} ms " +
                    "(lookup ${lookupDoneAt - beganAt} ms, source: ${if (cached != null) "on the phone" else if (growing != null) "download" else "direct stream"})")
                val trackIndex = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return Outcome.Failed("no audio stream")
                extractor.selectTrack(trackIndex)
                val inFormat = extractor.getTrackFormat(trackIndex)
                val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return Outcome.Failed("unknown format")
                if (inFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = inFormat.getLong(MediaFormat.KEY_DURATION) / 1000
                    durationExact = true
                }
                val sourceBits = if (inFormat.containsKey(KEY_BITS_PER_SAMPLE)) inFormat.getInteger(KEY_BITS_PER_SAMPLE) else 0

                val dec = createDecoder(mime, inFormat)
                codec = dec
                dec.start()

                var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var streamRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var out = outputFor(targetRate(streamRate))
                builtEpoch = routeEpoch
                out.setInputRate(streamRate)
                updateFormatInfo(mime, streamRate, sourceBits, channels)

                // Resuming a saved queue: enter the stream at the sync point at or before the
                // saved position. Only the first track of a worker starts here.
                //
                // Read one sample first: an extractor backed by a URL ignores a seek that
                // arrives before its connection is open, which silently restarted resumed
                // tracks at 0:00. The sample is thrown away, never sent to the decoder.
                if (startAtMs > 0) {
                    val probeSize = if (inFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                        inFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(1 shl 12, 1 shl 20) else 1 shl 16
                    val probe = ByteBuffer.allocateDirect(probeSize)
                    if (extractor.readSampleData(probe, 0) >= 0) extractor.advance()
                    extractor.seekTo(startAtMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    positionMs = startAtMs
                    Log.i(TAG, "Resuming at ${positionMs}ms")
                    startAtMs = 0
                }

                val info = MediaCodec.BufferInfo()
                var inputDone = false

                while (true) {
                    if (!live) return Outcome.Cancelled
                    // Plugging in headphones/USB can move the mix rate under us.
                    if (routeEpoch != builtEpoch) rebuildOutput = true
                    if (rebuildOutput) {
                        rebuildOutput = false
                        val resumeAt = positionMs
                        out.release()
                        output = null
                        out = outputFor(targetRate(streamRate))
                        builtEpoch = routeEpoch
                        out.setInputRate(streamRate)
                        out.pauseAndFlush()
                        extractor.seekTo(resumeAt * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        dec.flush()
                        inputDone = false
                        positionMs = resumeAt
                        Log.i(TAG, "Rebuilt the output sink, resuming at ${resumeAt}ms")
                        continue
                    }
                    val jump = jumpTo
                    if (jump >= 0) {
                        jumpTo = -1
                        out.pauseAndFlush()
                        return Outcome.Jump(jump)
                    }
                    val seek = seekToMs
                    if (seek >= 0) {
                        seekToMs = -1
                        extractor.seekTo(seek * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        dec.flush()               // drops queued input/output; codec keeps running
                        inputDone = false
                        out.pauseAndFlush()       // drop audio already buffered for the old position
                        positionMs = seek
                        if (state != State.PAUSED) setState(State.PLAYING) else version++
                        continue
                    }
                    if (state == State.PAUSED) {
                        if (out.track.playState == AudioTrack.PLAYSTATE_PLAYING) out.track.pause()
                        SystemClock.sleep(50)
                        continue
                    }

                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf = dec.getInputBuffer(inIdx) ?: return Outcome.Failed("decoder input unavailable")
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        streamRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        out = outputFor(targetRate(streamRate))
                        builtEpoch = routeEpoch
                        out.setInputRate(streamRate)
                        updateFormatInfo(mime, streamRate, sourceBits, channels)
                        continue
                    }
                    if (outIdx < 0) continue

                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (info.size > 0) {
                        val buf = dec.getOutputBuffer(outIdx)
                        if (buf == null) {
                            dec.releaseOutputBuffer(outIdx, false)
                            continue
                        }
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frames = info.size / (bytesPerSample(encoding) * channels)
                        if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                        toStereoFloat(buf, encoding, channels, frames, stereo)
                        dec.releaseOutputBuffer(outIdx, false)

                        // One gain per song, ahead of the EQ and limiter (see Leveler).
                        leveler?.let {
                            it.process(stereo, frames, streamRate)
                            loudnessGainDb = it.gainDb.toFloat()
                        }
                        out.process(stereo, frames)
                        if (out.track.playState != AudioTrack.PLAYSTATE_PLAYING) out.track.play()
                        val written = out.track.write(
                            out.outBuf, 0, out.processedFrames * 2, AudioTrack.WRITE_BLOCKING,
                        )
                        if (written < 0) return Outcome.Failed("AudioTrack.write error $written")
                        out.framesWritten += written / 2
                        positionMs = info.presentationTimeUs / 1000
                        lastProgressAt = SystemClock.elapsedRealtime()
                        if (state == State.LOADING) setState(State.PLAYING)
                        if (!beganThisTrack) {
                            beganThisTrack = true
                            startedAny = true
                            // The route (and so the Bluetooth guard) is only known once the
                            // track is really playing; re-push without rebuilding the sink.
                            out.refreshRouteDependentParams()
                            // History/recommendations should reflect what was really heard, not
                            // what was merely requested (some source ids have no stream at all).
                            queue.getOrNull(currentIndex)?.let { began ->
                                persistPool.execute { runCatching { settings.recordPlayed(began) } }
                                runCatching { Taste.onPlay(context, began) }
                            }
                        }
                        // Keep the saved position roughly current without touching the disk
                        // from the audio thread on every buffer.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSavedAt > SAVE_INTERVAL_MS) {
                            lastSavedAt = now
                            scheduleSave()
                        }
                    } else {
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                    if (eos) {
                        // The resampler holds a few ms of look-ahead; write it out instead of
                        // cutting the last note off.
                        out.flush()
                        if (out.processedFrames > 0) {
                            out.track.write(out.outBuf, 0, out.processedFrames * 2, AudioTrack.WRITE_BLOCKING)
                            out.framesWritten += out.processedFrames
                        }
                        return Outcome.Finished
                    }
                }
            } finally {
                // Keep what was learned about this song's level, and pass its gain on in case
                // the next one is from the same album.
                leveler?.let { lv ->
                    lv.measured()?.let { lufs -> runCatching { LoudnessStore.put(context, item, lufs) } }
                    lastAlbum = item.album?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                    lastGainDb = lv.gainDb
                }
                if (leveler == null) loudnessGainDb = Float.NaN
                leveler = null
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
                growing?.let { MediaCache.leave(it) }
            }
        }
    }

    /**
     * Uses the decoder's default PCM output (16-bit for the platform decoders) and reads the
     * actual encoding from its output format.
     *
     * Requesting float output was tried first, but some decoders ignore the request while their
     * output format still echoes it, so 16-bit samples were read as float: garbage values,
     * including NaN, which poisoned the EQ filters and played as silence. 16-bit is plenty for
     * car listening (96 dB of dynamic range).
     */
    private fun createDecoder(mime: String, format: MediaFormat): MediaCodec {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        return codec
    }

    private fun readFileInfo(source: String, isFile: Boolean, gen: Int, index: Int, growing: GrowingDownload? = null) {
        val key = "$gen:$index"
        if (infoBusy.size > 32) infoBusy.clear()   // abandoned generations never clean themselves
        if (!infoBusy.add(key)) return
        infoPool.execute {
            if (gen != generation || index != currentIndex) {
                infoBusy.remove(key)
                return@execute
            }
            val r = MediaMetadataRetriever()
            val info = try {
                if (growing != null) r.setDataSource(GrowingSource(growing, buffering = false, forGen = gen))
                else if (isFile) r.setDataSource(source) else r.setDataSource(source, HashMap<String, String>())
                FileInfo(
                    art = r.embeddedPicture?.let { decodeCover(it, 1000) },
                    title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null },
                    artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null },
                    album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifBlank { null },
                )
            } catch (e: Exception) {
                Log.i(TAG, "No embedded tags: ${e.javaClass.simpleName}")
                FileInfo(null, null, null, null)
            } finally {
                runCatching { r.release() }
            }
            infoBusy.remove(key)
            if (gen == generation && index == currentIndex) {
                currentInfo = info
                version++
            }
        }
    }

    /**
     * Hands a [GrowingDownload] to the platform's extractor. A read that has to wait for bytes
     * still on their way gives up as soon as the track is skipped or the player retired, which is
     * what the platform's own network reader could never do.
     */
    private inner class GrowingSource(
        private val download: GrowingDownload,
        /** True for the audio itself: waiting shows "Buffering…" and holds off the stall watchdog. */
        private val buffering: Boolean,
        private val forGen: Int = generation,
    ) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            var waitingSince = 0L
            var noted = false
            try {
                return download.readAt(position, buffer, offset, size) {
                    val now = SystemClock.elapsedRealtime()
                    if (buffering) {
                        if (waitingSince == 0L) waitingSince = now
                        // Waiting on the network is not a dead output: keep the watchdog quiet.
                        lastProgressAt = now
                        if (!noted && now - waitingSince > BUFFERING_NOTE_MS && state == State.PLAYING) {
                            noted = true
                            setNote("Buffering…")
                        }
                    }
                    forGen != generation || (buffering && jumpTo >= 0)
                }
            } finally {
                if (noted && note == "Buffering…") setNote(null)
            }
        }

        override fun getSize(): Long = download.total

        override fun close() = Unit   // the player releases the download itself
    }

    private fun decodeCover(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Before the service's first tick, start from the saved EQ so playback never starts flat. */
    private fun seedEqFromSettings() {
        synchronized(eqLock) {
            if (eqBands != null) return
            val s = Settings(context)
            eqBands = s.bandGains()
            eqPreampDb = s.effectivePreampDb()
            eqVersion++
        }
    }

    private fun stopWorker() {
        generation++
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) {
            // Unblocks a WRITE_BLOCKING write so the worker notices it's been retired.
            current?.let { runCatching { it.track.pause(); it.track.flush() } }
            // Kept short: this runs on the caller's (often main) thread. A worker that is
            // still inside a network read notices it was retired and releases its own output.
            t.join(500)
            if (t.isAlive) Log.w(TAG, "Player thread slow to stop; it will release its own output")
        }
        synchronized(eqLock) { current = null }
    }

    private fun setState(s: State, reason: String? = null) {
        if (s != state) {
            Log.i(TAG, "State ${state.name} -> ${s.name}" + (reason?.let { " ($it)" } ?: ""))
        }
        // The stall watchdog measures time since the last write. A pause writes nothing, so
        // entering PLAYING must restart that clock or a resume after >4 s looks like a stall.
        if (s == State.PLAYING && state != State.PLAYING) lastProgressAt = SystemClock.elapsedRealtime()
        state = s
        version++
        scheduleSave()
    }

    /** Coalesces saves: many state changes in a row land as one write. */
    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun saveQueue() {
        // Snapshot on the caller's thread, serialize and write on the persist thread.
        val tracks = queue
        val title = queueTitle
        val index = currentIndex
        val pos = positionMs
        persistPool.execute {
            val snap = if (tracks.isEmpty()) null
            else QueueSnapshot(title, tracks, index.coerceIn(0, tracks.lastIndex), pos)
            runCatching { settings.queueSnapshot = snap }
                .onFailure { Log.w(TAG, "Couldn't save the queue: ${it.message}") }
        }
    }

    private fun mimeLabel(mime: String): String = when (mime) {
        MediaFormat.MIMETYPE_AUDIO_FLAC -> "FLAC"
        MediaFormat.MIMETYPE_AUDIO_RAW -> "WAV"
        MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"
        MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
        MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"
        MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
        "audio/alac" -> "ALAC"
        else -> mime.removePrefix("audio/")
    }

    /** Re-pushes EQ and the Bluetooth guard after a route or setting change; no sink rebuild. */
    fun refreshRouteDependentParams() {
        liveOutput?.refreshRouteDependentParams()
        if (exoActive) exo?.sink?.refreshRouteDependentParams()
    }

    fun routedDevice(): AudioDeviceInfo? = liveOutput?.routed()

    /**
     * Where the *listener* is in the track, for lyrics. [positionMs] is how far the decoder has
     * got: it is ahead of the speaker by everything still queued in the AudioTrack (~200 ms,
     * more after a gapless hand-over), so lines highlighted from it arrive early.
     */
    val audiblePositionMs: Long
        get() {
            if (exoActive) return exo?.audiblePositionMs() ?: positionMs
            val pos = positionMs
            val o = liveOutput ?: return pos
            val queued = runCatching {
                val played = o.track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                (o.framesWritten - played).coerceIn(0L, o.rate * 2L)
            }.getOrDefault(0L)
            return (pos - queued * 1000L / o.rate).coerceAtLeast(0)
        }

    val outputSampleRate: Int? get() = if (exoActive) exo?.sink?.outputRate else liveOutput?.trackRate()

    private fun updateFormatInfo(mime: String, rate: Int, bits: Int, channels: Int) {
        sourceCodec = mimeLabel(mime)
        sourceSampleRate = rate
        sourceBits = bits
        val codecName = mimeLabel(mime)
        val bitsPart = if (bits > 0) " / $bits-bit" else ""
        val chPart = if (channels > 2) " / $channels ch → stereo" else ""
        val qualityPart = declaredQuality?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        formatInfo = String.format(Locale.US, "%s %.1f kHz%s%s%s", codecName, rate / 1000.0, bitsPart, chPart, qualityPart)
        version++
    }

    companion object {
        /** Ramp-in length at every (re)start; long enough to hide a step, short enough to forget. */
        private const val FADE_IN_MS = 15

        /** Full focus loss retries: enough to survive the car host grabbing the panel. */
        private const val FOCUS_RETRIES = 4
        private const val FOCUS_RETRY_MS = 1500L

        private const val TAG = "CabinEQ/Player"
        private const val TIMEOUT_US = 10_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"
        private const val SAVE_DELAY_MS = 1_500L
        private const val SAVE_INTERVAL_MS = 10_000L
        private const val STALL_MS = 4_000L
        /** How long a skip may wait for the busy worker before a fresh one takes over. */
        private const val SKIP_PICKUP_MS = 600L
        /** [Worker.waitForMore]: the queue has a new entry at the index that was past its end. */
        private const val QUEUE_GREW = -2
        /** How long the end of a radio queue waits for the next pick, and how often it asks. */
        private const val RADIO_WAIT_MS = 90_000L
        private const val RADIO_ASKS = 4
        /** How long to wait for a new download's first reply before streaming directly instead. */
        private const val START_WAIT_MS = 20_000L
        /** How long to wait for an already-running download to show it has started. */
        private const val JOIN_WAIT_MS = 3_000L
        private const val BUFFERING_NOTE_MS = 800L
        /** How long to wait for set-aside songs once nothing else is left to play. */
        private const val DEFERRED_WAIT_MS = 5 * 60_000L
        /** A song set aside this long ago is dropped from the list of ones to come back to. */
        private const val DEFERRED_MAX_AGE_MS = 10 * 60_000L
        /** A rebuilt sink gets this long before another stall is treated as fatal. */
        private const val STALL_REBUILD_WINDOW_MS = 30_000L

        // Same policy as the capture output: system components (Android Auto) may capture it.
        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
            .build()

        @Volatile private var instance: FilePlayer? = null

        fun get(context: Context): FilePlayer =
            instance ?: synchronized(this) {
                instance ?: FilePlayer(context.applicationContext).also {
                    instance = it
                    it.warmUp()
                }
            }

        /** The instance if one was ever created (doesn't create one). */
        fun peek(): FilePlayer? = instance

        // PCM conversion lives in [dev.alamo3.cabineq.music.Pcm], shared with the Media3 engine so
        // both feed the DSP the same samples.
        private fun bytesPerSample(encoding: Int): Int = dev.alamo3.cabineq.music.Pcm.bytesPerSample(encoding)

        private fun toStereoFloat(buf: ByteBuffer, encoding: Int, channels: Int, frames: Int, out: FloatArray) =
            dev.alamo3.cabineq.music.Pcm.toStereoFloat(buf, encoding, channels, frames, out)
    }
}
