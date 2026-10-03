package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneWordingTest {

    /** "..XX" -> four lanes, the right two to use ('X' active, 'v' valid only, '.' neither). */
    private fun lanes(pattern: String): List<Lane> = pattern.map { c ->
        Lane(listOf(LaneDirection.STRAIGHT), active = c == 'X', valid = c == 'X' || c == 'v')
    }

    private fun say(pattern: String) = LaneWording.phrase(lanes(pattern))

    @Test
    fun `lanes at the left edge`() {
        assertEquals("use the left lane", say("X."))
        assertEquals("use the far left lane", say("X.."))
        assertEquals("use the far left lane", say("X...."))
        assertEquals("use the left two lanes", say("XX."))
        assertEquals("use the left two lanes", say("XX...."))
        assertEquals("use any of the left three lanes", say("XXX."))
        assertEquals("use any of the left three lanes", say("XXX.."))
    }

    @Test
    fun `lanes at the right edge`() {
        assertEquals("use the right lane", say(".X"))
        assertEquals("use the far right lane", say("...X"))
        assertEquals("use the right two lanes", say("..XX"))
        assertEquals("use any of the right three lanes", say("..XXX"))
    }

    @Test
    fun `lanes in the middle`() {
        assertEquals("use the middle lane", say(".X."))
        assertEquals("use the middle lane", say("..X.."))
        assertEquals("use the middle two lanes", say(".XX."))
        assertEquals("use the middle three lanes", say(".XXX."))
        assertEquals("use the second lane from the left", say(".X.."))
        assertEquals("use the second lane from the right", say("..X."))
        assertEquals("use the third lane from the left", say("..X..."))
        // Two lanes off-centre: no short way to say it.
        assertNull(say(".XX.."))
    }

    @Test
    fun `nothing when every lane or none will do`() {
        assertNull(say("XXX"))
        assertNull(say("..."))
        assertNull(say("X"))
        assertNull(say(""))
        // Four or more at an edge: hardly any lane to avoid.
        assertNull(say("XXXX."))
    }

    @Test
    fun `active lanes win over merely valid ones`() {
        assertEquals("use the far left lane", say("Xvv."))
        assertEquals("use the right two lanes", say("v.XX"))
        // No active lane at all: the valid ones count.
        assertEquals("use the left two lanes", say("vv.."))
    }

    @Test
    fun `split groups name the one at an edge`() {
        assertEquals("use the left two lanes", say("XX.X."))
        assertEquals("use the far right lane", say(".X..X"))
        // Groups at both edges, or at neither, are ambiguous.
        assertNull(say("X..X"))
        assertNull(say(".X.X."))
    }

    @Test
    fun `worth showing`() {
        assertTrue(Lanes.worthShowing(lanes("X..")))
        assertTrue(Lanes.worthShowing(lanes("XXX")))
        assertFalse(Lanes.worthShowing(lanes("X")))
        assertFalse(Lanes.worthShowing(lanes("...")))
    }
}
