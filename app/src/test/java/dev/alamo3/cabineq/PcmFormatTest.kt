package dev.alamo3.cabineq

import dev.alamo3.cabineq.music.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The decoder's declaration about its own samples, checked against the samples.
 *
 * A device that reports float while sending 16-bit makes every word noise; read as float, the
 * values are mostly denormal with occasional huge ones, which a limiter holds down as constant
 * distortion. These cases build each word size and ask the detector to tell them apart, including
 * two that must *not* be "corrected".
 */
class PcmFormatTest {

    private val frames = 2_048
    private fun sine(i: Int) = Math.sin(2.0 * Math.PI * 440.0 * i / 44_100.0)

    private fun stereo(put: (ByteBuffer, Double) -> Unit): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = 0.5 * sine(i)
            put(buffer, v)
            put(buffer, v)
        }
        buffer.flip()
        return buffer
    }

    private fun float(): ByteBuffer = stereo { b, v -> b.putFloat(v.toFloat()) }

    private fun int16(): ByteBuffer = stereo { b, v -> b.putShort((v * 32_767).toInt().toShort()) }

    private fun int24(): ByteBuffer = stereo { b, v ->
        val s = (v * 8_388_607).toInt()
        b.put((s and 0xFF).toByte())
        b.put(((s shr 8) and 0xFF).toByte())
        b.put(((s shr 16) and 0xFF).toByte())
    }

    private fun silence(): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        return b
    }

    @Test
    fun floatDataIsTakenAtItsWord() {
        val reading = Pcm.detect(float(), Pcm.ENCODING_FLOAT, 2)
        assertEquals(Pcm.ENCODING_FLOAT, reading.encoding)
        assertFalse("nothing to correct here", reading.switched)
    }

    @Test
    fun int16DataDeclaredAsFloatIsRecognised() {
        val reading = Pcm.detect(int16(), Pcm.ENCODING_FLOAT, 2)
        assertEquals(Pcm.ENCODING_16BIT, reading.encoding)
        assertTrue(reading.note, reading.switched)
    }

    @Test
    fun int24DataDeclaredAsFloatIsNotMistakenForInt16() {
        val reading = Pcm.detect(int24(), Pcm.ENCODING_FLOAT, 2)
        assertEquals("the rough read must lose to the smooth one", Pcm.ENCODING_24BIT_PACKED, reading.encoding)
        assertTrue(reading.note, reading.switched)
    }

    @Test
    fun floatDataDeclaredAsInt16IsRecognised() {
        val reading = Pcm.detect(float(), Pcm.ENCODING_16BIT, 2)
        assertEquals(reading.note, Pcm.ENCODING_FLOAT, reading.encoding)
        assertTrue(reading.note, reading.switched)
    }

    @Test
    fun silenceIsLeftAlone() {
        val reading = Pcm.detect(silence(), Pcm.ENCODING_FLOAT, 2)
        assertEquals(Pcm.ENCODING_FLOAT, reading.encoding)
        assertFalse("a quiet passage is not a broken decoder", reading.switched)
    }

    @Test
    fun aShortBufferIsNotJudged() {
        val b = ByteBuffer.allocateDirect(2 * 8).order(ByteOrder.LITTLE_ENDIAN)
        val reading = Pcm.detect(b, Pcm.ENCODING_FLOAT, 2)
        assertEquals(Pcm.ENCODING_FLOAT, reading.encoding)
        assertFalse(reading.switched)
    }

    @Test
    fun everyWordSizeConvertsToTheSameLevel() {
        val out = FloatArray(frames * 2)
        for ((buffer, encoding) in listOf(float() to Pcm.ENCODING_FLOAT, int16() to Pcm.ENCODING_16BIT, int24() to Pcm.ENCODING_24BIT_PACKED)) {
            Pcm.toStereoFloat(buffer, encoding, 2, frames, out)
            var peak = 0.0
            for (i in 0 until frames * 2) peak = maxOf(peak, Math.abs(out[i].toDouble()))
            assertEquals("peak of a 0.5 sine read as ${Pcm.name(encoding)}", 0.5, peak, 0.01)
        }
    }
}
