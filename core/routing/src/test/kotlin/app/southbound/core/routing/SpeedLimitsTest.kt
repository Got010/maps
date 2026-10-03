package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedLimitsTest {

    @Test
    fun `limits round to sign values`() {
        assertEquals(55, SpeedLimits.roundToSign(88.5 * 0.621371)) // 88.5 km/h is a 55 mph sign
        assertEquals(25, SpeedLimits.roundToSign(40.0 * 0.621371))
        assertEquals(30, SpeedLimits.roundToSign(29.9))
        assertNull(SpeedLimits.roundToSign(0.0))
        assertNull(SpeedLimits.roundToSign(400.0))
    }

    @Test
    fun `gps speed to mph`() {
        assertEquals(22.4, SpeedLimits.mph(10f), 0.1)
    }
}
