package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosureAvoidanceTest {

    private fun closure(id: String, lat: Double, lon: Double, kind: IncidentKind = IncidentKind.CLOSURE) =
        Incident(id, kind, "Road", null, "desc", GeoPoint(lat, lon), null, null, null)

    @Test
    fun `a square is a closed ring of lon-lat pairs about 80 m across`() {
        val ring = ClosureAvoidance.square(GeoPoint(40.75, -73.98))
        assertEquals(5, ring.size)
        assertEquals(ring.first(), ring.last())
        val width = Geo.distanceMeters(GeoPoint(ring[0].second, ring[0].first), GeoPoint(ring[1].second, ring[1].first))
        val height = Geo.distanceMeters(GeoPoint(ring[1].second, ring[1].first), GeoPoint(ring[2].second, ring[2].first))
        assertEquals(80.0, width, 1.0)
        assertEquals(80.0, height, 1.0)
        assertTrue(ring.all { (lon, lat) -> lon < -73 && lat > 40 })
    }

    @Test
    fun `only closures near the trip are sent, nearest the line first, capped`() {
        val from = GeoPoint(40.70, -74.00)
        val to = GeoPoint(40.80, -73.95)
        val onLine = closure("on", 40.75, -73.975)
        val nearby = closure("near", 40.76, -73.99)
        val farAway = closure("buffalo", 42.88, -78.87)
        val notAClosure = closure("work", 40.75, -73.975, IncidentKind.ROADWORK)
        val relevant = ClosureAvoidance.relevant(listOf(farAway, nearby, notAClosure, onLine), from, to)
        assertEquals(listOf("on", "near"), relevant.map { it.id })
        assertEquals(1, ClosureAvoidance.relevant(listOf(nearby, onLine), from, to, max = 1).size)
        assertEquals(2, ClosureAvoidance.polygons(listOf(farAway, nearby, onLine), from, to).size)
        // The box grows by at least 3 km, so a closure just past the destination still counts.
        assertEquals(1, ClosureAvoidance.relevant(listOf(closure("past", 40.82, -73.95)), from, to).size)
    }

    @Test
    fun `closures on the route are found by distance to the line`() {
        val route = listOf(GeoPoint(40.70, -74.00), GeoPoint(40.75, -73.98), GeoPoint(40.80, -73.95))
        val onRoute = closure("on", 40.7501, -73.98)
        val beside = closure("beside", 40.75, -73.97) // about 850 m east
        val found = ClosureAvoidance.onRoute(listOf(onRoute, beside), route)
        assertEquals(listOf("on"), found.map { it.id })
        assertTrue(ClosureAvoidance.onRoute(listOf(onRoute), emptyList()).isEmpty())
    }

    @Test
    fun `distances are about right`() {
        // Columbus Circle to Grand Army Plaza: roughly 1.1 km.
        val d = Geo.distanceMeters(GeoPoint(40.7681, -73.9819), GeoPoint(40.7644, -73.9735))
        assertEquals(815.0, d, 20.0)
        assertEquals(0.0, Geo.distanceToSegmentMeters(GeoPoint(40.0, -74.0), GeoPoint(40.0, -74.0), GeoPoint(40.0, -74.0)), 1e-6)
    }
}
