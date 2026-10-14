package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteOptionsTest {

    @Test
    fun `exclude polygons go into the request body as lon-lat rings`() {
        val body = """{"format":"osrm","costing":"auto","locations":[]}"""
        val ring = listOf(-74.0 to 40.7, -73.99 to 40.7, -73.99 to 40.71, -74.0 to 40.71, -74.0 to 40.7)
        assertEquals(
            """{"exclude_polygons":[[[-74.000000,40.700000],[-73.990000,40.700000],[-73.990000,40.710000],[-74.000000,40.710000],[-74.000000,40.700000]]],""" +
                """"format":"osrm","costing":"auto","locations":[]}""",
            RouteOptions.withExcludePolygons(body, listOf(ring)),
        )
    }

    @Test
    fun `nothing to avoid leaves the body alone`() {
        val body = """{"costing":"auto"}"""
        assertEquals(body, RouteOptions.withExcludePolygons(body, emptyList()))
        assertEquals(body, RouteOptions.withExcludePolygons(body, listOf(listOf(-74.0 to 40.7, -73.9 to 40.7))))
        assertEquals(body, RouteOptions.withExcludeLocations(body, emptyList()))
    }

    @Test
    fun `exclude locations go into the request body as lat-lon points, beside the polygons`() {
        val body = """{"format":"osrm","costing":"auto","locations":[]}"""
        val points = listOf(CameraAvoidance.Spot(40.7, -74.0), CameraAvoidance.Spot(41.123456789, -73.5))
        assertEquals(
            """{"exclude_locations":[{"lat":40.700000,"lon":-74.000000},{"lat":41.123457,"lon":-73.500000}],""" +
                """"format":"osrm","costing":"auto","locations":[]}""",
            RouteOptions.withExcludeLocations(body, points),
        )
        val ring = listOf(-74.0 to 40.7, -73.99 to 40.7, -73.99 to 40.71, -74.0 to 40.7)
        val both = RouteOptions.withExcludeLocations(RouteOptions.withExcludePolygons(body, listOf(ring)), points.take(1))
        assertEquals(true, both.startsWith("""{"exclude_locations":[{"lat":40.700000,"lon":-74.000000}],"exclude_polygons":[[["""))
    }

    @Test
    fun `options ask for alternatives in miles`() {
        assertEquals("""{"alternates":2,"units":"miles","language":"en-US"}""", RouteOptions.json(TravelMode.DRIVE))
        // Costing options are per request (withPrefs), so no mode carries its own.
        assertEquals(RouteOptions.json(TravelMode.DRIVE), RouteOptions.json(TravelMode.BIKE))
        assertEquals(RouteOptions.json(TravelMode.DRIVE), RouteOptions.json(TravelMode.WALK))
    }

    // Phase 6
    private val autoBody = """{"format":"osrm","costing":"auto","locations":[]}"""

    @Test
    fun `default prefs and other modes leave the body alone`() {
        assertEquals(autoBody, RouteOptions.withPrefs(autoBody, TravelMode.DRIVE, RoutePrefs.DEFAULT))
        // Driving options never leak into walking requests (cycling always sends its own, see WalkBikePrefsTest).
        val avoid = RoutePrefs(avoidTolls = true, avoidHighways = true)
        assertEquals(autoBody, RouteOptions.withPrefs(autoBody, TravelMode.WALK, avoid))
        // Truck dimensions alone don't matter while the profile is Car.
        assertEquals(autoBody, RouteOptions.withPrefs(autoBody, TravelMode.DRIVE, RoutePrefs(heightFt = 12.0)))
    }

    @Test
    fun `avoid toggles become use_ options at zero`() {
        assertEquals(
            """{"costing_options":{"auto":{"use_tolls":0,"exclude_tolls":true,"use_highways":0,"use_ferry":0}},"format":"osrm","costing":"auto","locations":[]}""",
            RouteOptions.withPrefs(autoBody, TravelMode.DRIVE, RoutePrefs(avoidTolls = true, avoidHighways = true, avoidFerries = true)),
        )
        assertEquals(
            """{"costing_options":{"auto":{"use_tolls":0,"exclude_tolls":true}},"format":"osrm","costing":"auto","locations":[]}""",
            RouteOptions.withPrefs(autoBody, TravelMode.DRIVE, RoutePrefs(avoidTolls = true)),
        )
        // With no way around a toll, the router asks again with tolls kept as few as it can.
        assertEquals(
            """{"costing_options":{"auto":{"use_tolls":0}},"format":"osrm","costing":"auto","locations":[]}""",
            RouteOptions.withPrefs(autoBody, TravelMode.DRIVE, RoutePrefs(avoidTolls = true), strictTolls = false),
        )
    }

    @Test
    fun `truck profile switches costing and converts feet and tons`() {
        val prefs = RoutePrefs(avoidFerries = true, vehicle = VehicleKind.TRUCK, heightFt = 13.5, widthFt = 8.5, lengthFt = 50.0, weightTons = 20.0)
        assertEquals(
            """{"costing_options":{"truck":{"use_ferry":0,"height":4.11,"width":2.59,"length":15.24,"weight":18.14}},"format":"osrm","costing":"truck","locations":[]}""",
            RouteOptions.withPrefs(autoBody, TravelMode.DRIVE, prefs),
        )
        val spaced = """{"costing" : "auto"}"""
        assertEquals(true, RouteOptions.withPrefs(spaced, TravelMode.DRIVE, prefs).contains(""""costing":"truck""""))
    }

    @Test
    fun `prefs compose with exclude polygons and stay valid json shape`() {
        val ring = listOf(-74.0 to 40.7, -73.99 to 40.7, -73.99 to 40.71, -74.0 to 40.7)
        val body = RouteOptions.withExcludePolygons(RouteOptions.withPrefs(autoBody, TravelMode.DRIVE, RoutePrefs(avoidHighways = true)), listOf(ring))
        assertEquals(true, body.startsWith("""{"exclude_polygons":[[["""))
        assertEquals(true, body.contains(""""costing_options":{"auto":{"use_highways":0}}"""))
    }

    @Test
    fun `sanitized keeps dimensions in range`() {
        val p = RoutePrefs(vehicle = VehicleKind.TRUCK, heightFt = 0.0, widthFt = 99.0, lengthFt = -1.0, weightTons = 1000.0).sanitized()
        assertEquals(3.0, p.heightFt, 0.0)
        assertEquals(12.0, p.widthFt, 0.0)
        assertEquals(5.0, p.lengthFt, 0.0)
        assertEquals(80.0, p.weightTons, 0.0)
        assertEquals("truck", p.costing)
        assertEquals(true, RoutePrefs.DEFAULT.isDefault)
    }
}
