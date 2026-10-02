package app.southbound.core.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavReaderTest {

    private fun wav(channels: Int, rate: Int, samples: ShortArray, dataSize: Int? = null, extraChunk: Boolean = false): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun short(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); int(0); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(channels); int(rate); int(rate * channels * 2); short(channels * 2); short(16)
        if (extraChunk) { out.write("LIST".toByteArray()); int(3); out.write(byteArrayOf(1, 2, 3, 0)) }
        out.write("data".toByteArray()); int(dataSize ?: data.capacity()); out.write(data.array())
        return out.toByteArray()
    }

    @Test
    fun mono() {
        val pcm = WavReader.parse(wav(1, 22050, shortArrayOf(1, -2, 300)))
        assertEquals(22050, pcm.sampleRate)
        assertArrayEquals(shortArrayOf(1, -2, 300), pcm.samples)
    }

    @Test
    fun stereoIsMixedDown() {
        val pcm = WavReader.parse(wav(2, 16000, shortArrayOf(100, 300, -50, -150)))
        assertArrayEquals(shortArrayOf(200, -100), pcm.samples)
    }

    @Test
    fun skipsOtherChunksAndOddPadding() {
        val pcm = WavReader.parse(wav(1, 8000, shortArrayOf(7, 8), extraChunk = true))
        assertArrayEquals(shortArrayOf(7, 8), pcm.samples)
    }

    @Test
    fun streamingSizeOfZeroReadsToEnd() {
        val pcm = WavReader.parse(wav(1, 8000, shortArrayOf(5, 6, 7), dataSize = 0))
        assertArrayEquals(shortArrayOf(5, 6, 7), pcm.samples)
    }

    @Test
    fun floatsAreClamped() {
        val pcm = Pcm.fromFloats(floatArrayOf(0f, 1f, -2f), 16000)
        assertArrayEquals(shortArrayOf(0, Short.MAX_VALUE, (-Short.MAX_VALUE).toShort()), pcm.samples)
    }
}
