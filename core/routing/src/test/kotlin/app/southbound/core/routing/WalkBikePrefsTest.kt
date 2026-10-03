package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkBikePrefsTest {

    private val walkBody = """{"format":"osrm","costing":"pedestrian","locations":[]}"""
    private val bikeBody = """{"format":"osrm","costing":"bicycle","locations":[]}"""

    // Walking

    @Test
    fun `default walking prefs send nothing`() {
        assertNull(WalkPrefs().costingOptionsJson())
        assertEquals(walkBody, RouteOptions.withPrefs(walkBody, TravelMode.WALK, RoutePrefs.DEFAULT))
    }

    @Test
    fun `avoid stairs is a 600 second step penalty`() {
        assertEquals("""{"step_penalty":600}""", WalkPrefs(avoidStairs = true).costingOptionsJson())
        assertEquals(
            """{"costing_options":{"pedestrian":{"step_penalty":600}},"format":"osrm","costing":"pedestrian","locations":[]}""",
            RouteOptions.withPrefs(walkBody, TravelMode.WALK, RoutePrefs(walk = WalkPrefs(avoidStairs = true))),
        )
    }

    @Test
    fun `step free adds flat grades, no rough trails and a slower pace to the stairs penalty`() {
        assertEquals(
            """{"walking_speed":4,"step_penalty":600,"use_hills":0.1,"use_tracks":0,"max_hiking_difficulty":0}""",
            WalkPrefs(stepFree = true).costingOptionsJson(),
        )
        // Asking for both is the same as step free alone.
        assertEquals(WalkPrefs(stepFree = true).costingOptionsJson(), WalkPrefs(stepFree = true, avoidStairs = true).costingOptionsJson())
    }

    @Test
    fun `prefer paths favours footways and sidewalks with factors below one`() {
        assertEquals("""{"walkway_factor":0.5,"sidewalk_factor":0.7}""", WalkPrefs(preferPaths = true).costingOptionsJson())
        assertEquals(
            """{"step_penalty":600,"walkway_factor":0.5,"sidewalk_factor":0.7}""",
            WalkPrefs(avoidStairs = true, preferPaths = true).costingOptionsJson(),
        )
    }

    @Test
    fun `walking prefs go under pedestrian only for walk mode`() {
        val prefs = RoutePrefs(walk = WalkPrefs(stepFree = true), avoidTolls = true)
        assertTrue(RouteOptions.withPrefs(walkBody, TravelMode.WALK, prefs).contains(""""costing_options":{"pedestrian":{"walking_speed":4,"""))
        // Driving ignores the walking prefs, and its own body only carries its own options.
        val auto = """{"costing":"auto"}"""
        assertEquals("""{"costing_options":{"auto":{"use_tolls":0}},"costing":"auto"}""", RouteOptions.withPrefs(auto, TravelMode.DRIVE, prefs))
        assertEquals(auto, RouteOptions.withPrefs(auto, TravelMode.DRIVE, RoutePrefs(walk = WalkPrefs(stepFree = true))))
    }

    // Cycling

    @Test
    fun `default cycling prefs keep the bike lane friendly defaults`() {
        assertEquals("""{"bicycle_type":"hybrid","use_roads":0.3}""", BikePrefs().costingOptionsJson(avoidFerries = false))
        assertEquals(
            """{"costing_options":{"bicycle":{"bicycle_type":"hybrid","use_roads":0.3}},"format":"osrm","costing":"bicycle","locations":[]}""",
            RouteOptions.withPrefs(bikeBody, TravelMode.BIKE, RoutePrefs.DEFAULT),
        )
    }

    @Test
    fun `bicycle type maps to valhalla names`() {
        assertEquals("""{"bicycle_type":"road","use_roads":0.3}""", BikePrefs(type = BikeType.ROAD).costingOptionsJson(false))
        assertEquals("""{"bicycle_type":"mountain","use_roads":0.3}""", BikePrefs(type = BikeType.MOUNTAIN).costingOptionsJson(false))
    }

    @Test
    fun `avoid hills and busy roads lower use_hills and use_roads`() {
        assertEquals(
            """{"bicycle_type":"road","use_roads":0.1,"use_hills":0.1}""",
            BikePrefs(type = BikeType.ROAD, avoidHills = true, avoidBusyRoads = true).costingOptionsJson(false),
        )
        assertEquals("""{"bicycle_type":"hybrid","use_roads":0.3,"use_hills":0.1}""", BikePrefs(avoidHills = true).costingOptionsJson(false))
    }

    @Test
    fun `avoid ferries reaches the bicycle options`() {
        val prefs = RoutePrefs(avoidFerries = true, bike = BikePrefs(avoidHills = true))
        assertEquals(
            """{"costing_options":{"bicycle":{"bicycle_type":"hybrid","use_roads":0.3,"use_hills":0.1,"use_ferry":0}},"costing":"bicycle"}""",
            RouteOptions.withPrefs("""{"costing":"bicycle"}""", TravelMode.BIKE, prefs),
        )
    }

    @Test
    fun `bike options never carry walking or driving options`() {
        val prefs = RoutePrefs(avoidTolls = true, walk = WalkPrefs(stepFree = true))
        val body = RouteOptions.withPrefs(bikeBody, TravelMode.BIKE, prefs)
        assertFalse(body.contains("pedestrian"))
        assertFalse(body.contains("use_tolls"))
        assertFalse(body.contains("step_penalty"))
        assertEquals(1, Regex(""""costing_options"""").findAll(body).count())
    }

    // The Options chip

    @Test
    fun `isDefaultFor looks at the active mode only`() {
        assertTrue(RoutePrefs.DEFAULT.isDefaultFor(TravelMode.DRIVE))
        assertTrue(RoutePrefs.DEFAULT.isDefaultFor(TravelMode.WALK))
        assertTrue(RoutePrefs.DEFAULT.isDefaultFor(TravelMode.BIKE))

        val walking = RoutePrefs(walk = WalkPrefs(avoidStairs = true))
        assertTrue(walking.isDefaultFor(TravelMode.DRIVE))
        assertFalse(walking.isDefaultFor(TravelMode.WALK))
        assertTrue(walking.isDefaultFor(TravelMode.BIKE))

        val cycling = RoutePrefs(bike = BikePrefs(type = BikeType.MOUNTAIN))
        assertTrue(cycling.isDefaultFor(TravelMode.DRIVE))
        assertTrue(cycling.isDefaultFor(TravelMode.WALK))
        assertFalse(cycling.isDefaultFor(TravelMode.BIKE))

        val driving = RoutePrefs(avoidTolls = true)
        assertFalse(driving.isDefaultFor(TravelMode.DRIVE))
        assertTrue(driving.isDefaultFor(TravelMode.WALK))
        assertTrue(driving.isDefaultFor(TravelMode.BIKE))

        // Ferries are shared with cycling, but not walking.
        val ferries = RoutePrefs(avoidFerries = true)
        assertFalse(ferries.isDefaultFor(TravelMode.DRIVE))
        assertTrue(ferries.isDefaultFor(TravelMode.WALK))
        assertFalse(ferries.isDefaultFor(TravelMode.BIKE))
    }

    @Test
    fun `plain isDefault stays about driving`() {
        assertTrue(RoutePrefs(walk = WalkPrefs(stepFree = true), bike = BikePrefs(avoidHills = true)).isDefault)
    }
}
