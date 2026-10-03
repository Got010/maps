package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStopsTest {
    private val same = { a: String, b: String -> a == b }

    @Test
    fun `add appends before the destination and ignores repeats`() {
        var stops = TripStops.add(emptyList(), "Albany", same)
        stops = TripStops.add(stops, "Utica", same)
        assertEquals(listOf("Albany", "Utica"), stops)
        assertEquals(stops, TripStops.add(stops, "Albany", same))
        assertEquals(listOf("Albany", "Utica", "Buffalo"), TripStops.route(stops, "Buffalo"))
    }

    @Test
    fun `add stops at the limit`() {
        val full = (1..TripStops.MAX_STOPS).map { "s$it" }
        assertEquals(full, TripStops.add(full, "extra", same))
    }

    @Test
    fun `move and remove`() {
        val stops = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), TripStops.move(stops, 1, -1))
        assertEquals(listOf("a", "c", "b"), TripStops.move(stops, 1, 1))
        assertEquals(stops, TripStops.move(stops, 0, -1))
        assertEquals(stops, TripStops.move(stops, 2, 1))
        assertEquals(stops, TripStops.move(stops, 5, 1))
        assertEquals(listOf("a", "c"), TripStops.remove(stops, 1))
        assertEquals(stops, TripStops.remove(stops, 3))
        assertEquals(emptyList<String>(), TripStops.remove(listOf("x"), 0))
    }

    @Test
    fun `skip drops the next waypoint but never the destination`() {
        assertEquals(listOf("b", "dest"), TripStops.skipNext(listOf("a", "b", "dest")))
        assertEquals(listOf("dest"), TripStops.skipNext(listOf("dest")))
        assertTrue(TripStops.canSkip(2))
        assertFalse(TripStops.canSkip(1))
        assertFalse(TripStops.canSkip(0))
    }

    @Test
    fun `reaching a stop names it and the next place`() {
        val route = listOf("Albany", "Utica", "Buffalo")
        // Three waypoints left (both stops and the destination), then two: Albany is done.
        assertEquals("Albany" to "Utica", TripStops.reached(route, 3, 2))
        assertEquals("Utica" to "Buffalo", TripStops.reached(route, 2, 1))
        // Nothing reached, or the destination itself (the trip's end says that).
        assertNull(TripStops.reached(route, 2, 2))
        assertNull(TripStops.reached(route, 1, 0))
        // Counts that don't fit the names (a trip started without them).
        assertNull(TripStops.reached(emptyList<String>(), 2, 1))
        assertNull(TripStops.reached(route, 5, 4))
    }
}

class TripClockTest {
    @Test
    fun `arrival is now plus the time left, never in the past`() {
        assertEquals(1_000_000L + 90_500L, TripClock.arrivalMillis(1_000_000L, 90.5))
        assertEquals(1_000_000L, TripClock.arrivalMillis(1_000_000L, -5.0))
    }
}
