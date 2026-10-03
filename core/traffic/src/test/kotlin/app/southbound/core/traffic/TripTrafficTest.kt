package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrafficTest {
    // A straight route going east along 40.70N, about 1.7 km (0.02 degrees of longitude; ~84 m per point).
    private val route = (0..20).map { GeoPoint(40.70, -74.00 + it * 0.001) }

    private fun link(id: String, fromLon: Double, toLon: Double, mph: Double, seconds: Int? = null) =
        SpeedLink(id, id, "Manhattan", mph, seconds, listOf(GeoPoint(40.70, fromLon), GeoPoint(40.70, (fromLon + toLon) / 2), GeoPoint(40.70, toLon)), asOf = 1L)

    private fun at(lon: Double) = GeoPoint(40.70, lon)

    // A crawl from -73.995 to -73.985 (~843 m): 240 s against ~42 s at free flow.
    private val slow = link("slow", -73.995, -73.985, 8.0, seconds = 240)

    @Test
    fun fromTheStartTheDelayIsTheWholeRoutes() {
        val trip = TripTraffic.match(route, listOf(slow))
        val ahead = trip.ahead(at(-74.0))
        assertEquals(RouteTraffic.slowdowns(route, listOf(slow)).delaySeconds, ahead.delaySeconds, 1e-6)
        assertEquals(1, ahead.segments.size)
    }

    @Test
    fun delayShrinksAsTheDriverMovesThroughTheSlowStretch() {
        val trip = TripTraffic.match(route, listOf(slow))
        val before = trip.ahead(at(-73.997)).delaySeconds
        val quarter = trip.ahead(at(-73.9925)).delaySeconds
        val half = trip.ahead(at(-73.990)).delaySeconds
        val nearlyOut = trip.ahead(at(-73.986)).delaySeconds
        val past = trip.ahead(at(-73.984))
        assertEquals(198.0, before, 3.0)
        assertEquals(before * 0.75, quarter, 1.0)
        assertEquals(before * 0.5, half, 1.0)
        assertTrue(nearlyOut in 0.0..(before * 0.15))
        assertEquals(0.0, past.delaySeconds, 0.0)
        assertTrue(past.segments.isEmpty())
    }

    @Test
    fun theStretchBeingDrivenIsDrawnOnlyFromTheDriverOn() {
        val trip = TripTraffic.match(route, listOf(slow))
        val seg = trip.ahead(at(-73.990)).segments.single()
        assertEquals(-73.990, seg.points.first().lon, 1e-6)
        assertEquals(-73.985, seg.points.last().lon, 1e-6)
    }

    @Test
    fun passedLinksDropOffAndLaterOnesStay() {
        val early = link("early", -73.998, -73.994, 12.0, seconds = 120)
        val late = link("late", -73.988, -73.984, 15.0, seconds = 100)
        val trip = TripTraffic.match(route, listOf(late, early))
        val start = trip.ahead(at(-74.0))
        assertEquals(2, start.segments.size)
        val between = trip.ahead(at(-73.991))
        assertEquals(1, between.segments.size)
        assertEquals(RouteTraffic.slowdowns(route, listOf(late)).delaySeconds, between.delaySeconds, 1e-6)
    }

    @Test
    fun noSpeedsMeansNoDelay() {
        val trip = TripTraffic.match(route, emptyList())
        assertEquals(RouteSlowdowns.NONE, trip.ahead(at(-74.0)))
        assertEquals(RouteSlowdowns.NONE, TripTraffic.NONE.ahead(at(-74.0)))
        assertEquals(RouteSlowdowns.NONE, TripTraffic.match(emptyList(), listOf(slow)).ahead(at(-74.0)))
    }

    @Test
    fun flowingLinksAreDrawnButAddNothing() {
        val trip = TripTraffic.match(route, listOf(link("free", -73.995, -73.985, 50.0, seconds = 40)))
        val ahead = trip.ahead(at(-73.99))
        assertEquals(1, ahead.segments.size)
        assertEquals(0.0, ahead.delaySeconds, 0.0)
    }

    @Test
    fun onARouteThatComesBackTheDriverStaysOnTheWayBack() {
        // East 1.7 km, then back west on the same line: a trip out to a stop and back.
        val outAndBack = route + route.reversed().drop(1)
        val trip = TripTraffic.match(outAndBack, listOf(slow))
        val total = trip.lengthMeters
        assertEquals(198.0, trip.ahead(0.0).delaySeconds, 3.0)
        // At the turnaround the driver is half way. Coming back past -73.99 they must be placed on the way
        // back (~2.5 km in), not on the way out (~850 m in), so the eastbound crawl is all behind them.
        val turn = trip.along(at(-73.98), notBefore = total / 2 - 50)
        assertEquals(total / 2, turn, 20.0)
        val back = trip.along(at(-73.99), notBefore = turn)
        assertEquals(total / 2 + 843, back, 20.0)
        assertEquals(0.0, trip.ahead(back).delaySeconds, 0.0)
        // Without the last position the way out is found, which is why the trip passes it on.
        assertEquals(843.0, trip.along(at(-73.99)), 20.0)
    }

    @Test
    fun aFixFarFromTheRouteKeepsTheLastPosition() {
        val trip = TripTraffic.match(route, listOf(slow))
        assertEquals(500.0, trip.along(GeoPoint(41.5, -73.0), notBefore = 500.0), 0.0)
    }

    private fun incident(id: String, lat: Double, lon: Double) =
        Incident(id, IncidentKind.ACCIDENT, "FDR", null, "Crash", GeoPoint(lat, lon), null, null, null)

    @Test
    fun incidentsAheadDropOnceDriven() {
        val trip = TripTraffic.match(
            route,
            emptyList(),
            listOf(incident("second", 40.7003, -73.985), incident("off", 40.71, -73.99), incident("first", 40.7001, -73.995)),
        )
        assertEquals(listOf("first", "second"), trip.incidentsAhead(0.0).map { it.id })
        assertEquals(listOf("second"), trip.incidentsAhead(trip.along(at(-73.99))).map { it.id })
    }

    @Test
    fun rankingPutsTheFastestWithTrafficFirst() {
        // Valhalla's first route is 20 min but has 9 min of delay; the second is 23 min with none.
        assertEquals(listOf(1, 0, 2), TrafficRanking.order(listOf(1200.0, 1380.0, 1500.0), listOf(540.0, 0.0, 0.0)))
        // The third gains even more, so it goes first and the rest keep Valhalla's order.
        assertEquals(listOf(2, 0, 1), TrafficRanking.order(listOf(1200.0, 1380.0, 1300.0), listOf(540.0, 0.0, 0.0)))
    }

    @Test
    fun rankingKeepsValhallasOrderForSmallGainsOrNoTraffic() {
        assertEquals(listOf(0, 1, 2), TrafficRanking.order(listOf(1200.0, 1260.0, 1300.0), emptyList()))
        // Valhalla put a 25 min route ahead of a 20 min one for its own reasons (turns, road classes): with no
        // delay anywhere, or the same delay on both, that stands.
        assertEquals(listOf(0, 1), TrafficRanking.order(listOf(1500.0, 1200.0), listOf(0.0, 0.0)))
        assertEquals(listOf(0, 1), TrafficRanking.order(listOf(1500.0, 1200.0), listOf(300.0, 300.0)))
        // 90 s faster with traffic: under the 2 min needed to flip.
        assertEquals(listOf(0, 1), TrafficRanking.order(listOf(1200.0, 1290.0), listOf(180.0, 0.0)))
        assertEquals(listOf(0), TrafficRanking.order(listOf(1200.0), listOf(600.0)))
        assertEquals(emptyList<Int>(), TrafficRanking.order(emptyList(), emptyList()))
    }

    @Test
    fun rankingSkipsRoutesThatMayNotLead() {
        val order = TrafficRanking.order(listOf(1200.0, 1300.0, 1380.0), listOf(540.0, 0.0, 0.0), mayLead = { it != 1 })
        assertEquals(listOf(2, 0, 1), order)
        assertEquals(listOf("c", "a", "b"), TrafficRanking.reorder(listOf("a", "b", "c"), order))
        assertEquals(listOf("a", "b"), TrafficRanking.reorder(listOf("a", "b"), order))
    }
}
