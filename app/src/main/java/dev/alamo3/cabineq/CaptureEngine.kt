package dev.alamo3.cabineq

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Capture -> native DSP -> playback loop.
 *
 * Input: AudioPlaybackCapture of media/game/unknown audio from other apps (our own UID is
 * excluded, so we never capture ourselves).
 * Output: an AudioTrack that follows normal media routing (Bluetooth, USB, Android Auto).
 * It allows capture *by system components* — Android Auto delivers phone audio to the car
 * by capturing it, and if we forbade that (as RootlessJamesDSP does) Android Auto can't
 * take our output and it ends up on the phone speaker.
 *
 * When the captured signal is digital silence for a moment the output track is paused, so
 * we don't hold an active media stream (and don't waste power) while nothing plays.
 */
class CaptureEngine(private val context: Context, private val projection: MediaProjection) {

    @Volatile var running = false
        private set

    var sampleRate = 48000
        private set

    private var handle = 0L
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    /** elapsedRealtime when the input last had non-zero samples. */
    @Volatile var lastSignalAt = 0L
        private set
    @Volatile var outputPaused = true
        private set
    @Volatile var lastError: String? = null
        private set

    private val meterScratch = FloatArray(5)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller
    fun start(): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.takeIf { it in 44100..48000 } ?: 48000

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val recMin = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val trackMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)

        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()
            record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(recMin, BLOCK_FRAMES * 8) * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

            if (record?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord not initialised")
            handle = NativeDsp.create(sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up capture", e)
            lastError = e.message ?: e.javaClass.simpleName
            release()
            return false
        }

        running = true
        lastSignalAt = SystemClock.elapsedRealtime()
        thread = Thread({ loop() }, "CabinEQ-DSP").apply { start() }
        return true
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }  // unblocks a pending READ_BLOCKING read
        val t = thread
        t?.join(1000)
        thread = null
        if (t != null && t.isAlive) {
            // Never free the native engine under a thread that might still use it.
            Log.w(TAG, "DSP thread did not stop in time; leaking engine instead of risking a crash")
            return
        }
        release()
    }

    fun setParams(coeffs: DoubleArray, bandCount: Int, preampLin: Float, limiterThresholdLin: Float) {
        val h = handle
        // The Bluetooth guard only matters for the in-app player; captured audio follows the
        // route the capturing app was asked for.
        if (h != 0L) NativeDsp.setParams(h, coeffs, bandCount, preampLin, limiterThresholdLin, null, 0)
    }

    /** [inPeak, outPeak, minLimiterGain] since the last call, or null if not running. */
    fun readMeters(): FloatArray? {
        val h = handle
        if (h == 0L || !running) return null
        NativeDsp.readMeters(h, meterScratch)
        return meterScratch.copyOf()
    }

    /** Type of the device our output is currently routed to, or null if not playing. */
    fun routedDeviceType(): Int? {
        if (outputPaused) return null
        return track?.routedDevice?.type
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = record ?: return
        val out = track ?: return
        val buf = FloatArray(BLOCK_FRAMES * 2)
        // Capture and playback rates are equal here, so the engine only copies through.
        val processed = FloatArray(BLOCK_FRAMES * 2)
        var silentFrames = 0
        val pauseAfterFrames = sampleRate * SILENCE_PAUSE_MS / 1000
        try {
            rec.startRecording()
            outputPaused = true
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    lastError = "AudioRecord.read error $n"
                    break
                }
                if (n == 0) continue
                val frames = n / 2

                var peak = 0f
                for (i in 0 until n) peak = max(peak, abs(buf[i]))
                if (peak > 0f) {
                    silentFrames = 0
                    lastSignalAt = SystemClock.elapsedRealtime()
                    if (outputPaused) {
                        out.play()
                        outputPaused = false
                    }
                } else {
                    silentFrames += frames
                    if (!outputPaused && silentFrames > pauseAfterFrames) {
                        out.pause()
                        out.flush()
                        outputPaused = true
                    }
                }
                if (outputPaused) continue

                val written = NativeDsp.process(handle, buf, frames, processed, BLOCK_FRAMES)
                if (written > 0) out.write(processed, 0, written * 2, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DSP loop failed", e)
            lastError = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { rec.stop() }
            runCatching { out.stop() }
            outputPaused = true
            running = false
        }
    }

    private fun release() {
        runCatching { record?.release() }
        runCatching { track?.release() }
        record = null
        track = null
        val h = handle
        handle = 0L
        if (h != 0L) NativeDsp.destroy(h)
    }

    companion object {
        private const val TAG = "CabinEQ/Capture"
        private const val BLOCK_FRAMES = 480        // 10 ms at 48 kHz
        private const val SILENCE_PAUSE_MS = 400
    }
}
