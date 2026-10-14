package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShorterRouteTest {

    // Vails Gate, measured on the release's tiles: Route 94 to the ShopRite by Windsor Highway or Old Temple Hill Road.
    private val windsor = ShorterRoute.Option(127.0, 1440.0, listOf(41.4565 to -74.0480, 41.4535 to -74.0573, 41.4575 to -74.0560))
    private val oldTempleHill = ShorterRoute.Option(106.0, 1053.0, listOf(41.4565 to -74.0480, 41.4550 to -74.0552, 41.4575 to -74.0571))
    private val forgeHill = ShorterRoute.Option(133.0, 1133.0, listOf(41.4565 to -74.0480, 41.4570 to -74.0540, 41.4575 to -74.0560))

    private fun merge(options: List<ShorterRoute.Option>, shorter: ShorterRoute.Option?) = ShorterRoute.merge(options, shorter) { it }

    @Test
    fun `a faster and shorter way is offered after the others`() {
        assertEquals(listOf(windsor, forgeHill, oldTempleHill), merge(listOf(windsor, forgeHill), oldTempleHill))
        // With two alternatives already, it takes the last one's place: three options, three colours.
        val third = forgeHill.copy(line = listOf(41.0 to -74.0, 41.1 to -74.1))
        assertEquals(listOf(windsor, forgeHill, oldTempleHill), merge(listOf(windsor, forgeHill, third), oldTempleHill))
    }

    @Test
    fun `not when it is slower or longer than the first`() {
        assertEquals(listOf(windsor), merge(listOf(windsor), oldTempleHill.copy(seconds = 130.0)))
        assertEquals(listOf(windsor), merge(listOf(windsor), oldTempleHill.copy(meters = 1500.0)))
        assertEquals(listOf(windsor), merge(listOf(windsor), null))
        assertEquals(emptyList<ShorterRoute.Option>(), merge(emptyList(), oldTempleHill))
    }

    @Test
    fun `not when it is a route already offered`() {
        // The same line a few metres off, a little faster by rounding: no second copy.
        val again = windsor.copy(seconds = 120.0, meters = 1400.0, line = windsor.line.map { (lat, lon) -> lat + 0.00005 to lon })
        assertEquals(listOf(windsor, forgeHill), merge(listOf(windsor, forgeHill), again))
    }

    @Test
    fun `the shorter way is only where merge put it`() {
        val merged = merge(listOf(windsor, forgeHill), oldTempleHill)
        assertEquals(2, ShorterRoute.indexIn(merged, oldTempleHill))
        // Traffic may reorder the options; the shorter way is found wherever it went.
        assertEquals(0, ShorterRoute.indexIn(listOf(oldTempleHill, windsor, forgeHill), oldTempleHill))
        // Valhalla's distance-weighted answer can be the first route itself. Merge refuses it, and an equal copy is
        // no shorter way: before, it was found "in" the one option and the preview crashed telling it apart.
        val again = windsor.copy()
        assertEquals(listOf(windsor), merge(listOf(windsor), again))
        assertNull(ShorterRoute.indexIn(listOf(windsor), again))
        // Nor is an alternative it happens to equal.
        assertNull(ShorterRoute.indexIn(listOf(windsor, forgeHill), forgeHill.copy()))
        assertNull(ShorterRoute.indexIn(listOf(windsor), null))
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
        val roads = listOf("NY 94" to 600.0, "Old Temple Hill Road" to 230.0, null to 160.0)
        assertEquals("NY 94", RouteSummary.longestRoad(roads))
        assertEquals("Old Temple Hill Road", RouteSummary.longestRoad(roads, except = setOf("NY 94", "Windsor Highway")))
        assertNull(RouteSummary.longestRoad(roads, except = setOf("NY 94", "Old Temple Hill Road")))
    }
}
