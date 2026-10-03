package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class EtaTest {
    @Test
    fun clockAddsTheDurationInTheGivenZone() {
        // 2026-10-03 15:00:00 UTC is 11:00 in New York (EDT).
        val noonish = 1_791_039_600_000L
        val ny = TimeZone.getTimeZone("America/New_York")
        val text = Eta.clock(noonish, 42 * 60.0, Locale.US, ny)
        assertTrue(text, text.startsWith("11:42"))
        assertTrue(text, text.contains("AM"))
    }

    @Test
    fun lateMeansMoreThanTwoMinutes() {
        assertFalse(Eta.isLate(120.0))
        assertTrue(Eta.isLate(121.0))
        assertEquals(3, Eta.delayMinutes(170.0))
        assertEquals(1, Eta.delayMinutes(20.0))
    }

    // A road running east along 40.7N; the end of the route is at -74.000.
    private val road = listOf(MapPoint(40.7, -74.010), MapPoint(40.7, -74.005), MapPoint(40.7, -74.000))

    @Test
    fun destinationNorthOfAnEastboundRoadIsOnTheLeft() {
        assertEquals(ArrivalSide.LEFT, Arrival.side(road, MapPoint(40.70015, -74.0001)))
    }

    @Test
    fun destinationSouthOfAnEastboundRoadIsOnTheRight() {
        assertEquals(ArrivalSide.RIGHT, Arrival.side(road, MapPoint(40.69985, -74.0001)))
    }

    @Test
    fun westboundFlipsIt() {
        val west = road.reversed()
        assertEquals(ArrivalSide.RIGHT, Arrival.side(west, MapPoint(40.70015, -74.0101)))
    }

    @Test
    fun destinationOnTheRoadLineIsAhead() {
        assertEquals(ArrivalSide.AHEAD, Arrival.side(road, MapPoint(40.70001, -73.9995)))
    }

    @Test
    fun needsAWayToKnowTheHeading() {
        assertNull(Arrival.side(emptyList(), MapPoint(40.7, -74.0)))
        assertNull(Arrival.side(listOf(MapPoint(40.7, -74.0)), MapPoint(40.7, -74.0)))
    }

    @Test
    fun aTinyFinalSegmentDoesNotSkewTheHeading() {
        // The last point curls 1 m north; the road still heads east.
        val curl = road + MapPoint(40.700009, -74.0)
        assertEquals(ArrivalSide.LEFT, Arrival.side(curl, MapPoint(40.70020, -74.0001)))
    }
}
