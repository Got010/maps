package app.southbound.core.voice

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono 16-bit audio, ready for AudioTrack. */
class Pcm(val samples: ShortArray, val sampleRate: Int) {
    companion object {
        fun fromFloats(samples: FloatArray, sampleRate: Int): Pcm =
            Pcm(ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }, sampleRate)
    }
}

/**
 * Reads the 16-bit PCM WAV files Android's TextToSpeech.synthesizeToFile
 * writes. Stereo is mixed down to mono.
 */
object WavReader {

    fun read(file: File): Pcm = RandomAccessFile(file, "r").use { raf ->
        val bytes = ByteArray(raf.length().toInt())
        raf.readFully(bytes)
        parse(bytes)
    }

    fun parse(bytes: ByteArray): Pcm {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && chunkId(buf, 0) == "RIFF" && chunkId(buf, 8) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var channels = 1
        var sampleRate = 0
        var bitsPerSample = 16
        while (pos + 8 <= bytes.size) {
            val id = chunkId(buf, pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(buf.getShort(body).toInt() == 1) { "Only PCM WAV is supported" }
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bitsPerSample = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(bitsPerSample == 16 && sampleRate > 0) { "Only 16-bit WAV is supported" }
                    // Some engines leave the size at 0 or too big while streaming; trust the file.
                    val length = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                    val frames = length / (2 * channels)
                    val samples = ShortArray(frames) { frame ->
                        var sum = 0
                        for (c in 0 until channels) sum += buf.getShort(body + (frame * channels + c) * 2)
                        (sum / channels).toShort()
                    }
                    return Pcm(samples, sampleRate)
                }
            }
            pos = body + size + (size and 1)
        }
        error("WAV file has no data")
    }

    private fun chunkId(buf: ByteBuffer, at: Int): String =
        String(ByteArray(4) { buf.get(at + it) }, Charsets.US_ASCII)
}
