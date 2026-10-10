package app.southbound.core.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TripRegionsTest {

    private val manhattan = 40.75 to -73.99
    private val newark = 40.7347 to -74.1644
    private val philadelphia = 39.9526 to -75.1652
    private val allentown = 40.6023 to -75.4714

    private val own = setOf("nyc")
    private val trip = setOf("njn", "pae")

    private fun fix(at: Pair<Double, Double>, visited: Set<String> = emptySet(), navigating: Boolean = false) =
        TripRegions.onFix(at.first, at.second, own, trip, visited, navigating)

    @Test
    fun `fetched at home the night before, nothing goes`() {
        // Home is an own region, but the phone hasn't been in either trip region yet: the trip hasn't happened.
        assertTrue(fix(manhattan).isEmpty)
        assertTrue(fix(manhattan, navigating = true).isEmpty)
    }

    @Test
    fun `being in a trip region marks it`() {
        assertEquals(TripRegions.Step(setOf("njn"), emptySet()), fix(newark, navigating = true))
        assertEquals(TripRegions.Step(setOf("pae"), emptySet()), fix(philadelphia, visited = setOf("njn")))
        // Once marked, it isn't again.
        assertTrue(fix(allentown, visited = setOf("njn", "pae")).isEmpty)
    }

    @Test
    fun `back home after the trip, the regions it went through go`() {
        assertEquals(TripRegions.Step(emptySet(), setOf("njn", "pae")), fix(manhattan, visited = setOf("njn", "pae")))
    }

    @Test
    fun `only the trip regions the phone was in go`() {
        // Philadelphia was fetched too but the phone only got as far as Newark: Philadelphia's trip is still ahead.
        assertEquals(TripRegions.Step(emptySet(), setOf("njn")), fix(manhattan, visited = setOf("njn")))
    }

    @Test
    fun `nothing goes while navigating, even home`() {
        assertTrue(fix(manhattan, visited = setOf("njn", "pae"), navigating = true).isEmpty)
    }

    @Test
    fun `nothing goes outside the own regions`() {
        // Albany is in no own or trip region here.
        assertTrue(fix(42.65 to -73.76, visited = setOf("njn", "pae")).isEmpty)
    }

    @Test
    fun `no trip regions, nothing to do`() {
        assertTrue(TripRegions.onFix(manhattan.first, manhattan.second, own, emptySet(), emptySet(), false).isEmpty)
    }

    @Test
    fun `the regions at the ends of a trip, for when there's no route`() {
        // Directions need local roads at the start, stops and destination: Philadelphia's region is missing.
        val ends = listOf(manhattan, philadelphia)
        assertEquals(listOf("pae"), RouteRegions.missing(ends, setOf("nyc"), step = 1).map { it.id })
        assertTrue(RouteRegions.missing(ends, setOf("nyc", "pae"), step = 1).isEmpty())
    }

    @Test
    fun `what fetching regions adds to the download`() {
        val m = ReleaseManifest.parseV2(File("src/test/resources/southbound-v2.json").readText())
        val bytes = { name: String -> m.packs.first { it.file.name == name }.file.size }
        // Connecticut beside New York City: its map, search and its own routing pack; the shared ones are there.
        assertEquals(
            bytes("basemap-ct.pmtiles") + bytes("routing-ct.tar") + bytes("search-ct.sqlite"),
            UpdatePlan.addedBytes(m, setOf("nyc"), setOf("ct")),
        )
        // Nothing new: a region already picked.
        assertEquals(0L, UpdatePlan.addedBytes(m, setOf("nyc", "ct"), setOf("ct")))
    }
}
