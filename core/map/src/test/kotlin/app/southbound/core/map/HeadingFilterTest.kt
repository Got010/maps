package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingFilterTest {
    private fun HeadingFilter.fix(
        bearing: Float? = null,
        speed: Float? = null,
        accuracy: Float? = null,
    ) = update(
        hasBearing = bearing != null,
        bearing = bearing ?: 0f,
        hasSpeed = speed != null,
        speed = speed ?: 0f,
        hasBearingAccuracy = accuracy != null,
        bearingAccuracy = accuracy ?: 0f,
    )

    @Test
    fun startsNorth() {
        assertEquals(0f, HeadingFilter().heading, 0f)
    }

    @Test
    fun movingFixSetsHeading() {
        val filter = HeadingFilter()
        assertEquals(135f, filter.fix(bearing = 135f, speed = 8f), 0f)
    }

    @Test
    fun fixWithoutBearingKeepsLastHeading() {
        val filter = HeadingFilter()
        filter.fix(bearing = 200f, speed = 8f)
        assertEquals(200f, filter.fix(speed = 8f), 0f)
        assertEquals(200f, filter.fix(), 0f)
    }

    @Test
    fun firstFixWithoutBearingStaysNorth() {
        assertEquals(0f, HeadingFilter().fix(speed = 8f), 0f)
    }

    @Test
    fun parkedPhoneDoesNotSpin() {
        val filter = HeadingFilter()
        filter.fix(bearing = 90f, speed = 10f)
        assertEquals(90f, filter.fix(bearing = 270f, speed = 0.3f), 0f)
        assertEquals(90f, filter.fix(bearing = 10f, speed = 0.99f), 0f)
    }

    @Test
    fun bearingWithoutSpeedIsIgnored() {
        val filter = HeadingFilter()
        assertEquals(0f, filter.fix(bearing = 123f), 0f)
    }

    @Test
    fun speedThresholdIsInclusive() {
        assertEquals(45f, HeadingFilter().fix(bearing = 45f, speed = 1.0f), 0f)
    }

    @Test
    fun poorBearingAccuracyIsIgnored() {
        val filter = HeadingFilter()
        filter.fix(bearing = 10f, speed = 8f, accuracy = 5f)
        assertEquals(10f, filter.fix(bearing = 180f, speed = 8f, accuracy = 60f), 0f)
        assertEquals(20f, filter.fix(bearing = 20f, speed = 8f, accuracy = 45f), 0f)
    }

    @Test
    fun missingAccuracyIsAccepted() {
        assertTrue(HeadingFilter.isUsable(true, true, 5f, false, 999f))
        assertFalse(HeadingFilter.isUsable(true, true, 5f, true, 999f))
    }

    @Test
    fun headingIsNormalised() {
        assertEquals(10f, HeadingFilter().fix(bearing = 370f, speed = 5f), 0f)
        assertEquals(350f, HeadingFilter().fix(bearing = -10f, speed = 5f), 0f)
    }
}
