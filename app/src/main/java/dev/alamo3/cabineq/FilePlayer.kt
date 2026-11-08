package dev.alamo3.cabineq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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

    /**
     * One queue entry. [resolveUrl] runs on the player thread right before the track starts
     * (links can expire). The other fields are display metadata (UI, MediaSession, car screen).
     */
    class Track(
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val artworkUrl: String? = null,
        val resolveUrl: () -> String,
    )

    enum class State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    @Volatile var state = State.IDLE
        private set
    @Volatile var queueTitle: String? = null
        private set
    @Volatile var queue: List<Track> = emptyList()
        private set
    @Volatile var currentIndex = -1
        private set
    @Volatile var positionMs = 0L
        private set
    @Volatile var durationMs = 0L
        private set
    /** e.g. "FLAC 44.1 kHz / 16-bit" */
    @Volatile var formatInfo: String? = null
        private set
    @Volatile var lastError: String? = null
        private set
    /** Transient status from the track source, e.g. "Waiting on TorBox: caching" (cleared on play). */
    @Volatile var note: String? = null
        private set

    /** Called from a [Track.resolveUrl] while it waits on its source. */
    fun setNote(text: String?) {
        note = text
        version++
    }

    /** Bumped on every track / state change so observers (MediaSession, UI) can update cheaply. */
    @Volatile var version = 0
        private set

    val currentTitle: String? get() = queue.getOrNull(currentIndex)?.title
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)

    /**
     * Identifies the current queue/skip. A [Track.resolveUrl] that waits (e.g. on TorBox caching)
     * captures it at start and gives up once [isStale] says playback moved on.
     */
    val ticket: Int get() = generation * 100_000 + (if (jumpTo >= 0) -1 else currentIndex)
    fun isStale(ticket: Int): Boolean = ticket != this.ticket
    val isActive: Boolean get() = state == State.LOADING || state == State.PLAYING || state == State.PAUSED

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var thread: Thread? = null

    @Volatile private var generation = 0          // incremented to retire the current worker
    @Volatile private var jumpTo = -1             // pending skip target
    @Volatile private var seekToMs = -1L          // pending seek within the current track
    @Volatile private var userPaused = false
    @Volatile private var focusPaused = false

    // EQ state and the active output's native engine, guarded by [eqLock].
    private val eqLock = Any()
    private var eqBands: FloatArray? = null
    private var eqPreampDb = 0f
    private var eqLimiterLin = 0.891f
    private var eqVersion = 0
    @Volatile private var current: Output? = null
    private val meterScratch = FloatArray(3)

    // ---------------------------------------------------------------- controls

    fun play(tracks: List<Track>, startIndex: Int = 0, title: String? = null) {
        if (tracks.isEmpty()) return
        stopWorker()
        seedEqFromSettings()
        queue = tracks
        queueTitle = title
        lastError = null
        userPaused = false
        focusPaused = false
        jumpTo = -1
        if (!requestFocus()) {
            lastError = "Another app holds audio focus"
            setState(State.ERROR)
            return
        }
        val gen = ++generation
        val worker = Worker(gen, startIndex.coerceIn(0, tracks.lastIndex))
        thread = Thread(worker, "CabinEQ-Player").apply { start() }
    }

    fun togglePause() {
        if (state == State.PAUSED || state == State.IDLE || state == State.ERROR) resume() else pause()
    }

    fun pause() {
        if (!isActive) return
        userPaused = true
        setState(State.PAUSED)
    }

    fun resume() {
        if (state == State.IDLE || state == State.ERROR) {
            if (queue.isNotEmpty()) play(queue, max(0, currentIndex), queueTitle)
            return
        }
        if (!requestFocus()) return
        userPaused = false
        focusPaused = false
        if (state == State.PAUSED) setState(State.PLAYING)
    }

    fun next() {
        if (queue.isEmpty()) return
        if (currentIndex < queue.lastIndex) skipTo(currentIndex + 1) else stop()
    }

    /** Restarts the current track if more than 3 s in, otherwise goes to the previous one. */
    fun previous() {
        if (queue.isEmpty()) return
        skipTo(if (positionMs > 3000 || currentIndex <= 0) max(0, currentIndex) else currentIndex - 1)
    }

    fun skipTo(index: Int) {
        if (index !in queue.indices) return
        if (thread?.isAlive == true) {
            userPaused = false
            jumpTo = index
            if (state == State.PAUSED) setState(State.LOADING)
        } else {
            play(queue, index, queueTitle)
        }
    }

    /** Seeks within the current track (to the nearest sync point at or before [ms]). */
    fun seekTo(ms: Long) {
        if (!isActive) return
        val target = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
        seekToMs = target
        positionMs = target
        version++
    }

    fun stop() {
        stopWorker()
        abandonFocus()
        positionMs = 0
        setState(State.IDLE)
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
        }
    }

    /** [inPeak, outPeak, minLimiterGain] since the last call, or null if not playing. */
    fun readMeters(): FloatArray? {
        synchronized(eqLock) {
            val h = current?.handle ?: 0L
            if (h == 0L || state != State.PLAYING) return null
            NativeDsp.readMeters(h, meterScratch)
            return meterScratch.copyOf()
        }
    }

    fun routedDeviceType(): Int? = if (state == State.PLAYING) current?.track?.routedDevice?.type else null

    fun isRoutedToPhoneSpeaker(): Boolean {
        val t = routedDeviceType() ?: return false
        return t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE
    }

    // ---------------------------------------------------------------- audio focus

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another music app started: pause and stay paused.
                userPaused = true
                if (isActive) setState(State.PAUSED)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Phone call etc.: pause, resume when it ends.
                focusPaused = true
                if (state == State.PLAYING || state == State.LOADING) setState(State.PAUSED)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusPaused && !userPaused && state == State.PAUSED) setState(State.PLAYING)
                focusPaused = false
            }
            // LOSS_TRANSIENT_CAN_DUCK (navigation prompts): the system ducks us automatically.
        }
    }

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRIBUTES)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    // ---------------------------------------------------------------- output

    /** One AudioTrack + one native engine at a fixed sample rate. Owned by a single worker. */
    private inner class Output(val rate: Int) {
        val track: AudioTrack
        var handle = 0L          // guarded by eqLock
        private var appliedEq = -1
        /** Frames written since creation or the last flush; compared with the play head to drain. */
        var framesWritten = 0L

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
            }
        }

        fun applyEqLocked() {
            val bands = eqBands ?: return
            if (handle == 0L || appliedEq == eqVersion) return
            val coeffs = EqDesign.designAll(bands, rate.toDouble())
            NativeDsp.setParams(handle, coeffs, bands.size, EqService.dbToLin(eqPreampDb), eqLimiterLin)
            appliedEq = eqVersion
        }

        fun process(buf: FloatArray, frames: Int) {
            synchronized(eqLock) {
                if (handle != 0L) NativeDsp.process(handle, buf, frames)
            }
        }

        fun pauseAndFlush() {
            runCatching { track.pause(); track.flush() }
            framesWritten = 0
        }

        fun release() {
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
    }

    private inner class Worker(private val gen: Int, private val start: Int) : Runnable {
        private var output: Output? = null
        private var stereo = FloatArray(0)

        private val live: Boolean get() = gen == generation

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var index = start
            var consecutiveFailures = 0
            try {
                while (live && index in queue.indices) {
                    currentIndex = index
                    note = null
                    seekToMs = -1
                    positionMs = 0
                    durationMs = 0
                    formatInfo = null
                    if (state != State.PAUSED) setState(State.LOADING) else version++
                    val outcome = try {
                        playTrack(queue[index])
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
                        index = pendingJump
                        continue
                    }
                    when (outcome) {
                        Outcome.Finished -> { consecutiveFailures = 0; index++ }
                        Outcome.Cancelled -> return
                        is Outcome.Jump -> { consecutiveFailures = 0; index = outcome.index }
                        is Outcome.Failed -> {
                            lastError = "${queue.getOrNull(index)?.title}: ${outcome.message}"
                            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                if (live) { abandonFocus(); setState(State.ERROR) }
                                return
                            }
                            index++
                        }
                    }
                }
                // End of queue: let the last track finish playing out.
                output?.let { drain(it) }
                if (live) {
                    abandonFocus()
                    setState(State.IDLE)
                }
            } finally {
                output?.release()
                output = null
            }
        }

        /** Returns an output at [rate], reusing the current one if the rate matches (gapless). */
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

        private fun playTrack(item: Track): Outcome {
            val url = item.resolveUrl()
            if (!live) return Outcome.Cancelled
            note = null

            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(url, emptyMap<String, String>())
                val trackIndex = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return Outcome.Failed("no audio stream")
                extractor.selectTrack(trackIndex)
                val inFormat = extractor.getTrackFormat(trackIndex)
                val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return Outcome.Failed("unknown format")
                if (inFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = inFormat.getLong(MediaFormat.KEY_DURATION) / 1000
                }
                val sourceBits = if (inFormat.containsKey(KEY_BITS_PER_SAMPLE)) inFormat.getInteger(KEY_BITS_PER_SAMPLE) else 0

                val dec = createDecoder(mime, inFormat)
                codec = dec
                dec.start()

                var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var out = outputFor(inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                updateFormatInfo(mime, out.rate, sourceBits, channels)

                val info = MediaCodec.BufferInfo()
                var inputDone = false

                while (true) {
                    if (!live) return Outcome.Cancelled
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
                        out = outputFor(f.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        updateFormatInfo(mime, out.rate, sourceBits, channels)
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

                        out.process(stereo, frames)
                        if (out.track.playState != AudioTrack.PLAYSTATE_PLAYING) out.track.play()
                        val written = out.track.write(stereo, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) return Outcome.Failed("AudioTrack.write error $written")
                        out.framesWritten += written / 2
                        positionMs = info.presentationTimeUs / 1000
                        if (state == State.LOADING) setState(State.PLAYING)
                    } else {
                        dec.releaseOutputBuffer(outIdx, false)
                    }
                    if (eos) return Outcome.Finished
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
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
            t.join(2000)
            if (t.isAlive) Log.w(TAG, "Player thread slow to stop; it will release its own output")
        }
        synchronized(eqLock) { current = null }
    }

    private fun setState(s: State) {
        state = s
        version++
    }

    private fun updateFormatInfo(mime: String, rate: Int, bits: Int, channels: Int) {
        val codecName = when (mime) {
            MediaFormat.MIMETYPE_AUDIO_FLAC -> "FLAC"
            MediaFormat.MIMETYPE_AUDIO_RAW -> "WAV"
            MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"
            MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
            MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"
            MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
            "audio/alac" -> "ALAC"
            else -> mime.removePrefix("audio/")
        }
        val bitsPart = if (bits > 0) " / $bits-bit" else ""
        val chPart = if (channels > 2) " / $channels ch → stereo" else ""
        formatInfo = String.format(Locale.US, "%s %.1f kHz%s%s", codecName, rate / 1000.0, bitsPart, chPart)
        version++
    }

    companion object {
        private const val TAG = "CabinEQ/Player"
        private const val TIMEOUT_US = 10_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val KEY_BITS_PER_SAMPLE = "bits-per-sample"

        // Same policy as the capture output: system components (Android Auto) may capture it.
        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
            .build()

        @Volatile private var instance: FilePlayer? = null

        fun get(context: Context): FilePlayer =
            instance ?: synchronized(this) {
                instance ?: FilePlayer(context.applicationContext).also { instance = it }
            }

        /** The instance if one was ever created (doesn't create one). */
        fun peek(): FilePlayer? = instance

        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }

        /**
         * Converts [frames] interleaved frames of any PCM encoding / channel count into
         * interleaved stereo float. Mono is duplicated; for >2 channels the front L/R are kept.
         */
        private fun toStereoFloat(buf: ByteBuffer, encoding: Int, channels: Int, frames: Int, out: FloatArray) {
            val bps = bytesPerSample(encoding)
            val base = buf.position()
            for (i in 0 until frames) {
                val p = base + i * channels * bps
                val l = sanitize(readSample(buf, p, encoding))
                val r = if (channels >= 2) sanitize(readSample(buf, p + bps, encoding)) else l
                out[2 * i] = l
                out[2 * i + 1] = r
            }
        }

        /** One bad sample must never reach the IIR filters: NaN/Inf would poison them for good. */
        private fun sanitize(x: Float): Float = when {
            x.isNaN() || x.isInfinite() -> 0f
            x > 4f -> 4f
            x < -4f -> -4f
            else -> x
        }

        private fun readSample(buf: ByteBuffer, p: Int, encoding: Int): Float = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buf.getFloat(p)
            AudioFormat.ENCODING_PCM_32BIT -> buf.getInt(p) / 2147483648f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val v = (buf.get(p).toInt() and 0xFF) or
                    ((buf.get(p + 1).toInt() and 0xFF) shl 8) or
                    (buf.get(p + 2).toInt() shl 16)   // sign comes from the top byte
                v / 8388608f
            }
            AudioFormat.ENCODING_PCM_8BIT -> ((buf.get(p).toInt() and 0xFF) - 128) / 128f
            else -> buf.getShort(p) / 32768f
        }
    }
}
