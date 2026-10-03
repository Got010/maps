package app.southbound.core.routing

import app.southbound.core.routing.CameraAvoidance.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAvoidanceTest {

    private val camera = Spot(40.75, -73.99)

    @Test
    fun `a camera becomes a small closed square around it`() {
        val ring = CameraAvoidance.square(camera)
        assertEquals(5, ring.size)
        assertEquals(ring.first(), ring.last())
        // (lon, lat) pairs, centred on the camera.
        assertEquals(camera.lon, ring.take(4).map { it.first }.average(), 1e-9)
        assertEquals(camera.lat, ring.take(4).map { it.second }.average(), 1e-9)
        // 36 m a side.
        assertEquals(4 * 2 * CameraAvoidance.HALF_SIDE_METERS, CameraAvoidance.perimeterMeters(ring), 0.5)
    }

    @Test
    fun `cameras next to the start, a stop or the destination are left alone`() {
        val start = Spot(40.70, -74.00)
        val nearStart = Spot(40.7005, -74.00) // ~55 m
        val awayFromEverything = Spot(40.72, -74.00)
        val nearEnd = Spot(40.7999, -73.95)
        val end = Spot(40.80, -73.95)
        val rings = CameraAvoidance.polygons(listOf(nearStart, awayFromEverything, nearEnd, awayFromEverything), listOf(start, end))
        assertEquals(1, rings.size)
        assertEquals(awayFromEverything.lat, rings.single().take(4).map { it.second }.average(), 1e-9)
    }

    @Test
    fun `at most max cameras are squared off, first ones first`() {
        val cams = (0 until 100).map { Spot(41.0 + it * 0.01, -74.0) }
        val rings = CameraAvoidance.polygons(cams, emptyList(), max = CameraAvoidance.MAX_CAMERAS)
        assertEquals(CameraAvoidance.MAX_CAMERAS, rings.size)
        assertEquals(41.0, rings.first().take(4).map { it.second }.average(), 1e-9)
        // The cap itself fits under Valhalla's perimeter limit.
        assertTrue(rings.sumOf { CameraAvoidance.perimeterMeters(it) } <= CameraAvoidance.MAX_TOTAL_PERIMETER_METERS)
    }

    @Test
    fun `closures keep their place and cameras fill what perimeter is left`() {
        // 25 closure squares of 320 m (ClosureAvoidance's own cap) leave 1.5 km of the 9.5 km budget.
        val closures = (0 until 25).map { CameraAvoidance.square(Spot(42.0 + it * 0.01, -75.0), halfSideMeters = 40.0) }
        val cams = (0 until 30).map { CameraAvoidance.square(Spot(41.0 + it * 0.01, -74.0)) }
        val merged = CameraAvoidance.merge(closures, cams)
        assertEquals(closures, merged.take(25))
        val camerasKept = merged.size - 25
        // 1,500 m / 144 m per camera.
        assertEquals(10, camerasKept)
        assertEquals(cams.take(10), merged.drop(25))
        assertTrue(merged.sumOf { CameraAvoidance.perimeterMeters(it) } <= CameraAvoidance.MAX_TOTAL_PERIMETER_METERS)
    }

    @Test
    fun `no cameras leaves the closures as they were`() {
        val closures = listOf(CameraAvoidance.square(camera, 40.0))
        assertEquals(closures, CameraAvoidance.merge(closures, emptyList()))
        assertEquals(emptyList<List<Pair<Double, Double>>>(), CameraAvoidance.merge(emptyList(), emptyList()))
    }

    @Test
    fun `avoiding plate readers is an option but not a costing option`() {
        val prefs = RoutePrefs(avoidAlpr = true)
        assertFalse(prefs.isDefault)
        assertNull(prefs.costingOptionsJson())
        assertTrue(RoutePrefs.DEFAULT.isDefault)
        val body = """{"format":"osrm","costing":"auto","locations":[]}"""
        assertEquals(body, RouteOptions.withPrefs(body, TravelMode.DRIVE, prefs))
    }
}
