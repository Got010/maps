package app.southbound.core.routing

import app.southbound.core.routing.CameraAvoidance.Spot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAvoidanceTest {

    @Test
    fun `cameras next to the start, a stop or the destination are left alone`() {
        val start = Spot(40.70, -74.00)
        val nearStart = Spot(40.7005, -74.00) // ~55 m
        val awayFromEverything = Spot(40.72, -74.00)
        val nearEnd = Spot(40.7999, -73.95)
        val end = Spot(40.80, -73.95)
        val ends = listOf(start, end)
        assertFalse(CameraAvoidance.clearOfWaypoints(nearStart, ends))
        assertFalse(CameraAvoidance.clearOfWaypoints(nearEnd, ends))
        assertTrue(CameraAvoidance.clearOfWaypoints(awayFromEverything, ends))
        assertTrue(CameraAvoidance.clearOfWaypoints(awayFromEverything, emptyList()))
    }

    @Test
    fun `a reroute leaves out the spots beside the car, not the ones ahead`() {
        val car = Spot(40.70, -74.00)
        val beside = Spot(40.7005, -74.00) // ~55 m: the road the car is on
        val ahead = Spot(40.72, -74.00) // ~2.2 km
        assertEquals(listOf(ahead), CameraAvoidance.clearOfCar(listOf(beside, ahead), car))
    }

    @Test
    fun `a reroute that finds no road with the spots routes once more without them`() {
        val car = Spot(40.70, -74.00)
        val ahead = Spot(40.72, -74.00)
        val asked = mutableListOf<List<Spot>>()
        // The car is on an excluded road farther than the clearance: no route with any spot.
        val route = CameraAvoidance.fromCar(listOf(ahead), car) { spots ->
            asked += spots
            if (spots.isNotEmpty()) throw RoutingException("No path could be found for input")
            "plain"
        }
        assertEquals("plain", route)
        assertEquals(listOf(listOf(ahead), emptyList()), asked)
    }

    @Test
    fun `a reroute that routes with the spots keeps them, and one with no spots to drop still fails`() {
        val car = Spot(40.70, -74.00)
        val ahead = Spot(40.72, -74.00)
        assertEquals(listOf(ahead), CameraAvoidance.fromCar(listOf(ahead), car) { it })
        var calls = 0
        val failed = runCatching {
            CameraAvoidance.fromCar(emptyList(), car) { _ ->
                calls++
                throw RoutingException("No path could be found for input")
            }
        }
        assertTrue(failed.exceptionOrNull() is RoutingException)
        assertEquals(1, calls)
    }

    @Test
    fun `a batch that fits is kept whole with one routing`() = runBlocking {
        var calls = 0
        val split = CameraAvoidance.passable(listOf("a", "b", "c")) { calls++; true }
        assertEquals(listOf("a", "b", "c"), split.kept)
        assertEquals(emptyList<String>(), split.dropped)
        assertEquals(1, calls)
    }

    @Test
    fun `the camera on the only way is dropped and the rest kept`() = runBlocking {
        // Any set holding "bridge" leaves no route.
        var calls = 0
        val split = CameraAvoidance.passable(listOf("a", "b", "bridge", "c", "d", "e", "f", "g")) { part -> calls++; "bridge" !in part }
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), split.kept)
        assertEquals(listOf("bridge"), split.dropped)
        // The whole, two halves, two quarters, two single items, then the rest in bigger pieces.
        assertTrue("$calls routings", calls <= 8)
    }

    @Test
    fun `two cameras that shut the way together keep the first and drop the second`() = runBlocking {
        // Two bridges, a camera on each: either alone can be avoided, both together cannot.
        val split = CameraAvoidance.passable(listOf("x", "bridgeA", "y", "bridgeB")) { part -> !("bridgeA" in part && "bridgeB" in part) }
        assertEquals(listOf("x", "bridgeA", "y"), split.kept)
        assertEquals(listOf("bridgeB"), split.dropped)
    }

    @Test
    fun `nothing passable drops everything`() = runBlocking {
        val split = CameraAvoidance.passable(listOf("a", "b", "c")) { false }
        assertEquals(emptyList<String>(), split.kept)
        assertEquals(listOf("a", "b", "c"), split.dropped)
        assertEquals(CameraAvoidance.Split<String>(emptyList(), emptyList()).kept, CameraAvoidance.passable(emptyList<String>()) { true }.kept)
    }

    @Test
    fun `distances are metres on the ground`() {
        assertEquals(111.32, CameraAvoidance.distanceMeters(Spot(41.0, -74.0), Spot(41.001, -74.0)), 0.5)
        assertEquals(84.0, CameraAvoidance.distanceMeters(Spot(41.0, -74.0), Spot(41.0, -74.001)), 0.5)
    }

    @Test
    fun `the limits fit the phone's Valhalla`() {
        // OfflineRouter raises max_exclude_locations above MAX_CAMERAS; a pass costs one routing, a bisection a few.
        assertTrue(CameraAvoidance.MAX_ROUTINGS > CameraAvoidance.MAX_PASSES)
        assertTrue(CameraAvoidance.SAME_ROAD_METERS < CameraAvoidance.NEAR_ROUTE_METERS)
    }

    @Test
    fun `avoiding plate readers keeps off toll roads too`() {
        val prefs = RoutePrefs(avoidAlpr = true)
        assertFalse(prefs.isDefault)
        assertTrue(RoutePrefs.DEFAULT.isDefault)
        // Tolls are plate readers: the same costing as "Avoid tolls", with the same way back when no route is left.
        assertEquals(RoutePrefs(avoidTolls = true).costingOptionsJson(), prefs.costingOptionsJson())
        assertEquals("""{"use_tolls":0,"exclude_tolls":true}""", prefs.costingOptionsJson())
        assertEquals("""{"use_tolls":0}""", prefs.costingOptionsJson(strictTolls = false))
        // DOT cameras are not tolls: avoiding them alone is still no costing option.
        assertNull(RoutePrefs(avoidDotCameras = true).costingOptionsJson())
        val body = """{"format":"osrm","costing":"auto","locations":[]}"""
        assertEquals(body, RouteOptions.withPrefs(body, TravelMode.DRIVE, RoutePrefs(avoidDotCameras = true)))
    }
}
