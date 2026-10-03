package app.southbound.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenUnitsTest {

    @Test
    fun distancesAreSaidInWords() {
        assertEquals("12 miles", SpokenUnits.distance(19_312.0))
        assertEquals("2.4 miles", SpokenUnits.distance(3_862.0))
        assertEquals("2 miles", SpokenUnits.distance(3_220.0))
        assertEquals("1 mile", SpokenUnits.distance(1_609.0))
        assertEquals("0.3 miles", SpokenUnits.distance(483.0))
        assertEquals("500 feet", SpokenUnits.distance(152.0))
        assertEquals("50 feet", SpokenUnits.distance(1.0))
    }
}
