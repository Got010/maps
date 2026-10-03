package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyBoxTest {
    @Test
    fun boxSpansTheDistanceBothWays() {
        val times = LatLon(40.7580, -73.9855)
        val box = NearbyBox.around(times, 15.0)
        assertEquals(15.0, SearchRanking.distanceMeters(times, box.north, times.lon) / 1000, 0.1)
        assertEquals(15.0, SearchRanking.distanceMeters(times, times.lat, box.east) / 1000, 0.2)
        assertTrue(box.contains(40.7527, -73.9772)) // Grand Central
        assertFalse(box.contains(40.5774, -73.9812)) // Coney Island, ~20 km south
    }
}
