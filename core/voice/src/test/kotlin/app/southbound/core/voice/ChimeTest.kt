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
    fun speedBeepIsADoubleBeepThatStandsOnItsOwn() {
        for (rate in listOf(16_000, 24_000)) {
            val beep = Chime.render(ChimeKind.SPEED_BEEP, rate)
            // Two 120 ms beeps with a 90 ms gap: 330 ms, ending on the second beep.
            assertEquals(330 * rate / 1000, beep.size, 2)
            // Same 330 ms as the alert, so tell them apart by rhythm: in the alert's
            // first pause (70-120 ms) the alert is silent while the long beep still sounds.
            val alert = Chime.render(ChimeKind.ALERT, rate)
            val alertPause = (80 * rate / 1000) until (110 * rate / 1000)
            assertTrue(alert.slice(alertPause).all { it.toInt() == 0 })
            assertTrue(beep.slice(alertPause).maxOf { abs(it.toInt()) } > Short.MAX_VALUE / 3)
            // Audible alone over road noise, but never clipping.
            val peak = beep.maxOf { abs(it.toInt()) }
            assertTrue(peak > Short.MAX_VALUE / 3 && peak < Short.MAX_VALUE * 0.8)
            // Silent in the middle: the gap between the two beeps.
            val gapStart = 130 * rate / 1000
            assertTrue(beep.slice(gapStart until gapStart + 60 * rate / 1000).all { it.toInt() == 0 })
            // Fades in and out: no click at either end.
            assertTrue(abs(beep.first().toInt()) < 200 && abs(beep.last().toInt()) < 200)
        }
        val only = Chime.only(ChimeKind.SPEED_BEEP, 24_000)
        assertEquals(24_000, only.sampleRate)
        assertEquals(Chime.render(ChimeKind.SPEED_BEEP, 24_000).size, only.samples.size)
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
