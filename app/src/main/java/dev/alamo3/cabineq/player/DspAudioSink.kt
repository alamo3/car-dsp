@file:OptIn(UnstableApi::class)

package dev.alamo3.cabineq.player

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.alamo3.cabineq.FilePlayer
import dev.alamo3.cabineq.NativeDsp
import dev.alamo3.cabineq.music.Pcm
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The native DSP as Media3's audio sink.
 *
 * A processor cannot do this job. Media3's [DefaultAudioSink] builds its pipeline in two
 * branches: with float output it inserts its own float converter and **never adds the custom
 * processor chain**, so a processor-based DSP is silently skipped; with integer output the chain
 * runs but everything is 16-bit. Owning the sink removes the choice — this sink runs the engine
 * in float, resamples to the rate the device mixes at, and hands [DefaultAudioSink] a float
 * buffer it has no reason to convert.
 *
 * [DefaultAudioSink] still does the hard part: the AudioTrack, its buffer, timestamps, drain and
 * gapless handling. This class only sees the decoded PCM on its way in.
 */
internal class DspAudioSink(private val bridge: FilePlayer.Bridge) : AudioSink {

    private val delegate: AudioSink = DefaultAudioSink.Builder(bridge.context)
        // Float output is what we feed it; with no processor chain there is nothing to skip.
        .setEnableFloatOutput(true)
        .setAudioProcessors(emptyArray<AudioProcessor>())
        .build()

    private val audioManager = bridge.context.getSystemService(AudioManager::class.java)

    // What the decoder hands us (per configure()).
    private var inRate = 0
    private var inEncoding = C.ENCODING_PCM_16BIT
    private var inChannels = 2

    // The engine, always at the rate the device mixes at.
    private var rate = 0
    /** The rate the engine is running at (what reaches the AudioTrack); null before configure. */
    @Volatile var outputRate: Int? = null
        private set
    private var handle = 0L
    private var appliedEq = -1
    private var inFloat = FloatArray(0)
    private var outFloat = FloatArray(0)
    private var outBytes: ByteBuffer = ByteBuffer.allocateDirect(0)

    private var listener: AudioSink.Listener? = null

    // ---------------------------------------------------------------- configuration

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        delegate.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean =
        delegate.supportsFormat(sinkFormat(format))

    override fun getFormatSupport(format: Format): Int =
        delegate.getFormatSupport(sinkFormat(format))

    /** What we will actually give the delegate: float, stereo, at the device's rate. */
    private fun sinkFormat(format: Format): Format {
        val target = bridge.targetRate(format.sampleRate)
        return format.buildUpon()
            .setSampleRate(target)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build()
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        inRate = format.sampleRate
        inEncoding = format.pcmEncoding
        inChannels = format.channelCount
        val target = bridge.targetRate(inRate)
        rate = target
        outputRate = target
        delegate.configure(sinkFormat(format), specifiedBufferSize, outputChannels)
        synchronized(bridge.dspLock) { ensureEngineLocked() }
    }

    /** Creates (or re-creates) the engine at [rate] and pushes the current EQ into it. */
    private fun ensureEngineLocked() {
        if (rate <= 0) return
        if (handle == 0L || engineRate != rate) {
            if (handle != 0L) NativeDsp.destroy(handle)
            handle = runCatching { NativeDsp.create(rate) }.getOrElse { e ->
                Log.e(TAG, "could not create the DSP engine at $rate Hz: ${e.message}")
                bridge.error("DSP unavailable")
                0L
            }
            engineRate = rate
            appliedEq = -1
            Log.i(TAG, if (inRate == rate) "engine at $rate Hz" else "engine at $rate Hz, resampling from $inRate Hz")
        }
        if (handle == 0L) return
        NativeDsp.setInputRate(handle, 0)          // drop history: a configure is a new stream
        NativeDsp.setInputRate(handle, inRate)
        applyEqLocked()
        NativeDsp.fadeIn(handle, rate * FADE_IN_MS / 1000)
    }

    private var engineRate = 0

    /** The native engine's handle, or 0. For meters and parameter pushes from the app. */
    val handleOrZero: Long get() = synchronized(bridge.dspLock) { handle }

    /** Pushes the current EQ if it changed (called when settings change). */
    fun applyEq() {
        synchronized(bridge.dspLock) { applyEqLocked() }
    }

    private fun applyEqLocked() {
        if (handle == 0L || appliedEq == bridge.eqVersion) return
        if (bridge.pushEq(handle, rate, bluetoothRoute())) appliedEq = bridge.eqVersion
    }

    /** After a route or setting change: push again even if the EQ itself did not move. */
    fun refreshRouteDependentParams() {
        synchronized(bridge.dspLock) {
            appliedEq = -1
            applyEqLocked()
        }
    }

    /**
     * Whether the sound is going to Bluetooth: asked of the framework for where media *would*
     * route (API 33+), not of what merely happens to be connected.
     */
    private fun bluetoothRoute(): Boolean = runCatching {
        val am = audioManager ?: return@runCatching false
        val devices = if (android.os.Build.VERSION.SDK_INT >= 33) {
            am.getAudioDevicesForAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)

    // ---------------------------------------------------------------- audio through the DSP

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAudioBytes: Int): Boolean {
        if (!buffer.hasRemaining()) return true
        val frameBytes = Pcm.bytesPerSample(inEncoding) * inChannels
        val frames = buffer.remaining() / frameBytes
        var out: ByteBuffer? = null
        if (frames > 0) {
            if (inFloat.size < frames * 2) inFloat = FloatArray(frames * 2)
            Pcm.toStereoFloat(buffer, inEncoding, inChannels, frames, inFloat)
            var produced = 0
            synchronized(bridge.dspLock) {
                if (handle != 0L) {
                    val cap = NativeDsp.maxFrames(handle, frames)
                    if (outFloat.size < cap * 2) outFloat = FloatArray(cap * 2)
                    produced = NativeDsp.process(handle, inFloat, frames, outFloat, cap)
                }
            }
            if (produced > 0) out = floatBuffer(produced)
        }
        buffer.position(buffer.limit())
        // Nothing came out (the resampler is filling, or the engine failed): consumed, no write.
        if (out == null) return true
        return delegate.handleBuffer(out, presentationTimeUs, 0)
    }

    /** Engine output as a direct float buffer the delegate can copy from. */
    private fun floatBuffer(frames: Int): ByteBuffer {
        val bytes = frames * 2 * 4
        if (outBytes.capacity() < bytes) outBytes = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        outBytes.clear()
        outBytes.asFloatBuffer().put(outFloat, 0, frames * 2)
        outBytes.limit(bytes)
        return outBytes
    }

    override fun playToEndOfStream() {
        var produced = 0
        synchronized(bridge.dspLock) {
            if (handle != 0L) {
                val cap = NativeDsp.maxFrames(handle, 1024)
                if (cap > 0) {
                    if (outFloat.size < cap * 2) outFloat = FloatArray(cap * 2)
                    produced = NativeDsp.flush(handle, outFloat, cap)
                }
            }
        }
        if (produced > 0) delegate.handleBuffer(floatBuffer(produced), 0, 0)
        delegate.playToEndOfStream()
    }

    override fun handleDiscontinuity() {
        synchronized(bridge.dspLock) {
            if (handle != 0L) {
                NativeDsp.setInputRate(handle, 0)
                NativeDsp.setInputRate(handle, inRate)
                NativeDsp.fadeIn(handle, rate * FADE_IN_MS / 1000)
            }
        }
        delegate.handleDiscontinuity()
    }

    override fun flush() {
        synchronized(bridge.dspLock) {
            if (handle != 0L) {
                NativeDsp.setInputRate(handle, 0)
                NativeDsp.setInputRate(handle, inRate)
                NativeDsp.fadeIn(handle, rate * FADE_IN_MS / 1000)
            }
        }
        delegate.flush()
    }

    override fun reset() {
        synchronized(bridge.dspLock) {
            if (handle != 0L) NativeDsp.destroy(handle)
            handle = 0L
            engineRate = 0
            appliedEq = -1
        }
        outputRate = null
        delegate.reset()
    }

    override fun release() {
        synchronized(bridge.dspLock) {
            if (handle != 0L) NativeDsp.destroy(handle)
            handle = 0L
            engineRate = 0
        }
        outputRate = null
        delegate.release()
    }

    // ---------------------------------------------------------------- everything else

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = delegate.getCurrentPositionUs(sourceEnded)
    override fun play() = delegate.play()
    override fun pause() = delegate.pause()
    override fun isEnded(): Boolean = delegate.isEnded
    override fun hasPendingData(): Boolean = delegate.hasPendingData()
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = delegate.setPlaybackParameters(playbackParameters)
    override fun getPlaybackParameters(): PlaybackParameters = delegate.playbackParameters
    override fun setSkipSilenceEnabled(enabled: Boolean) = delegate.setSkipSilenceEnabled(enabled)
    override fun getSkipSilenceEnabled(): Boolean = delegate.skipSilenceEnabled
    override fun setAudioAttributes(audioAttributes: AudioAttributes) = delegate.setAudioAttributes(audioAttributes)
    override fun getAudioAttributes(): AudioAttributes = delegate.audioAttributes ?: AudioAttributes.DEFAULT
    override fun setAudioSessionId(audioSessionId: Int) = delegate.setAudioSessionId(audioSessionId)
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = delegate.setAuxEffectInfo(auxEffectInfo)
    override fun getAudioTrackBufferSizeUs(): Long = delegate.audioTrackBufferSizeUs
    override fun enableTunnelingV21() = delegate.enableTunnelingV21()
    override fun disableTunneling() = delegate.disableTunneling()
    override fun setVolume(volume: Float) = delegate.setVolume(volume)

    private companion object {
        const val TAG = "CabinEQ/Dsp"
        const val FADE_IN_MS = 15
    }
}
