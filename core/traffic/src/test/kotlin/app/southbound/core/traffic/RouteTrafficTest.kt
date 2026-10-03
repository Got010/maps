package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTrafficTest {
    // A straight route going east along 40.70N, about 1.7 km (0.02 degrees of longitude).
    private val route = (0..20).map { GeoPoint(40.70, -74.00 + it * 0.001) }

    private fun link(id: String, fromLon: Double, toLon: Double, mph: Double, lat: Double = 40.70, seconds: Int? = null) =
        SpeedLink(id, id, "Manhattan", mph, seconds, listOf(GeoPoint(lat, fromLon), GeoPoint(lat, (fromLon + toLon) / 2), GeoPoint(lat, toLon)), asOf = 1L)

    @Test
    fun bandsFollowTheMapsSpeedLayer() {
        assertEquals(SpeedBand.STOPPED, SpeedBand.of(5.0))
        assertEquals(SpeedBand.SLOW, SpeedBand.of(10.0))
        assertEquals(SpeedBand.SLOW, SpeedBand.of(24.9))
        assertEquals(SpeedBand.MODERATE, SpeedBand.of(25.0))
        assertEquals(SpeedBand.FREE, SpeedBand.of(40.0))
    }

    @Test
    fun matchedLinkBecomesAColouredSegmentOfTheRoute() {
        val result = RouteTraffic.slowdowns(route, listOf(link("a", -73.995, -73.985, 8.0)))
        assertEquals(1, result.segments.size)
        val seg = result.segments[0]
        assertEquals(SpeedBand.STOPPED, seg.band)
        assertEquals(-73.995, seg.points.first().lon, 1e-6)
        assertEquals(-73.985, seg.points.last().lon, 1e-6)
        // Route vertices between the ends are kept so the stretch follows the road.
        assertTrue(seg.points.size >= 11)
    }

    @Test
    fun delayIsTravelTimeMinusFreeFlow() {
        // 0.01 degrees of longitude at 40.7N is about 843 m. At 45 mph (20.1 m/s) that is ~42 s.
        val result = RouteTraffic.slowdowns(route, listOf(link("a", -73.995, -73.985, 8.0, seconds = 240)))
        assertEquals(240.0 - 843.0 / (45 * 0.44704), result.delaySeconds, 3.0)
    }

    @Test
    fun withoutATravelTimeTheSpeedStandsIn() {
        val result = RouteTraffic.slowdowns(route, listOf(link("a", -73.995, -73.985, 9.0, seconds = null)))
        assertEquals(843.0 / (9 * 0.44704) - 843.0 / (45 * 0.44704), result.delaySeconds, 3.0)
    }

    @Test
    fun flowingLinksAreGreenAndAddNoDelay() {
        val result = RouteTraffic.slowdowns(route, listOf(link("a", -73.995, -73.985, 52.0, seconds = 10)))
        assertEquals(SpeedBand.FREE, result.segments.single().band)
        assertEquals(0.0, result.delaySeconds, 0.0)
    }

    @Test
    fun linkAgainstTheRouteIsTheOtherCarriageway() {
        val result = RouteTraffic.slowdowns(route, listOf(link("a", -73.985, -73.995, 8.0)))
        assertTrue(result.segments.isEmpty())
        assertEquals(0.0, result.delaySeconds, 0.0)
    }

    @Test
    fun linkOffTheRouteIsIgnored() {
        // 40 m north of the route: outside the 25 m match.
        val result = RouteTraffic.slowdowns(route, listOf(link("a", -73.995, -73.985, 8.0, lat = 40.70036)))
        assertTrue(result.segments.isEmpty())
        // 15 m north is inside it.
        assertEquals(1, RouteTraffic.slowdowns(route, listOf(link("a", -73.995, -73.985, 8.0, lat = 40.700135))).segments.size)
    }

    @Test
    fun linkThatOnlyTouchesOneEndIsIgnored() {
        val long = link("a", -73.995, -73.90, 8.0)
        assertTrue(RouteTraffic.slowdowns(route, listOf(long)).segments.isEmpty())
    }

    @Test
    fun segmentsComeOutInRouteOrderAndDelaysAdd() {
        val result = RouteTraffic.slowdowns(
            route,
            listOf(link("late", -73.99, -73.98, 12.0, seconds = 200), link("early", -73.998, -73.992, 20.0, seconds = 100)),
        )
        assertEquals(listOf(SpeedBand.SLOW, SpeedBand.SLOW), result.segments.map { it.band })
        assertTrue(result.segments[0].points.first().lon < result.segments[1].points.first().lon)
        assertTrue(result.delaySeconds > 200)
    }

    @Test
    fun noRouteOrNoLinksMeansNothing() {
        assertEquals(RouteSlowdowns.NONE, RouteTraffic.slowdowns(emptyList(), listOf(link("a", -73.995, -73.985, 8.0))))
        assertEquals(RouteSlowdowns.NONE, RouteTraffic.slowdowns(route, emptyList()))
    }

    private fun incident(id: String, lat: Double, lon: Double) =
        Incident(id, IncidentKind.ACCIDENT, "I-87", null, "Crash", GeoPoint(lat, lon), null, null, null)

    @Test
    fun incidentsWithin60MetersOfTheRouteInRouteOrder() {
        val found = RouteTraffic.incidentsOnRoute(
            route,
            listOf(
                incident("far", 40.7007, -73.99), // 78 m off
                incident("second", 40.70040, -73.980), // 44 m off
                incident("first", 40.70010, -73.995),
            ),
        )
        assertEquals(listOf("first", "second"), found.map { it.id })
    }

    @Test
    fun remainingStartsAtTheNearestPoint() {
        val rest = RouteTraffic.remaining(route, GeoPoint(40.70005, -73.9905))
        assertEquals(-73.9905, rest.first().lon, 1e-5)
        assertEquals(-73.98, rest.last().lon, 1e-6)
        assertEquals(12, rest.size)
    }

    @Test
    fun fasterRouteMustSaveFiveMinutes() {
        // Current: 20 min left plus 8 min of delay. Candidate: 22 min, no delay.
        val saving = FasterRoute.savingSeconds(1200.0, 480.0, 1320.0, 0.0)
        assertEquals(360.0, saving, 0.0)
        assertEquals(6, FasterRoute.offerMinutes(saving))
        assertNull(FasterRoute.offerMinutes(FasterRoute.savingSeconds(1200.0, 0.0, 1100.0, 0.0)))
        assertNull(FasterRoute.offerMinutes(FasterRoute.savingSeconds(1200.0, 0.0, 1300.0, 0.0)))
        assertNotNull(FasterRoute.offerMinutes(300.0))
    }

    @Test
    fun dismissedRouteIsNotOfferedAgainForTenMinutes() {
        val dismissals = FasterRoute.Dismissals()
        val offered = (0..40).map { GeoPoint(40.70 + it * 0.001, -74.0) }
        // Three minutes on, the same roads, minus the stretch already driven.
        val later = offered.drop(8)
        val different = (0..40).map { GeoPoint(40.70 + it * 0.001, -73.95) }
        assertFalse(dismissals.isDismissed(offered, 0))
        dismissals.dismiss(offered, 1_000)
        assertTrue(dismissals.isDismissed(later, 180_000))
        assertFalse(dismissals.isDismissed(different, 180_000))
        assertFalse(dismissals.isDismissed(later, 1_000 + FasterRoute.DISMISS_MILLIS))
    }
}
