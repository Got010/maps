package app.southbound.core.states

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingsTest {

    private val outlines get() = TestData.outlines

    private fun cross(route: List<StatePoint>) = Crossings.crossingsAlong(route, outlines)

    /** Metres along [route] to the point at index [i]. */
    private fun metersTo(route: List<StatePoint>, i: Int): Double =
        (1..i).sumOf { Geo.distanceMeters(route[it - 1].lat, route[it - 1].lon, route[it].lat, route[it].lon) }

    @Test
    fun `a route across a straight border has one crossing, placed at the line`() {
        // East to west across Kansas into Colorado, a point every 0.05 degrees (about 4 km).
        val route = TestData.along(38.5, -100.0, -104.0, 0.05)
        val c = cross(route).single()
        assertEquals("KS", c.from)
        assertEquals("CO", c.to)
        assertEquals("CO", outlines.stateAt(route[c.index].lat, route[c.index].lon))
        assertEquals("KS", outlines.stateAt(route[c.index - 1].lat, route[c.index - 1].lon))
        val truth = (TestData.KS_CO_LON - -100.0).let { -it } * TestData.M_PER_DEG_LON_385
        assertEquals(truth, c.distanceMetersFromStart, 300.0)
    }

    @Test
    fun `two points a hundred kilometres apart still place the border within a kilometre`() {
        val route = listOf(StatePoint(38.5, -101.5), StatePoint(38.5, -102.6))
        val c = cross(route).single()
        assertEquals(1, c.index)
        val truth = (102.0454 - 101.5) * TestData.M_PER_DEG_LON_385
        assertEquals(truth, c.distanceMetersFromStart, 1000.0)
    }

    @Test
    fun `a densely sampled crossing counts once`() {
        // A point every 0.001 degrees (about 90 m): many points inside Colorado before the first is a kilometre deep.
        val route = TestData.along(38.5, -102.10, -102.0, 0.001).reversed()
        val c = cross(route).single()
        assertEquals("KS", c.from)
        assertEquals("CO", c.to)
    }

    @Test
    fun `a road that hugs the line has no crossing until it leaves it`() {
        // Zig-zag a few hundred metres either side of the line for 40 km, then head 20 km into Colorado.
        val hugging = (0..80).map { i ->
            StatePoint(38.0 + i * 0.005, TestData.KS_CO_LON + if (i % 2 == 0) 0.003 else -0.003)
        }
        assertTrue(cross(hugging).isEmpty())
        val leaving = hugging + TestData.along(38.4, TestData.KS_CO_LON - 0.003, TestData.KS_CO_LON - 0.25, 0.01)
        val c = cross(leaving).single()
        assertEquals("KS", c.from)
        assertEquals("CO", c.to)
        assertTrue("the crossing is not before the road leaves the line", c.index >= hugging.size)
    }

    @Test
    fun `a short dip over the line and back is not a crossing`() {
        // One point 600 m inside Colorado, between Kansas points.
        val dip = TestData.along(38.5, -100.0, -102.0, 0.05) +
            StatePoint(38.5, TestData.KS_CO_LON - 600.0 / TestData.M_PER_DEG_LON_385) +
            TestData.along(38.5, -102.0, -100.0, 0.05)
        assertTrue(cross(dip).isEmpty())
    }

    @Test
    fun `a deep excursion is two crossings`() {
        val trip = TestData.along(38.5, -100.0, -102.0, 0.05) +
            TestData.along(38.5, -102.1, -102.4, 0.05) +
            TestData.along(38.5, -102.0, -100.0, 0.05)
        val c = cross(trip)
        assertEquals(listOf("KS" to "CO", "CO" to "KS"), c.map { it.from to it.to })
        assertTrue(c[0].distanceMetersFromStart < c[1].distanceMetersFromStart)
    }

    @Test
    fun `crossings come in order along a route through three states`() {
        // Kansas to Colorado to Utah, west along 38.5 N: Colorado is about 600 km wide there.
        val route = TestData.along(38.5, -101.0, -111.5, 0.1)
        val c = cross(route)
        assertEquals(listOf("KS" to "CO", "CO" to "UT"), c.map { it.from to it.to })
        assertTrue(c[0].distanceMetersFromStart < c[1].distanceMetersFromStart)
        assertTrue(c[0].index < c[1].index)
    }

    @Test
    fun `a route through Washington DC crosses into and out of it`() {
        // West to east along 38.89 N, from Virginia through DC into Maryland, a point every 0.005 degrees.
        val route = TestData.along(38.89, -77.25, -76.80, 0.005)
        val c = cross(route)
        assertEquals(listOf("VA" to "DC", "DC" to "MD"), c.map { it.from to it.to })
        assertTrue(c[0].distanceMetersFromStart in 12_000.0..20_000.0)
    }

    @Test
    fun `points out at sea keep the last known state`() {
        // The coast of North Carolina and then the Atlantic and back: no state change.
        val atSea = listOf(StatePoint(35.78, -78.64), StatePoint(35.0, -60.0), StatePoint(35.0, -61.0), StatePoint(35.78, -78.7))
        assertTrue(cross(atSea).isEmpty())
        // A gap of sea in the middle of a real crossing does not hide it.
        val gap = listOf(StatePoint(38.5, -101.0), StatePoint(35.0, -60.0), StatePoint(38.5, -103.0))
        assertEquals("CO", cross(gap).single().to)
        // A route wholly in the sea has none.
        assertTrue(cross(listOf(StatePoint(35.0, -60.0), StatePoint(36.0, -60.0))).isEmpty())
    }

    @Test
    fun `short and empty routes have no crossings`() {
        assertTrue(cross(emptyList()).isEmpty())
        assertTrue(cross(listOf(StatePoint(38.5, -100.0))).isEmpty())
    }

    @Test
    fun `the crossing index is the first point inside the new state`() {
        val route = TestData.along(38.5, -101.0, -103.0, 0.1)
        val c = cross(route).single()
        assertTrue(metersTo(route, c.index) >= c.distanceMetersFromStart)
        assertTrue(metersTo(route, c.index - 1) <= c.distanceMetersFromStart)
    }

    @Test
    fun `a long route is read in a few tens of milliseconds`() {
        // Kansas to Utah again with a point every 0.0005 degrees: 20 000 points.
        val route = TestData.along(38.5, -101.0, -111.0, 0.0005)
        val t0 = System.nanoTime()
        val c = cross(route)
        val ms = (System.nanoTime() - t0) / 1e6
        println("${route.size} route points, ${c.size} crossings, ${"%.1f".format(ms)} ms")
        assertEquals(2, c.size)
        assertTrue("took $ms ms", ms < 5_000)
    }
}
