package app.southbound.feature.directions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetSwipeTest {
    private val threshold = 100f
    private val flingSpeed = 1000f

    @Test
    fun `an open sheet follows the finger down only, up to four thresholds`() {
        assertEquals(30f, SheetSwipe.follow(0f, 30f, collapsed = false, threshold = threshold), 0f)
        assertEquals(0f, SheetSwipe.follow(0f, -30f, collapsed = false, threshold = threshold), 0f)
        assertEquals(0f, SheetSwipe.follow(20f, -50f, collapsed = false, threshold = threshold), 0f)
        assertEquals(400f, SheetSwipe.follow(390f, 50f, collapsed = false, threshold = threshold), 0f)
    }

    @Test
    fun `a shrunk sheet follows the finger up only, up to two thresholds`() {
        assertEquals(-30f, SheetSwipe.follow(0f, -30f, collapsed = true, threshold = threshold), 0f)
        assertEquals(0f, SheetSwipe.follow(0f, 30f, collapsed = true, threshold = threshold), 0f)
        assertEquals(-200f, SheetSwipe.follow(-190f, -50f, collapsed = true, threshold = threshold), 0f)
    }

    @Test
    fun `a long drag down or a quick flick down shrinks an open sheet`() {
        assertTrue(SheetSwipe.settle(101f, 0f, collapsed = false, threshold = threshold, flingSpeed = flingSpeed))
        assertTrue(SheetSwipe.settle(5f, 1001f, collapsed = false, threshold = threshold, flingSpeed = flingSpeed))
    }

    @Test
    fun `a short slow drag leaves the sheet as it was`() {
        assertFalse(SheetSwipe.settle(100f, 1000f, collapsed = false, threshold = threshold, flingSpeed = flingSpeed))
        assertTrue(SheetSwipe.settle(-100f, -1000f, collapsed = true, threshold = threshold, flingSpeed = flingSpeed))
    }

    @Test
    fun `a long drag up or a quick flick up grows a shrunk sheet`() {
        assertFalse(SheetSwipe.settle(-101f, 0f, collapsed = true, threshold = threshold, flingSpeed = flingSpeed))
        assertFalse(SheetSwipe.settle(-5f, -1001f, collapsed = true, threshold = threshold, flingSpeed = flingSpeed))
    }

    @Test
    fun `a flick the wrong way for the state does nothing`() {
        assertFalse(SheetSwipe.settle(0f, -5000f, collapsed = false, threshold = threshold, flingSpeed = flingSpeed))
        assertTrue(SheetSwipe.settle(0f, 5000f, collapsed = true, threshold = threshold, flingSpeed = flingSpeed))
    }

    @Test
    fun `a pulled sheet and the finger moving up undoes the pull`() {
        val delta = SheetSwipe.preScrollDelta(drag = 50f, availableY = -30f, collapsed = false, isUserInput = true)
        assertEquals(-30f, delta, 0f)
        assertEquals(20f, SheetSwipe.follow(50f, delta, collapsed = false, threshold = threshold), 0f)
        // Moving up further than the pull only takes the pull back to zero.
        assertEquals(0f, SheetSwipe.follow(50f, SheetSwipe.preScrollDelta(50f, -80f, false, true), collapsed = false, threshold = threshold), 0f)
    }

    @Test
    fun `finger up with the sheet not pulled takes nothing, so the body scrolls`() {
        assertEquals(0f, SheetSwipe.preScrollDelta(drag = 0f, availableY = -30f, collapsed = false, isUserInput = true), 0f)
    }

    @Test
    fun `finger down before the body has scrolled takes nothing`() {
        assertEquals(0f, SheetSwipe.preScrollDelta(drag = 50f, availableY = 30f, collapsed = false, isUserInput = true), 0f)
    }

    @Test
    fun `downward movement left over after the body scrolled pulls the sheet`() {
        val delta = SheetSwipe.postScrollDelta(availableY = 30f, collapsed = false, isUserInput = true)
        assertEquals(30f, delta, 0f)
        assertEquals(30f, SheetSwipe.follow(0f, delta, collapsed = false, threshold = threshold), 0f)
        assertEquals(0f, SheetSwipe.postScrollDelta(availableY = -30f, collapsed = false, isUserInput = true), 0f)
        assertEquals(0f, SheetSwipe.postScrollDelta(availableY = 0f, collapsed = false, isUserInput = true), 0f)
    }

    @Test
    fun `a shrunk sheet takes no scroll movement`() {
        assertEquals(0f, SheetSwipe.preScrollDelta(drag = 50f, availableY = -30f, collapsed = true, isUserInput = true), 0f)
        assertEquals(0f, SheetSwipe.postScrollDelta(availableY = 30f, collapsed = true, isUserInput = true), 0f)
    }

    @Test
    fun `movement that is not the finger takes nothing`() {
        assertEquals(0f, SheetSwipe.preScrollDelta(drag = 50f, availableY = -30f, collapsed = false, isUserInput = false), 0f)
        assertEquals(0f, SheetSwipe.postScrollDelta(availableY = 30f, collapsed = false, isUserInput = false), 0f)
    }

    @Test
    fun `a fling with the sheet not pulled is not taken`() {
        assertFalse(SheetSwipe.takesFling(drag = 0f, collapsed = false))
        assertFalse(SheetSwipe.takesFling(drag = 50f, collapsed = true))
    }

    @Test
    fun `a fling with the sheet pulled down is taken and settles by the usual thresholds`() {
        assertTrue(SheetSwipe.takesFling(drag = 5f, collapsed = false))
        assertTrue(SheetSwipe.settle(5f, 1001f, collapsed = false, threshold = threshold, flingSpeed = flingSpeed))
        assertFalse(SheetSwipe.settle(5f, 500f, collapsed = false, threshold = threshold, flingSpeed = flingSpeed))
        assertTrue(SheetSwipe.settle(101f, 0f, collapsed = false, threshold = threshold, flingSpeed = flingSpeed))
    }
}
