package app.southbound.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechQueueTest {

    private fun item(text: String, kind: UtteranceKind = UtteranceKind.INSTRUCTION, at: Long = 0, id: String? = null) =
        SpeechQueue.Item(GuidanceAction.Speak(text), kind, at, id)

    private val always: (String) -> Boolean = { true }

    @Test
    fun aTurnPromptWaitsForTheSentenceBeforeIt() {
        val q = SpeechQueue()
        assertFalse(q.offer(item("Turn left.")))
        assertEquals("Turn left.", q.next(0, always)?.action?.text)
        // A camera alert arrives mid-sentence: no cut-off.
        assertFalse(q.offer(item("Speed camera ahead.", UtteranceKind.HAZARD_ALERT)))
        assertEquals("Turn left.", q.playing?.action?.text)
        q.done()
        assertEquals("Speed camera ahead.", q.next(10, always)?.action?.text)
    }

    @Test
    fun rerouteCutsInAndDropsOldPrompts() {
        val q = SpeechQueue()
        q.offer(item("Turn left."))
        q.next(0, always)
        q.offer(item("Then keep right."))
        q.offer(item("Speed camera ahead.", UtteranceKind.HAZARD_ALERT))
        assertTrue(q.offer(item("Rerouting.", UtteranceKind.REROUTE)))
        q.done()
        assertEquals("Rerouting.", q.next(0, always)?.action?.text)
        q.done()
        // The alert survives; the old turn prompt doesn't.
        assertEquals("Speed camera ahead.", q.next(0, always)?.action?.text)
        q.done()
        assertNull(q.next(0, always))
    }

    @Test
    fun closureAlertCutsIn() {
        val q = SpeechQueue()
        q.offer(item("Turn left."))
        q.next(0, always)
        assertTrue(q.offer(item("Road closed ahead.", UtteranceKind.TRAFFIC_ALERT)))
    }

    @Test
    fun nothingCutsTheTestVoice() {
        val q = SpeechQueue()
        q.offer(item("This is Southbound.", UtteranceKind.TEST))
        q.next(0, always)
        assertFalse(q.offer(item("Rerouting.", UtteranceKind.REROUTE)))
    }

    @Test
    fun newerTurnPromptReplacesAWaitingOne() {
        val q = SpeechQueue()
        q.offer(item("Arrival soon.", UtteranceKind.ARRIVAL))
        q.next(0, always)
        q.offer(item("In a quarter mile, turn left."))
        q.offer(item("Turn left."))
        q.done()
        assertEquals("Turn left.", q.next(0, always)?.action?.text)
        q.done()
        assertTrue(q.isEmpty)
    }

    @Test
    fun stalePromptsAreDropped() {
        val q = SpeechQueue(maxWaitMillis = 5_000)
        q.offer(item("Turn left.", at = 0, id = "a"))
        assertNull(q.next(6_000, always))
        q.offer(item("Turn right.", at = 0, id = "b"))
        assertNull(q.next(100) { it != "b" })
        // Alerts aren't tied to a turn and still play.
        q.offer(item("Speed camera ahead.", UtteranceKind.HAZARD_ALERT, at = 0))
        assertEquals("Speed camera ahead.", q.next(9_000) { false }?.action?.text)
    }
}
