package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationCameraTest {

    /** Where MapLibre puts the tracked puck, from the top of the view. */
    private fun puckY(height: Int, top: Double, bottom: Double) = top + (height - top - bottom) / 2

    @Test
    fun puckLandsAThirdUpFromTheBottom() {
        val height = 2400
        val top = navigationTopPadding(height)
        assertEquals(720.0, top, 1e-9)
        // 35% up from the bottom is 65% down from the top.
        assertEquals(height * 0.65, puckY(height, top, 0.0), 1e-9)
    }

    @Test
    fun bottomPaddingIsCompensated() {
        val height = 2000
        val top = navigationTopPadding(height, bottomPx = 100)
        assertEquals(height * 0.65, puckY(height, top, 100.0), 1e-9)
    }

    @Test
    fun noHeightMeansNoPadding() {
        assertEquals(0.0, navigationTopPadding(0), 0.0)
        assertEquals(0.0, navigationTopPadding(-5), 0.0)
    }

    @Test
    fun centredWhenAskedForHalf() {
        assertEquals(0.0, navigationTopPadding(1000, fromBottom = 0.5), 1e-9)
    }

    @Test
    fun paddingNeverSwallowsTheView() {
        val top = navigationTopPadding(1000, fromBottom = -2.0)
        assertEquals(750.0, top, 1e-9)
    }
}
