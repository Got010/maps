package app.southbound.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingLandscapeTest {

    @Test
    fun `portrait never uses the driving column`() {
        assertFalse(DrivingLandscape.isActive(landscape = false, widthDp = 900))
        assertFalse(DrivingLandscape.isActive(landscape = false, widthDp = 400))
    }

    @Test
    fun `a phone on its side does`() {
        // A 411 x 891 dp phone rotated.
        assertTrue(DrivingLandscape.isActive(landscape = true, widthDp = 891))
    }

    @Test
    fun `a narrow landscape window keeps the portrait layout`() {
        assertFalse(DrivingLandscape.isActive(landscape = true, widthDp = DrivingLandscape.MIN_WIDTH_DP - 1))
        assertTrue(DrivingLandscape.isActive(landscape = true, widthDp = DrivingLandscape.MIN_WIDTH_DP))
    }

    @Test
    fun `the column takes 40 percent of the width`() {
        assertEquals(356.4f, DrivingLandscape.panelWidthDp(891), 0.01f)
        assertEquals(224f, DrivingLandscape.panelWidthDp(560), 0.01f)
    }
}
