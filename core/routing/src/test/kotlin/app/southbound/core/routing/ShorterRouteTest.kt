package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShorterRouteTest {

    // A short town trip (synthetic points): the main road the router prefers, the shorter side street, another street.
    private val mainRoad = ShorterRoute.Option(127.0, 1440.0, listOf(43.2000 to -75.9000, 43.1970 to -75.9093, 43.2010 to -75.9080))
    private val sideStreet = ShorterRoute.Option(106.0, 1053.0, listOf(43.2000 to -75.9000, 43.1985 to -75.9072, 43.2010 to -75.9091))
    private val otherStreet = ShorterRoute.Option(133.0, 1133.0, listOf(43.2000 to -75.9000, 43.2005 to -75.9060, 43.2010 to -75.9080))

    private fun merge(options: List<ShorterRoute.Option>, shorter: ShorterRoute.Option?) = ShorterRoute.merge(options, shorter) { it }

    @Test
    fun `a faster and shorter way is offered after the others`() {
        assertEquals(listOf(mainRoad, otherStreet, sideStreet), merge(listOf(mainRoad, otherStreet), sideStreet))
        // With two alternatives already, it takes the last one's place: three options, three colours.
        val third = otherStreet.copy(line = listOf(43.10 to -76.00, 43.15 to -76.05))
        assertEquals(listOf(mainRoad, otherStreet, sideStreet), merge(listOf(mainRoad, otherStreet, third), sideStreet))
    }

    @Test
    fun `not when it is slower or longer than the first`() {
        assertEquals(listOf(mainRoad), merge(listOf(mainRoad), sideStreet.copy(seconds = 130.0)))
        assertEquals(listOf(mainRoad), merge(listOf(mainRoad), sideStreet.copy(meters = 1500.0)))
        assertEquals(listOf(mainRoad), merge(listOf(mainRoad), null))
        assertEquals(emptyList<ShorterRoute.Option>(), merge(emptyList(), sideStreet))
    }

    @Test
    fun `not when it is a route already offered`() {
        // The same line a few metres off, a little faster by rounding: no second copy.
        val again = mainRoad.copy(seconds = 120.0, meters = 1400.0, line = mainRoad.line.map { (lat, lon) -> lat + 0.00005 to lon })
        assertEquals(listOf(mainRoad, otherStreet), merge(listOf(mainRoad, otherStreet), again))
    }

    @Test
    fun `the shorter way is only where merge put it`() {
        val merged = merge(listOf(mainRoad, otherStreet), sideStreet)
        assertEquals(2, ShorterRoute.indexIn(merged, sideStreet))
        // Traffic may reorder the options; the shorter way is found wherever it went.
        assertEquals(0, ShorterRoute.indexIn(listOf(sideStreet, mainRoad, otherStreet), sideStreet))
        // Valhalla's distance-weighted answer can be the first route itself. Merge refuses it, and an equal copy is
        // no shorter way: before, it was found "in" the one option and the preview crashed telling it apart.
        val again = mainRoad.copy()
        assertEquals(listOf(mainRoad), merge(listOf(mainRoad), again))
        assertNull(ShorterRoute.indexIn(listOf(mainRoad), again))
        // Nor is an alternative it happens to equal.
        assertNull(ShorterRoute.indexIn(listOf(mainRoad, otherStreet), otherStreet.copy()))
        assertNull(ShorterRoute.indexIn(listOf(mainRoad), null))
    }

    @Test
    fun `same means most of the length runs along the other line`() {
        val line = listOf(40.0 to -74.0, 40.0 to -73.99, 40.0 to -73.98)
        assertTrue(ShorterRoute.same(line, line))
        // 20 m north all along: the same road.
        assertTrue(ShorterRoute.same(line.map { (lat, lon) -> lat + 0.00018 to lon }, line))
        // 200 m north: a parallel street.
        assertFalse(ShorterRoute.same(line.map { (lat, lon) -> lat + 0.0018 to lon }, line))
        // Half shared, half on another road.
        assertFalse(ShorterRoute.same(listOf(40.0 to -74.0, 40.0 to -73.99, 40.01 to -73.99), line))
    }

    @Test
    fun `the request weighs distance in and asks for one route`() {
        val body = """{"alternates":2,"format":"osrm","costing":"auto","locations":[]}"""
        assertEquals(
            """{"costing_options":{"auto":{"use_distance":0.50}},"alternates":2,"format":"osrm","costing":"auto","locations":[]}""",
            RouteOptions.withPrefs(body, TravelMode.DRIVE, RoutePrefs.DEFAULT, ShorterRoute.USE_DISTANCE),
        )
        assertEquals(
            """{"costing_options":{"auto":{"use_distance":0.50,"use_tolls":0,"exclude_tolls":true}},"alternates":2,"format":"osrm","costing":"auto","locations":[]}""",
            RouteOptions.withPrefs(body, TravelMode.DRIVE, RoutePrefs(avoidTolls = true), ShorterRoute.USE_DISTANCE),
        )
        assertEquals("""{"alternates":0,"costing":"auto"}""", RouteOptions.withoutAlternates("""{"alternates" : 2,"costing":"auto"}"""))
        // Never for walking.
        assertEquals(body, RouteOptions.withPrefs(body, TravelMode.WALK, RoutePrefs.DEFAULT, ShorterRoute.USE_DISTANCE))
    }

    @Test
    fun `via names the road the shorter way doesn't share`() {
        val roads = listOf("State Route 1" to 600.0, "Elm Street" to 230.0, null to 160.0)
        assertEquals("State Route 1", RouteSummary.longestRoad(roads))
        assertEquals("Elm Street", RouteSummary.longestRoad(roads, except = setOf("State Route 1", "Main Street")))
        assertNull(RouteSummary.longestRoad(roads, except = setOf("State Route 1", "Elm Street")))
    }
}
