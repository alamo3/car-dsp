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
 * Soloist renders into a PulseAudio null sink inside Debian, and a PulseAudio module serves that
 * sink's monitor as raw PCM on a loopback port (see `tools/cabineq-soloist.sh`). This connects to
 * it and does what [CaptureEngine] does for other apps: s16le stereo in, the same native engine
 * (EQ, limiter, subsonic filter) at the device rate, an AudioTrack out.
 *
 * Playback capture is deliberately not involved: Termux's stream has no audio session, so the
 * source could not be muted while its captured copy plays — two copies would be heard. A socket
 * has neither problem and needs no MediaProjection consent.
 *
 * The producer (44.1 kHz on the other side of a socket) and the phone's output clock are not the
 * same clock. Left alone the buffer drains or overflows over minutes, so playback starts with
 * [TARGET_MS] queued and anything beyond [MAX_MS] is dropped — the drop lands on a buffer
 * boundary, which is inaudible, instead of a click at an arbitrary sample.
 */
object SoloistInput {
    private const val TAG = "CabinEQ/Soloist"

    /** Port the Termux side serves on (`tools/cabineq-soloist.sh`). */
    const val DEFAULT_PORT = 4712

    private const val RATE = 44100          // what the PCM server sends
    private const val CHANNELS = 2
    private const val TARGET_MS = 250        // buffer before playback starts
    private const val MAX_MS = 800           // beyond this, the oldest audio is dropped
    private const val BLOCK_FRAMES = 1024
    private const val SILENCE_PAUSE_MS = 2000
    private const val LIMITER_THRESHOLD_LIN = 0.7943f   // -2 dBFS, as EqService uses

    @Volatile private var running = false
    @Volatile var status: String = "off"
        private set
    @Volatile var lastError: String? = null
        private set

    /** One line for the status screen: what it is doing, and at what level. */
    @Volatile private var lastPeak = 0f

    private var reader: Thread? = null
    private var player: Thread? = null
    private var socket: Socket? = null
    private var track: AudioTrack? = null
    private var handle = 0L
    private var sampleRate = 48000
    private var settings: Settings? = null

    // The jitter buffer: stereo s16 bytes, filled by the reader, drained by the player.
    private val lock = Object()
    private var ring = ByteArray(RATE * CHANNELS * 2)
    private var head = 0
    private var count = 0

    // What was last pushed to the engine, so the EQ can follow the sliders while this plays.
    private var pushedBands = FloatArray(0)
    private var pushedPreamp = Float.NaN
    private var pushedSubsonic = false

    val isRunning: Boolean get() = running

    fun describe(): String = when {
        running -> String.format(
            java.util.Locale.US, "%s — peak %.0f dBFS", status,
            if (lastPeak > 0f) 20 * Math.log10(lastPeak.toDouble()) else -120.0,
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
                .setBufferSizeInBytes(max(trackMin, BLOCK_FRAMES * 8) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track?.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AudioTrack not initialised")

            val sock = Socket()
            sock.connect(InetSocketAddress("127.0.0.1", port), 3000)
            sock.tcpNoDelay = true
            socket = sock
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            status = "cannot start: $lastError"
            DLog.w(TAG, "start failed: $lastError")
            release()
            return false
        }

        synchronized(lock) { head = 0; count = 0 }
        running = true
        reader = Thread({ readLoop() }, "CabinEQ-SoloistIn").apply { start() }
        player = Thread({ playLoop() }, "CabinEQ-SoloistOut").apply { start() }
        status = "connected, waiting for audio"
        DLog.i(TAG, "connected to 127.0.0.1:$port, engine at $sampleRate Hz from ${RATE} Hz")
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

    private fun readLoop() {
        val sock = socket ?: return
        val buf = ByteArray(16 * 1024)
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
            if (running) {
                lastError = e.message ?: e.javaClass.simpleName
                status = "read failed: $lastError"
                DLog.w(TAG, "read failed: $lastError")
            }
        }
    }

    private fun put(src: ByteArray, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            val maxBytes = RATE * CHANNELS * 2 * MAX_MS / 1000
            if (count + len > maxBytes) {
                // Too far behind: drop the oldest whole frames so latency stays bounded.
                val drop = (count + len - maxBytes + (CHANNELS * 2 - 1)) / (CHANNELS * 2) * (CHANNELS * 2)
                head = (head + drop) % ring.size
                count = max(0, count - drop)
            }
            while (count + len > ring.size) {
                // The ring itself is a second of audio; shrink back to it.
                val drop = count + len - ring.size
                head = (head + drop) % ring.size
                count -= drop
            }
            var tail = (head + count) % ring.size
            var left = len
            var off = 0
            while (left > 0) {
                val n = min(left, ring.size - tail)
                System.arraycopy(src, off, ring, tail, n)
                tail = (tail + n) % ring.size
                off += n
                left -= n
            }
            count += len
            lock.notifyAll()
        }
    }

    private fun take(dst: ByteArray, len: Int): Int {
        synchronized(lock) {
            if (count < len) return 0
            var left = len
            var off = 0
            while (left > 0) {
                val n = min(left, ring.size - head)
                System.arraycopy(ring, head, dst, off, n)
                head = (head + n) % ring.size
                off += n
                left -= n
            }
            count -= len
            return len
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

        try {
            while (running) {
                // Wait for the jitter buffer to fill, without blocking a stop.
                synchronized(lock) {
                    while (running && count < targetBytes) lock.wait(50)
                    if (!running) break
                }

                val got = take(pcm, pcm.size)
                if (got == 0) continue
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
                        status = "silent (Spotify not playing?)"
                    }
                }
                if (!played) continue

                val h = handle
                if (h == 0L) continue
                val cap = NativeDsp.maxFrames(h, frames)
                if (processed.size < cap * 2) continue
                val n = NativeDsp.process(h, floats, frames, processed, cap)
                if (n > 0) out.write(processed, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                trimIfBehind(targetBytes)
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

    /** If the phone's clock fell behind, throw away the surplus instead of letting it grow. */
    private fun trimIfBehind(targetBytes: Int) {
        val maxBytes = RATE * CHANNELS * 2 * MAX_MS / 1000
        synchronized(lock) {
            if (count <= maxBytes) return
            val drop = count - targetBytes
            head = (head + drop) % ring.size
            count -= drop
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
