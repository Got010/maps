package app.southbound.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChimeTest {

    @Test
    fun chimesAreShortQuietAndDistinct() {
        for (rate in listOf(16_000, 22_050, 24_000)) {
            val turn = Chime.render(ChimeKind.INSTRUCTION, rate)
            val alert = Chime.render(ChimeKind.ALERT, rate)
            assertEquals(270 * rate / 1000, turn.size, 2)
            assertTrue(alert.size in rate / 10..rate / 2)
            assertNotEquals(turn.size, alert.size)
            assertTrue(turn.maxOf { abs(it.toInt()) } < Short.MAX_VALUE / 2)
            // Fades in and out: no click at either end.
            assertTrue(abs(turn.first().toInt()) < 200 && abs(turn.last().toInt()) < 200)
        }
    }

    @Test
    fun chimeGoesBeforeTheSpeech() {
        val speech = Pcm(ShortArray(1000) { 7 }, 16_000)
        val out = Chime.before(ChimeKind.INSTRUCTION, speech)
        val lead = Chime.render(ChimeKind.INSTRUCTION, 16_000).size + Chime.GAP_MS * 16
        assertEquals(lead + 1000, out.samples.size)
        assertEquals(16_000, out.sampleRate)
        assertEquals(0, out.samples[lead - 1].toInt())
        assertTrue(out.samples.drop(lead).all { it.toInt() == 7 })
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) = assertTrue("$actual vs $expected", abs(expected - actual) <= delta)
}
