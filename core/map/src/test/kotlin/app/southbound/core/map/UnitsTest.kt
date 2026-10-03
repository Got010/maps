package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {
    @Test
    fun distance() {
        assertEquals("50 ft", Units.distance(3.0))
        assertEquals("450 ft", Units.distance(137.0))
        assertEquals("0.3 mi", Units.distance(500.0))
        assertEquals("2.4 mi", Units.distance(3862.0))
        assertEquals("18 mi", Units.distance(29_000.0))
    }

    @Test
    fun duration() {
        assertEquals("1 min", Units.duration(10.0))
        assertEquals("25 min", Units.duration(1500.0))
        assertEquals("1 hr 5 min", Units.duration(3900.0))
        assertEquals("3 hr", Units.duration(10_800.0))
    }
}
