package dev.alamo3.cabineq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import dev.alamo3.cabineq.music.Pcm
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Spotify through Soloist (in Termux) as a source for this equaliser.
 *
 * Soloist renders into a PulseAudio pipe sink inside Debian, and socat serves that pipe as raw
 * PCM on a loopback port (see `tools/cabineq-soloist.sh`). This connects to it and does what
 * [CaptureEngine] does for other apps: s16le stereo in, the same native engine (EQ, limiter,
 * subsonic filter) at the device rate, an AudioTrack out.
 *
 * Playback capture is deliberately not involved: Termux's stream has no audio session, so the
 * source could not be muted while its captured copy plays — two copies would be heard. A socket
 * has neither problem and needs no MediaProjection consent.
 *
 * One clock: this one. A pipe sink has no clock of its own — it renders only as fast as its pipe
 * is drained — so when the jitter buffer is full the reader simply stops reading ([put] waits),
 * the socket fills, socat stops draining the pipe, and Soloist is held back. The producer runs at
 * exactly the rate the AudioTrack plays: no drift to correct, nothing to drop, no rate trim.
 * While playback is paused for silence the stream is still consumed at real time, so that hold
 * does not turn into a free-running producer.
 */
object SoloistInput {
    private const val TAG = "CabinEQ/Soloist"

    /** Port the Termux side serves on (`tools/cabineq-soloist.sh`). */
    const val DEFAULT_PORT = 4712

    private const val RATE = 44100          // what the PCM server sends
    private const val CHANNELS = 2
    // The pipe sink writes in chunks of up to the pipe's size (~370 ms at 64 KB), so the cushion
    // sits above that. The cap is where the reader stops reading: the backpressure point.
    private const val TARGET_MS = 500        // cushion before playback starts
    private const val RESUME_MS = 300        // cushion before carrying on after a starve
    private const val CAP_MS = 1000          // buffer full: stop reading until the player drains it
    private const val SOCKET_BUFFER = 32 * 1024   // a small socket buffer keeps the delay short
    private const val OUTPUT_BUFFER_MS = 300 // AudioTrack cushion, so a late write is not a gap
    private const val BLOCK_FRAMES = 2048
    private const val SILENCE_PAUSE_MS = 2000
    private const val LIMITER_THRESHOLD_LIN = 0.7943f   // -2 dBFS, as EqService uses

    @Volatile private var running = false
    @Volatile var status: String = "off"
        private set
    @Volatile var lastError: String? = null
        private set

    /** One line for the status screen: what it is doing, and at what level. */
    @Volatile private var lastPeak = 0f
    /** Times the socket had nothing to give while playing: the cause of a pause. */
    @Volatile private var underruns = 0
    /** Times the AudioTrack itself ran dry — a late write, not a short supply. */
    @Volatile private var trackUnderruns = 0
    /** Times playback was paused because the source went silent. */
    @Volatile private var silentPauses = 0

    private var reader: Thread? = null
    private var player: Thread? = null
    private var socket: Socket? = null
    private var track: AudioTrack? = null
    private var handle = 0L
    private var sampleRate = 48000
    private var settings: Settings? = null

    // The jitter buffer: stereo s16 bytes, filled by the reader, drained by the player.
    private val lock = Object()
    private var ring = ByteArray(RATE * CHANNELS * 2 * CAP_MS / 1000)
    private var head = 0
    private var count = 0

    // What was last pushed to the engine, so the EQ can follow the sliders while this plays.
    private var pushedBands = FloatArray(0)
    private var pushedPreamp = Float.NaN
    private var pushedSubsonic = false

    val isRunning: Boolean get() = running

    fun describe(): String = when {
        running -> String.format(
            java.util.Locale.US,
            "%s — peak %.0f dBFS, %d ms buffered, %d underruns, %d track",
            status,
            if (lastPeak > 0f) 20 * Math.log10(lastPeak.toDouble()) else -120.0,
            bufferedBytes() * 1000 / (RATE * CHANNELS * 2),
            underruns, trackUnderruns,
        )
        else -> status
    }

    fun start(context: Context, port: Int = DEFAULT_PORT): Boolean {
        if (running) return true
        val app = context.applicationContext
        settings = Settings(app)
        lastError = null
        lastPeak = 0f
        status = "connecting to 127.0.0.1:$port"

        try {
            val am = app.getSystemService(android.media.AudioManager::class.java)
            sampleRate = am?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()?.takeIf { it in 44100..48000 } ?: 48000

            handle = NativeDsp.create(sampleRate)
            NativeDsp.setInputRate(handle, RATE)
            pushedBands = FloatArray(0)
            pushedPreamp = Float.NaN
            pushParams(force = true)

            val trackMin = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        // Android Auto plays the car by capturing our output.
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                // 200 ms of headroom: the writes come from another thread, and a late one must
                // not leave the AudioTrack empty (which is heard as a gap).
                .setBufferSizeInBytes(max(trackMin, sampleRate * 8 * OUTPUT_BUFFER_MS / 1000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Deliberately not PERFORMANCE_MODE_LOW_LATENCY: that asks for a small, fast
                // track whose buffer is not the one requested here, and a late write then runs
                // dry — heard as a gap. Music wants a deep buffer, not a short one.
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
            if (track?.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AudioTrack not initialised")

        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            status = "cannot start: $lastError"
            DLog.w(TAG, "start failed: $lastError")
            release()
            return false
        }

        synchronized(lock) { head = 0; count = 0 }
        running = true
        // The socket is connected on the reader thread: a socket open on the main thread throws
        // NetworkOnMainThreadException, and a slow connect must not freeze the screen either.
        reader = Thread({ connectAndRead(port) }, "CabinEQ-SoloistIn").apply { start() }
        player = Thread({ playLoop() }, "CabinEQ-SoloistOut").apply { start() }
        return true
    }

    fun stop() {
        if (!running) {
            status = "off"
            return
        }
        running = false
        runCatching { socket?.close() }
        synchronized(lock) { lock.notifyAll() }
        reader?.join(700)
        val p = player
        p?.join(1200)
        reader = null
        if (p != null && p.isAlive) {
            // Never free the engine under a thread that may still use it.
            DLog.w(TAG, "output thread did not stop in time; leaving the engine alone")
        } else {
            player = null
            release()
        }
        status = "off"
        DLog.i(TAG, "stopped")
    }

    private fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        if (handle != 0L) {
            NativeDsp.destroy(handle)
            handle = 0L
        }
    }

    // ---------------------------------------------------------------- the socket reader

    private fun connectAndRead(port: Int) {
        val buf = ByteArray(16 * 1024)
        // The source lives in Termux and is restarted independently of this app, so a dropped
        // connection is normal: reconnect, and start from the live edge rather than whatever the
        // buffer held before (that audio is seconds old and would be played out of order).
        while (running) {
            val sock = Socket()
            // Published before connecting, so stop() can close it and abort a connect that hangs.
            socket = sock
            try {
                // Set before connecting so the window is negotiated small: what sits in socket
                // buffers is delay between a tap in Spotify and the sound.
                sock.receiveBufferSize = SOCKET_BUFFER
                sock.connect(InetSocketAddress("127.0.0.1", port), 3000)
                sock.tcpNoDelay = true
                synchronized(lock) { head = 0; count = 0 }
                status = "connected, waiting for audio"
                DLog.i(
                    TAG,
                    "connected to 127.0.0.1:$port, engine at $sampleRate Hz from ${RATE} Hz, " +
                        "track buffer ${track?.bufferSizeInFrames ?: 0} frames",
                )
                readLoop(sock, buf)
            } catch (e: Exception) {
                if (running) {
                    lastError = e.message ?: e.javaClass.simpleName
                    status = "cannot connect: $lastError"
                    DLog.w(TAG, "cannot connect: $lastError")
                }
            } finally {
                runCatching { sock.close() }
                socket = null
            }
            if (!running) break
            status = "reconnecting…"
            try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
        }
    }

    private fun readLoop(sock: Socket, buf: ByteArray) {
        try {
            val input = sock.getInputStream()
            while (running) {
                val n = input.read(buf)
                if (n < 0) {
                    if (running) status = "stream ended"
                    break
                }
                if (n == 0) continue
                put(buf, n - n % (CHANNELS * 2))   // whole frames only
            }
        } catch (e: Exception) {
            if (running) DLog.w(TAG, "stream ended: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun put(src: ByteArray, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            // Full: wait for the player instead of dropping. Not reading is the backpressure that
            // paces Soloist (see the class comment). A chunk larger than the ring cannot happen
            // (reads are 16 KB, the ring is a second), but would only be truncated, not hang.
            while (running && count + len > ring.size && count > 0) lock.wait(100)
            if (!running) return
            val room = ring.size - count
            val take = min(len, room) / (CHANNELS * 2) * (CHANNELS * 2)
            if (take <= 0) return
            var tail = (head + count) % ring.size
            var left = take
            var off = 0
            while (left > 0) {
                val n = min(left, ring.size - tail)
                System.arraycopy(src, off, ring, tail, n)
                tail = (tail + n) % ring.size
                off += n
                left -= n
            }
            count += take
            lock.notifyAll()
        }
    }

    /**
     * Up to [dst].size bytes of whole frames — not exactly that many. Demanding a full block
     * turned every partial arrival into a "starved" buffer and a stall.
     */
    private fun takeUpTo(dst: ByteArray, len: Int): Int {
        synchronized(lock) {
            val frame = CHANNELS * 2
            val want = min(len, count / frame * frame)
            if (want <= 0) return 0
            var left = want
            var off = 0
            while (left > 0) {
                val n = min(left, ring.size - head)
                System.arraycopy(ring, head, dst, off, n)
                head = (head + n) % ring.size
                off += n
                left -= n
            }
            count -= want
            lock.notifyAll()      // room again: wake a reader waiting in put()
            return want
        }
    }

    private fun bufferedBytes(): Int = synchronized(lock) { count }

    // ---------------------------------------------------------------- the player

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val out = track ?: return
        val pcm = ByteArray(BLOCK_FRAMES * CHANNELS * 2)
        val floats = FloatArray(BLOCK_FRAMES * CHANNELS)
        val processed = FloatArray(BLOCK_FRAMES * 2 * 3)
        val targetBytes = RATE * CHANNELS * 2 * TARGET_MS / 1000
        var played = false
        var silentMs = 0
        var sinceParams = 0L
        // True while the jitter buffer has the cushion it needs. Waiting for it on *every*
        // block (as this did) turns any dip in the socket stream into a stall until the buffer
        // is full again — heard as a pause every so often.
        var primed = false
        var everPlayed = false
        // After a starve, resume as soon as there is a little audio: a full cushion here is a
        // second, deliberate pause.
        val resumeBytes = RATE * CHANNELS * 2 * RESUME_MS / 1000

        try {
            while (running) {
                if (!primed) {
                    val need = if (everPlayed) resumeBytes else targetBytes
                    synchronized(lock) {
                        val until = SystemClock.elapsedRealtime() + 600
                        while (running && count < need && SystemClock.elapsedRealtime() < until) lock.wait(50)
                        primed = count > 0
                    }
                    if (!running) break
                    if (!primed) continue
                }

                val got = takeUpTo(pcm, pcm.size)
                if (got == 0) {
                    // Starved: fill the cushion again before carrying on, and say so.
                    primed = false
                    if (everPlayed) {
                        underruns++
                        if (underruns <= 5) DLog.w(TAG, "buffer underrun #$underruns")
                    }
                    continue
                }
                val frames = got / (CHANNELS * 2)
                if (frames == 0) continue

                val now = SystemClock.elapsedRealtime()
                if (now - sinceParams > 500) {
                    sinceParams = now
                    pushParams(force = false)
                }
                val bb = ByteBuffer.wrap(pcm, 0, got).order(ByteOrder.LITTLE_ENDIAN)
                Pcm.toStereoFloat(bb, Pcm.ENCODING_16BIT, CHANNELS, frames, floats)

                var peak = 0f
                for (i in 0 until frames * 2) {
                    val a = if (floats[i].isFinite()) kotlin.math.abs(floats[i]) else 0f
                    if (a > peak) peak = a
                }
                lastPeak = peak
                if (peak > 0f) {
                    silentMs = 0
                    everPlayed = true
                    if (!played) {
                        out.play()
                        played = true
                        status = "playing"
                    }
                } else {
                    silentMs += frames * 1000 / RATE
                    if (played && silentMs > SILENCE_PAUSE_MS) {
                        out.pause()
                        out.flush()
                        played = false
                        silentPauses++
                        status = "silent (Spotify not playing?)"
                    }
                }
                if (!played) {
                    // Paused for silence, the stream still has to be drained at real time: the
                    // producer is paced by this consumer, and an unpaced drain would spin.
                    Thread.sleep(frames * 1000L / RATE)
                    continue
                }

                val h = handle
                if (h == 0L) continue
                val cap = NativeDsp.maxFrames(h, frames)
                if (processed.size < cap * 2) continue
                val n = NativeDsp.process(h, floats, frames, processed, cap)
                if (n > 0) out.write(processed, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                val trackUnder = out.underrunCount
                if (trackUnder > trackUnderruns) {
                    if (trackUnderruns == 0) DLog.w(TAG, "AudioTrack underrun (track $trackUnder)")
                    trackUnderruns = trackUnder
                }
            }
        } catch (e: Exception) {
            if (running) {
                lastError = e.message ?: e.javaClass.simpleName
                status = "playback failed: $lastError"
                DLog.w(TAG, "playback failed: $lastError")
            }
        } finally {
            runCatching { out.pause() }
            runCatching { out.flush() }
        }
    }

    private fun pushParams(force: Boolean) {
        val h = handle
        val s = settings ?: return
        if (h == 0L) return
        val bands = s.bandGains()
        val preamp = s.effectivePreampDb()
        val subsonic = s.subsonicFilter
        if (!force && bands.contentEquals(pushedBands) && preamp == pushedPreamp && subsonic == pushedSubsonic) return
        val rate = sampleRate.toDouble()
        val sub = if (subsonic) EqDesign.highPass(rate) else DoubleArray(0)
        val coeffs = sub + EqDesign.designAll(bands, rate)
        NativeDsp.setParams(h, coeffs, coeffs.size / 5, EqService.dbToLin(preamp), LIMITER_THRESHOLD_LIN, null, 0)
        pushedBands = bands.copyOf()
        pushedPreamp = preamp
        pushedSubsonic = subsonic
    }
}
