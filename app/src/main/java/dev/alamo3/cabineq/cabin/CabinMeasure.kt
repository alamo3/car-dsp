package dev.alamo3.cabineq.cabin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log

/**
 * Measures the response of the car: play a sweep, record it with the phone's microphone, and
 * compare what came back with what was sent.
 *
 * What the result contains is everything that multiplies between us and the mic — the car's
 * head-unit EQ and loudness, the speakers, the cabin, and the microphone itself. So it is only
 * meaningful as a *relative* curve (useful for correction) and only trustworthy over the band
 * where the phone's mic is roughly flat: 100 Hz – 8 kHz. Everything above and below that is
 * shown but must not be used to design a correction.
 *
 * Magnitude only: an EQ does not need phase, so there is no impulse-response deconvolution and
 * no need to align the recording with the sweep.
 */
object CabinMeasure {

    private const val TAG = "CabinEQ/Cabin"
    const val SAMPLE_RATE = 48000

    /** Microphone response we are willing to trust; correction outside this is guesswork. */
    const val TRUSTED_LOW_HZ = 100.0
    const val TRUSTED_HIGH_HZ = 8000.0

    class Point(val hz: Double, val db: Double)

    class Result(
        val points: List<Point>,
        /** Seconds of recording that contained the sweep. */
        val sweepSeconds: Double,
        /** Signal-to-noise: sweep level above the noise floor, in dB. */
        val snrDb: Double,
    ) {
        /** Peaks and dips inside the trusted band, worst first — what a correction would target. */
        fun extremes(count: Int = 4): List<Point> {
            val band = points.filter { it.hz in TRUSTED_LOW_HZ..TRUSTED_HIGH_HZ }
            if (band.isEmpty()) return emptyList()
            val level = band.map { it.db }.sorted()
            val median = level[level.size / 2]
            return band.sortedByDescending { kotlin.math.abs(it.db - median) }.take(count)
        }
    }

    /** Log sweep from [f1] to [f2]. Amplitude 0.1 is -20 dBFS: loud enough to measure, safe. */
    fun sweep(seconds: Double = 10.0, f1: Double = 20.0, f2: Double = 20000.0, amp: Float = 0.1f): FloatArray {
        val n = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val k = Math.log(f2 / f1)
        for (i in 0 until n) {
            val t = i.toDouble() / n
            // Phase of an exponential sweep, integrated numerically is not needed: closed form.
            val phase = 2.0 * Math.PI * f1 * seconds / k * (Math.exp(t * k) - 1.0)
            out[i] = (amp * Math.sin(phase)).toFloat()
        }
        return out
    }

    /**
     * Plays the sweep and records it. Blocking: call from a background thread. Returns null when
     * the microphone or the output could not be opened.
     */
    fun measure(
        context: Context,
        seconds: Double = 10.0,
        onProgress: (stage: String, fraction: Float) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result? {
        val sweep = sweep(seconds)
        val leadMs = 700          // recording starts first: the sweep must not be clipped
        val tailMs = 900
        val totalMs = leadMs + (seconds * 1000).toInt() + tailMs
        val totalFrames = totalMs * SAMPLE_RATE / 1000

        val record = openRecord() ?: return null
        val track = openTrack() ?: run {
            runCatching { record.release() }
            return null
        }
        // Measurement must not be shaped by the phone's own input processing.
        val agc = runCatching { AutomaticGainControl.create(record.audioSessionId) }.getOrNull()
        val ns = runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()
        runCatching { agc?.enabled = false }
        runCatching { ns?.enabled = false }

        val captured = FloatArray(totalFrames)
        try {
            onProgress("Recording", 0f)
            record.startRecording()
            track.play()
            val started = SystemClock.elapsedRealtime()
            // Play the sweep after the lead-in, while recording in parallel.
            val playThread = Thread {
                SystemClock.sleep(leadMs.toLong())
                val step = 960
                var i = 0
                while (i < sweep.size && !isCancelled()) {
                    val n = minOf(step, sweep.size - i)
                    track.write(sweep, i, n, AudioTrack.WRITE_BLOCKING)
                    i += n
                }
            }.apply { isDaemon = true; start() }

            var read = 0
            while (read < totalFrames && !isCancelled()) {
                val n = record.read(captured, read, minOf(4096, totalFrames - read), AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                read += n
                val elapsed = SystemClock.elapsedRealtime() - started
                onProgress("Recording", (elapsed.toFloat() / totalMs).coerceIn(0f, 1f))
            }
            playThread.join(2000)
            if (read < SAMPLE_RATE || isCancelled()) return null
            val frames = read
            onProgress("Analysing", 0.9f)

            // Only the window that contains the sweep matters; the lead-in is pure noise.
            val from = (leadMs * SAMPLE_RATE / 1000)
            val to = minOf(frames, from + sweep.size + tailMs * SAMPLE_RATE / 1000)
            val n = nextPowerOfTwo(maxOf(sweep.size, to - from))
            val reX = DoubleArray(n)
            val imX = DoubleArray(n)
            val reY = DoubleArray(n)
            val imY = DoubleArray(n)
            for (i in sweep.indices) {
                if (i < n) reX[i] = sweep[i].toDouble()
            }
            for (i in 0 until minOf(n, to - from)) {
                reY[i] = captured[from + i].toDouble()
            }
            fft(reX, imX)
            fft(reY, imY)

            val binHz = SAMPLE_RATE.toDouble() / n
            val transfer = DoubleArray(n / 2)
            for (b in 1 until n / 2) {
                val mx = Math.hypot(reX[b], imX[b])
                val my = Math.hypot(reY[b], imY[b])
                transfer[b] = if (mx > 1e-9) my / mx else 0.0
            }
            val points = smooth(transfer, binHz)
            val snr = snrDb(captured, from, sweep.size)
            Log.i(TAG, "measured ${points.size} bands, snr %.1f dB".format(java.util.Locale.US, snr))
            onProgress("Done", 1f)
            return Result(points, seconds, snr)
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { track.stop() }
            runCatching { track.release() }
            runCatching { agc?.release() }
            runCatching { ns?.release() }
        }
    }

    // ---------------------------------------------------------------------------- capture

    @android.annotation.SuppressLint("MissingPermission")   // caller checks RECORD_AUDIO
    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) return null
        // UNPROCESSED bypasses the phone's voice processing; not every device offers it.
        for (source in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            val rec = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE / 10 * 4))
                    .build()
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            runCatching { rec.release() }
        }
        return null
    }

    private fun openTrack(): AudioTrack? = runCatching {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE / 10 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrNull()?.takeIf { it.state == AudioTrack.STATE_INITIALIZED } ?: run {
        Log.w(TAG, "could not open an output track")
        null
    }

    /** Sweep level versus the recording's noise floor, before the sweep starts. */
    private fun snrDb(captured: FloatArray, sweepStart: Int, sweepFrames: Int): Double {
        val noiseTo = minOf(sweepStart - 1, captured.size)
        if (noiseTo < 4800) return 0.0
        var noise = 0.0
        for (i in 0 until noiseTo) noise += captured[i] * captured[i]
        noise /= noiseTo
        var signal = 0.0
        var count = 0
        val from = sweepStart + sweepFrames / 4
        val to = minOf(captured.size, from + SAMPLE_RATE / 2)
        for (i in from until to) {
            signal += captured[i] * captured[i]
            count++
        }
        if (count == 0) return 0.0
        signal /= count
        return 10 * Math.log10((signal + 1e-12) / (noise + 1e-12))
    }

    // -------------------------------------------------------------------------- analysis

    /** In-place iterative radix-2 FFT. [n] must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang)
            val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k]
                    val ai = im[i + k]
                    val br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ar + br
                    im[i + k] = ai + bi
                    re[i + k + len / 2] = ar - br
                    im[i + k + len / 2] = ai - bi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 1/6-octave power averaging, normalised to the median so the curve is relative. */
    private fun smooth(transfer: DoubleArray, binHz: Double, pointsPerOctave: Int = 6): List<Point> {
        val out = ArrayList<Point>()
        var f = 20.0
        val top = SAMPLE_RATE / 2.4
        while (f < top) {
            val lo = f * Math.pow(2.0, -0.5 / pointsPerOctave)
            val hi = f * Math.pow(2.0, 0.5 / pointsPerOctave)
            val b0 = maxOf(1, (lo / binHz).toInt())
            val b1 = minOf(transfer.size - 1, (hi / binHz).toInt())
            if (b1 >= b0) {
                var power = 0.0
                for (b in b0..b1) power += transfer[b] * transfer[b]
                power /= (b1 - b0 + 1)
                if (power > 0) out.add(Point(f, 10 * Math.log10(power)))
            }
            f *= Math.pow(2.0, 1.0 / pointsPerOctave)
        }
        // Relative curve: the median inside the trusted band becomes 0 dB.
        val band = out.filter { it.hz in TRUSTED_LOW_HZ..TRUSTED_HIGH_HZ }.map { it.db }.sorted()
        if (band.isNotEmpty()) {
            val median = band[band.size / 2]
            return out.map { Point(it.hz, it.db - median) }
        }
        return out
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }
}
