package app.southbound.core.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRegionsTest {

    /** NYC (Manhattan) to Albany to Buffalo, as a straight polyline sampled finely. */
    private fun line(vararg stops: Pair<Double, Double>, per: Int = 40): List<Pair<Double, Double>> =
        stops.toList().zipWithNext().flatMap { (a, b) ->
            (0 until per).map { i -> (a.first + (b.first - a.first) * i / per) to (a.second + (b.second - a.second) * i / per) }
        } + stops.last()

    private val manhattan = 40.75 to -73.99
    private val albany = 42.65 to -73.76
    private val buffalo = 42.89 to -78.88

    @Test
    fun `route home to albany to buffalo lists regions not installed in order`() {
        val route = line(manhattan, albany, buffalo)
        val missing = RouteRegions.missing(route, installed = setOf("nyc")).map { it.id }
        assertEquals(listOf("hudson", "capital", "western"), missing)
    }

    @Test
    fun `nothing missing when every region on the way is installed`() {
        val route = line(manhattan, albany)
        assertTrue(RouteRegions.missing(route, setOf("nyc", "hudson", "capital")).isEmpty())
    }

    @Test
    fun `overlapping regions count as covered by any installed one`() {
        // Staten Island-ish point sits in nyc only; a point in the nyc/li overlap is covered by either.
        val overlap = listOf(40.7 to -73.75)
        assertTrue(RouteRegions.missing(overlap, setOf("li")).isEmpty())
        assertTrue(RouteRegions.missing(overlap, setOf("nyc")).isEmpty())
        assertEquals(listOf("nyc"), RouteRegions.missing(overlap, emptySet()).map { it.id })
    }

    @Test
    fun `new jersey is missing for a phone with only the new york city map`() {
        // Manhattan to Newark: NYC's box covers Newark, but the Newark map is northern New Jersey's.
        val newark = 40.7357 to -74.1724
        val route = line(manhattan, newark)
        assertEquals(listOf("njn"), RouteRegions.missing(route, setOf("nyc")).map { it.id })
        assertTrue(RouteRegions.missing(route, setOf("nyc", "njn")).isEmpty())
        // The reverse: with only the New Jersey map the New York end is missing.
        assertEquals(listOf("nyc"), RouteRegions.missing(route, setOf("njn")).map { it.id })
    }

    @Test
    fun `staten island is not new jersey`() {
        val statenIsland = listOf(40.5540 to -74.1510)
        assertTrue(RouteRegions.missing(statenIsland, setOf("nyc")).isEmpty())
        assertEquals(listOf("nyc"), RouteRegions.missing(statenIsland, setOf("njn")).map { it.id })
    }

    @Test
    fun `points outside every region and empty routes are ignored`() {
        assertTrue(RouteRegions.missing(emptyList(), emptySet()).isEmpty())
        assertTrue(RouteRegions.missing(listOf(34.0 to -118.0), emptySet()).isEmpty())
    }

    @Test
    fun `sampling still checks the last point`() {
        val route = List(5) { 40.75 to -73.99 } + (42.89 to -78.88)
        assertEquals(listOf("western"), RouteRegions.missing(route, setOf("nyc"), step = 100).map { it.id })
    }
}
