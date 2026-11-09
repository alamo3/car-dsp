package dev.alamo3.cabineq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * What happens to our PCM between the player and the speaker, for the DSP screen.
 *
 * We cannot make Android hand the stream to the DAC untouched — the mixer resamples to the
 * device rate, applies the stream volume, and Samsung's Dolby/SoundAlive runs after that in
 * the HAL. What we *can* do is stop guessing about it: this reports the stages we can ask
 * about, so a fidelity question has an answer instead of a rumour.
 *
 * Everything here is read-only and cheap enough to run on the UI tick.
 */
object AudioPath {

    /** One line of the report; [warn] paints it as something that costs fidelity. */
    class Row(val label: String, val value: String, val warn: Boolean = false)

    class Facts(
        val codec: String?,
        val sourceRate: Int,
        val sourceBits: Int,
        /** Rate of the AudioTrack we feed — the DSP's own output rate. */
        val trackRate: Int?,
        /** Rate the platform mixes at, from PROPERTY_OUTPUT_SAMPLE_RATE. */
        val deviceRate: Int?,
        val framesPerBuffer: Int?,
        val volume: Int,
        val maxVolume: Int,
        /** getDirectPlaybackSupport for our format: null when the API/route can't say. */
        val direct: Int?,
        /** Supported mixer behaviours on the current device, API 34+ (e.g. bit-perfect). */
        val mixerBehaviours: List<String>,
        val spatialAvailable: Boolean?,
        val spatialEnabled: Boolean?,
        val route: String?,
        /** False when the player is idle: the rows then describe the device, not a stream. */
        val hasStream: Boolean,
    )

    fun collect(context: Context, player: FilePlayer?): Facts {
        val am = context.getSystemService(AudioManager::class.java)
        val deviceRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val frames = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        val device = player?.routedDevice()

        val format = runCatching {
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(trackRateOf(player) ?: deviceRate ?: 48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        }.getOrNull()
        val direct = if (Build.VERSION.SDK_INT >= 33 && format != null) runCatching {
            AudioManager.getDirectPlaybackSupport(format, ATTRIBUTES)
        }.getOrNull() else null

        val behaviours = if (Build.VERSION.SDK_INT >= 34 && device != null && am != null) {
            mixerBehaviours(am, device)
        } else emptyList()

        val spatial = if (Build.VERSION.SDK_INT >= 32) runCatching {
            am?.spatializer?.let { it.isAvailable to it.isEnabled }
        }.getOrNull() else null

        return Facts(
            codec = player?.sourceCodec,
            sourceRate = player?.sourceSampleRate ?: 0,
            sourceBits = player?.sourceBits ?: 0,
            trackRate = trackRateOf(player),
            deviceRate = deviceRate,
            framesPerBuffer = frames,
            volume = vol,
            maxVolume = maxVol,
            direct = direct,
            mixerBehaviours = behaviours,
            spatialAvailable = spatial?.first,
            spatialEnabled = spatial?.second,
            route = device?.label(),
            hasStream = player?.currentTrack != null || (player?.sourceSampleRate ?: 0) > 0,
        )
    }

    private fun trackRateOf(player: FilePlayer?): Int? =
        player?.outputSampleRate ?: player?.sourceSampleRate?.takeIf { it > 0 }

    fun rows(f: Facts): List<Row> = buildList {
        if (!f.hasStream) {
            add(Row("Player", "idle — only the device settings apply"))
        }
        val codec = f.codec?.takeIf { it.isNotBlank() }
        if (f.hasStream && codec != null && f.sourceRate > 0) {
            val bits = if (f.sourceBits > 0) " / ${f.sourceBits}-bit" else ""
            add(Row("Source", String.format(Locale.US, "%s %.1f kHz%s", codec, f.sourceRate / 1000.0, bits)))
        }
        val rate = f.trackRate ?: f.sourceRate
        if (f.hasStream && rate > 0) add(Row("Track", "PCM float · ${rate / 1000.0} kHz · stereo"))

        if (f.deviceRate != null) {
            val buf = f.framesPerBuffer?.let { " · $it frames/buffer" } ?: ""
            add(Row("Device", "${f.deviceRate / 1000.0} kHz$buf"))
        }
        if (f.hasStream && rate > 0 && f.deviceRate != null) {
            if (rate == f.deviceRate) {
                add(Row("Resample", "none — same rate"))
            } else {
                add(Row("Resampling", "${rate / 1000.0} → ${f.deviceRate / 1000.0} kHz, by the system mixer", warn = true))
            }
        }

        if (f.maxVolume > 0) {
            // The index, not getStreamVolumeDb: on this device that reports -0.1 dB at 6/15
            // while the mixer shows -25 dB on the track, so it would be worse than nothing.
            val text = "${f.volume}/${f.maxVolume} (${f.volume * 100 / f.maxVolume}%)"
            add(
                if (f.volume < f.maxVolume) {
                    Row("Volume", "$text — not at maximum; the mixer attenuates", warn = true)
                } else {
                    Row("Volume", "$text — at maximum")
                }
            )
        }

        f.direct?.takeIf { f.hasStream }?.let { d ->
            add(
                when (d) {
                    AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED ->
                        Row("Direct path", "no — this format is mixed", warn = true)
                    AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED ->
                        Row("Direct path", "offload available (compressed)")
                    AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED ->
                        Row("Direct path", "offload, gapless (compressed)")
                    AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED ->
                        Row("Direct path", "bitstream (compressed)")
                    else -> Row("Direct path", "code $d")
                }
            )
        }
        if (f.mixerBehaviours.isNotEmpty()) {
            add(Row("Mixer", f.mixerBehaviours.joinToString(" · ")))
        }

        if (f.spatialAvailable != null) {
            add(Row("Spatial", if (f.spatialEnabled == true) "on" else if (f.spatialAvailable) "available, off" else "not available"))
        }
        add(Row("Effects", "none attached by CabinEQ"))
        f.route?.let { add(Row("Route", it)) }
    }

    fun plain(f: Facts): String =
        rows(f).joinToString("\n") { "${it.label}: ${it.value}" } +
            "\n\n" + CAVEAT

    /**
     * The part no app can query: the vendor HAL. Samsung reports `mDsEnable=1` in its audio
     * HAL while Dolby is on, but that is invisible to the framework, so the user has to look.
     */
    private const val CAVEAT =
        "Vendor processing (Dolby Atmos, SoundAlive, Adapt Sound) runs after the mixer and is " +
            "invisible to apps — check Settings → Sounds and vibration → Sound quality and effects.\n" +
            "Bluetooth re-encodes the stream (SBC/AAC/LDAC), so it can never be sample-exact."

    private fun AudioDeviceInfo.label(): String {
        val kind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call)"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix (Android Auto)"
            AudioDeviceInfo.TYPE_BUS -> "bus (Android Auto)"
            else -> "device type $type"
        }
        val name = runCatching { productName?.toString() }.getOrNull()
        return if (name.isNullOrBlank() || name.equals(kind, true)) kind else "$kind · $name"
    }

    /** What mixer modes this output offers, e.g. "bit-perfect 48.0 kHz float". */
    @RequiresApi(34)
    private fun mixerBehaviours(am: AudioManager, device: AudioDeviceInfo): List<String> =
        runCatching { am.getSupportedMixerAttributes(device) }
            .getOrDefault(emptyList())
            .map { it.describe() }
            .distinct()

    /** "bit-perfect 48.0 kHz float" — how a host reports the mixer mode it offers. */
    @RequiresApi(34)
    private fun AudioMixerAttributes.describe(): String {
        val f = format
        val rate = if (f.sampleRate > 0) String.format(Locale.US, "%.1f kHz", f.sampleRate / 1000.0) else "? kHz"
        val enc = when (f.encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "float"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
            AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
            else -> "enc ${f.encoding}"
        }
        val behaviour = if (mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) "bit-perfect" else "default"
        return "$behaviour $rate $enc"
    }

    /** Mirrors the AudioTrack the player builds, so "direct" answers about the real stream. */
    private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
}
