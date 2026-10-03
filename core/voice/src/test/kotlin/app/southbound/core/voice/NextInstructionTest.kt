package app.southbound.core.voice

import app.southbound.core.voice.NextInstruction.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextInstructionTest {

    // Valhalla's usual three: at the step's start, an early warning, and at the turn.
    private val step = listOf(1_200.0, 400.0, 60.0)

    @Test
    fun furthestPromptStillAhead() {
        assertEquals(Pick(0, 0), NextInstruction.pick(step, 1_300.0, null))
        assertEquals(Pick(0, 1), NextInstruction.pick(step, 1_100.0, null))
        assertEquals(Pick(0, 2), NextInstruction.pick(step, 300.0, null))
    }

    @Test
    fun thenTheFollowingStep() {
        assertEquals(Pick(1, 0), NextInstruction.pick(step, 50.0, listOf(800.0, 50.0)))
        assertEquals(Pick(1, 1), NextInstruction.pick(emptyList(), 10.0, listOf(30.0, 900.0)))
        assertNull(NextInstruction.pick(step, 50.0, null))
    }
}
