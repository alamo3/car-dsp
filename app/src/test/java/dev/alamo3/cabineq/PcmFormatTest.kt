package dev.alamo3.cabineq

import dev.alamo3.cabineq.music.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The decoder's declaration about its own samples, checked against the samples.
 *
 * A device that reports float while sending 16-bit makes every word noise; read as float, the
 * values are mostly denormal with occasional huge ones, which a limiter holds down as constant
 * distortion. The check has to be conservative: a merely unusual buffer (a quiet intro, a
 * percussive start) must be left alone, because a wrong "correction" distorts a stream that was
 * fine — which is exactly how file-backed songs broke.
 */
class PcmFormatTest {

    private val frames = 2_048
    private fun sine(i: Int) = Math.sin(2.0 * Math.PI * 440.0 * i / 44_100.0)

    private fun stereo(count: Int = frames, put: (ByteBuffer, Double) -> Unit): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val v = 0.5 * sine(i)
            put(buffer, v)
            put(buffer, v)
        }
        buffer.flip()
        return buffer
    }

    private fun float(count: Int = frames): ByteBuffer = stereo(count) { b, v -> b.putFloat(v.toFloat()) }

    private fun int16(count: Int = frames): ByteBuffer =
        stereo(count) { b, v -> b.putShort((v * 32_767).toInt().toShort()) }

    private fun int24(count: Int = frames): ByteBuffer = stereo(count) { b, v ->
        val s = (v * 8_388_607).toInt()
        b.put((s and 0xFF).toByte())
        b.put(((s shr 8) and 0xFF).toByte())
        b.put(((s shr 16) and 0xFF).toByte())
    }

    private fun silence(): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)

    private fun switchedWordSize(buf: ByteBuffer): Int? =
        if (Pcm.floatIsImpossible(buf, 2)) Pcm.alternativeToFloat(buf, 2).encoding else null

    @Test
    fun floatDataIsReadAsFloat() {
        assertFalse("a normal float stream must never be reinterpreted", Pcm.floatIsImpossible(float(), 2))
        assertNull(switchedWordSize(float()))
    }

    @Test
    fun int16DataReadAsFloatIsCaughtAndNamed() {
        assertTrue(Pcm.floatIsImpossible(int16(), 2))
        assertEquals(Pcm.ENCODING_16BIT, switchedWordSize(int16()))
    }

    @Test
    fun int24DataReadAsFloatIsNotMistakenForInt16() {
        assertEquals("the smooth reading must win", Pcm.ENCODING_24BIT_PACKED, switchedWordSize(int24()))
    }

    @Test
    fun silenceIsLeftAlone() {
        assertFalse("a quiet passage is not a broken decoder", Pcm.floatIsImpossible(silence(), 2))
    }

    @Test
    fun aBufferTooShortToJudgeIsLeftAlone() {
        val b = ByteBuffer.allocateDirect(2 * 8).order(ByteOrder.LITTLE_ENDIAN)
        assertFalse(Pcm.floatIsImpossible(b, 2))
    }

    @Test
    fun aReadingLearnedForOneSongDoesNotStickToAnother() {
        // 16-bit data: float never reads as audio, so the correction stands for as long as the
        // data needs it.
        assertFalse(Pcm.floatReadsAsAudio(int16(), 2))
        // Genuine float: the declared interpretation is right again, so a correction learned for
        // the previous song must not survive into this one.
        assertTrue(Pcm.floatReadsAsAudio(float(), 2))
        // Silence proves nothing either way.
        assertFalse(Pcm.floatReadsAsAudio(silence(), 2))
    }

    @Test
    fun aShortWindowIsEnough() {
        // The check runs on every buffer, not only when the format is configured, so it must work
        // on a few hundred frames.
        assertTrue(Pcm.floatIsImpossible(int16(Pcm.CHECK_FRAMES), 2))
    }

    @Test
    fun aQuietFloatStartIsNotMistakenForGarbage() {
        // The buffer a file-backed song starts with: low level, but real samples.
        val quiet = stereo { b, v -> b.putFloat((v * 0.001).toFloat()) }
        assertFalse(Pcm.floatIsImpossible(quiet, 2))
    }

    @Test
    fun aBufferThatClaimsBigEndianIsStillReadAsLittleEndian() {
        // MediaCodec buffers carry the JVM's default byte order on some devices. Trusting it
        // reads every sample backwards — loud garbage, and the distortion that took a while to
        // place — so the reader sets the order itself. Both word sizes are checked.
        for ((buffer, encoding) in listOf(int16() to Pcm.ENCODING_16BIT, float() to Pcm.ENCODING_FLOAT)) {
            buffer.order(ByteOrder.BIG_ENDIAN)
            val out = FloatArray(frames * 2)
            Pcm.toStereoFloat(buffer, encoding, 2, frames, out)
            var peak = 0.0
            for (i in 0 until frames * 2) peak = maxOf(peak, Math.abs(out[i].toDouble()))
            assertEquals("a 0.5 sine marked big-endian, read as ${Pcm.name(encoding)}", 0.5, peak, 0.01)
        }
    }

    @Test
    fun everyWordSizeConvertsToTheSameLevel() {
        val out = FloatArray(frames * 2)
        for ((buffer, encoding) in listOf(
            float() to Pcm.ENCODING_FLOAT,
            int16() to Pcm.ENCODING_16BIT,
            int24() to Pcm.ENCODING_24BIT_PACKED,
        )) {
            Pcm.toStereoFloat(buffer, encoding, 2, frames, out)
            var peak = 0.0
            for (i in 0 until frames * 2) peak = maxOf(peak, Math.abs(out[i].toDouble()))
            assertEquals("peak of a 0.5 sine read as ${Pcm.name(encoding)}", 0.5, peak, 0.01)
        }
    }
}
