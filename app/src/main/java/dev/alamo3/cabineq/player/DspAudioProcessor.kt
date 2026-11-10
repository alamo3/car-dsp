@file:OptIn(UnstableApi::class)

package dev.alamo3.cabineq.player

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.alamo3.cabineq.FilePlayer
import dev.alamo3.cabineq.NativeDsp
import dev.alamo3.cabineq.music.Pcm
import java.nio.ByteBuffer

/**
 * The native DSP engine as a Media3 audio processor.
 *
 * Media3 hands this the decoded PCM just before the output; it runs exactly what the app's own
 * player runs — [Pcm.toStereoFloat] into [NativeDsp.process], configured by the same
 * [FilePlayer.Bridge.pushEq] — and hands back stereo float at the rate the device mixes at. The
 * engine resamples, equalises and limits; nothing of Media3's touches the audio after it.
 *
 * Called on Media3's playback thread. The engine handle is guarded by the player's DSP lock,
 * because the EQ is pushed from the service thread while audio is running.
 */
internal class DspAudioProcessor(private val bridge: FilePlayer.Bridge) : BaseAudioProcessor() {

    /** The native engine, or 0. Guarded by [FilePlayer.Bridge.dspLock]. */
    var handle = 0L
        private set
    /** The rate the engine was created for (what goes to the AudioTrack). */
    @Volatile var outputRate: Int? = null
        private set

    private var engineRate = 0
    private var appliedEq = -1
    private var inFloat = FloatArray(0)
    /** Bytes of an incomplete frame, carried to the next input buffer. */
    private var carry = ByteArray(0)
    private var outFloat = FloatArray(0)
    private val audioManager = bridge.context.getSystemService(AudioManager::class.java)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        val known = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT ||
            encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_32BIT ||
            encoding == C.ENCODING_PCM_8BIT
        if (!known || inputAudioFormat.channelCount < 1 || inputAudioFormat.sampleRate <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Emit at the rate the device mixes at (when that setting is on), as the other player
        // does, so the Android mixer has nothing left to resample.
        val rate = bridge.targetRate(inputAudioFormat.sampleRate)
        return AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)
    }

    /** A new stream, a seek, or a new format: make sure the engine matches, and start from silence. */
    override fun onFlush() {
        val inRate = inputAudioFormat.sampleRate
        val outRate = outputAudioFormat.sampleRate
        if (inRate <= 0 || outRate <= 0) return
        bridge.seedEq()
        synchronized(bridge.dspLock) {
            if (handle == 0L || engineRate != outRate) {
                if (handle != 0L) NativeDsp.destroy(handle)
                handle = runCatching { NativeDsp.create(outRate) }.getOrElse { e ->
                    // Without an engine the processor can only output silence; say so instead of
                    // playing a silent song and leaving the listener to guess.
                    Log.e(TAG, "could not create the DSP engine at $outRate Hz: ${e.message}")
                    bridge.error("DSP unavailable")
                    0L
                }
                engineRate = outRate
                appliedEq = -1
                Log.i(TAG, if (inRate == outRate) "engine at $outRate Hz, no resampling" else "engine at $outRate Hz, resampling from $inRate Hz")
            }
            if (handle == 0L) return@synchronized
            // A flush is a seek or a new song: drop the resampler's history, or its first frames
            // are a blend of the audio from before the jump. 0 disables it, the real rate resets it.
            NativeDsp.setInputRate(handle, 0)
            NativeDsp.setInputRate(handle, inRate)
            applyEqLocked()
            // Never start mid-waveform at full amplitude: that step is a click.
            NativeDsp.fadeIn(handle, outRate * FADE_IN_MS / 1000)
        }
        outputRate = outRate
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frameBytes = Pcm.bytesPerSample(format.encoding) * format.channelCount
        // Media3 is documented to hand over whole frames, but a stray partial frame at the end of
        // a buffer used to be dropped silently; keep the bytes and prepend them next time.
        var source: ByteBuffer = inputBuffer
        if (carry.isNotEmpty()) {
            val joined = ByteBuffer.allocate(carry.size + inputBuffer.remaining())
            joined.put(carry)
            joined.put(inputBuffer.duplicate())
            joined.flip()
            source = joined
        }
        val frames = source.remaining() / frameBytes
        val consumed = frames * frameBytes
        if (frames > 0) {
            if (inFloat.size < frames * 2) inFloat = FloatArray(frames * 2)
            Pcm.toStereoFloat(source, format.encoding, format.channelCount, frames, inFloat)
            var noEngine = false
            val produced = synchronized(bridge.dspLock) {
                if (handle == 0L) {
                    noEngine = true
                    0
                } else {
                    val cap = NativeDsp.maxFrames(handle, frames)
                    if (outFloat.size < cap * 2) outFloat = FloatArray(cap * 2)
                    NativeDsp.process(handle, inFloat, frames, outFloat, cap)
                }
            }
            if (noEngine) logSilenceOnce()
            emit(produced)
        }
        source.position(source.position() + consumed)
        val leftover = source.remaining()
        carry = if (leftover > 0) ByteArray(leftover).also { source.get(it) } else ByteArray(0)
        inputBuffer.position(inputBuffer.limit())
    }

    /** Says it once rather than per buffer: the engine failed, and the output is silence. */
    private var silenceLogged = false
    private fun logSilenceOnce() {
        if (silenceLogged) return
        silenceLogged = true
        Log.e(TAG, "no DSP engine: audio is being dropped while this song plays")
    }

    /** End of the stream: push the resampler's look-ahead through, or the last few ms are lost. */
    override fun onQueueEndOfStream() {
        val produced = synchronized(bridge.dspLock) {
            if (handle == 0L) 0 else {
                val cap = NativeDsp.maxFrames(handle, 1024)
                if (cap <= 0) 0 else {
                    if (outFloat.size < cap * 2) outFloat = FloatArray(cap * 2)
                    NativeDsp.flush(handle, outFloat, cap)
                }
            }
        }
        emit(produced)
    }

    private fun emit(frames: Int) {
        if (frames <= 0) return
        val bytes = frames * 2 * 4
        val out = replaceOutputBuffer(bytes)
        out.asFloatBuffer().put(outFloat, 0, frames * 2)
        out.limit(bytes)
    }

    override fun onReset() {
        carry = ByteArray(0)
        silenceLogged = false
        synchronized(bridge.dspLock) {
            if (handle != 0L) NativeDsp.destroy(handle)
            handle = 0L
            engineRate = 0
            appliedEq = -1
        }
        outputRate = null
    }

    /** Pushes the current EQ if it changed. Call under the DSP lock. */
    fun applyEqLocked() {
        if (handle == 0L || appliedEq == bridge.eqVersion) return
        if (bridge.pushEq(handle, engineRate, bluetoothRoute())) appliedEq = bridge.eqVersion
    }

    /** After a route or setting change: push again even if the EQ itself did not move. */
    fun refreshRouteDependentParams() {
        synchronized(bridge.dspLock) {
            appliedEq = -1
            applyEqLocked()
        }
    }

    /**
     * Whether the sound is going to Bluetooth. Media3 does not expose the AudioTrack's routed
     * device, so this asks the framework where media *would* go (API 33+); before that, all it
     * can do is look at what is connected — which is wrong when a Bluetooth device is paired but
     * the audio is going to a USB or AUX unit, and would have low-passed that for nothing.
     */
    private fun bluetoothRoute(): Boolean = runCatching {
        val am = audioManager ?: return@runCatching false
        val devices = if (android.os.Build.VERSION.SDK_INT >= 33) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            am.getAudioDevicesForAttributes(attrs)
        } else {
            // An array here, a list above: unify, or the result is just `Any`.
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "CabinEQ/Dsp"
        const val FADE_IN_MS = 15
    }
}
