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

    @Test
    fun withinKeepsTheRadiusAndSortsNearestFirst() {
        val here = LatLon(40.7580, -73.9855)
        fun lot(id: Long, dLat: Double) = Place(id, PlaceKind.PLACE, "Lot $id", "parking", null, here.lat + dLat, here.lon)
        // 0.001 degrees of latitude is about 111 m.
        val found = NearbyBox.within(listOf(lot(1, 0.002), lot(2, 0.0005), lot(3, 0.004), lot(4, -0.001)), here, 300.0, 5)
        assertEquals(listOf(2L, 4L, 1L), found.map { it.place.id })
        assertEquals(55.6, found[0].distanceMeters!!, 1.0)
        assertEquals(1, NearbyBox.within(listOf(lot(1, 0.002), lot(2, 0.0005)), here, 300.0, 1).size)
    }
}
