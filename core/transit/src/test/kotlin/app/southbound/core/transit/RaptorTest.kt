package app.southbound.core.transit

import app.southbound.core.transit.TestNetwork.Companion.hm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RaptorTest {

    /**
     * 1 -A-> 2 -A-> 3 -B-> 4, a slow direct C from 1 to 4, and a short walk
     * from 2 to 6 where D runs fast to 4.
     */
    private fun network(changeAt3: Int = 60, withD: Boolean = true) = TestNetwork().apply {
        (1..6).forEach { stop(it, changeSeconds = if (it == 3) changeAt3 else 0) }
        route(1, listOf(1, 2, 3), listOf(hm(8, 0), hm(8, 5), hm(8, 10)), listOf(hm(8, 10), hm(8, 15), hm(8, 20)))
        route(2, listOf(3, 4), listOf(hm(8, 10) + 30, hm(8, 35)), listOf(hm(8, 25), hm(8, 40)))
        route(3, listOf(1, 4), listOf(hm(8, 5), hm(8, 50)))
        if (withD) {
            route(4, listOf(6, 4), listOf(hm(8, 15), hm(8, 30)))
            walk(2, 6, 120)
        }
    }

    private fun search(net: TestNetwork, departAt: Int): List<Raptor.RawJourney> {
        val network = net.build()
        val tt = Timetable.build(network, mapOf(0 to net.trips))
        return Raptor(tt).search(listOf(StopAccess(1, 0)), listOf(StopAccess(4, 0)), departAt)
    }

    @Test
    fun `finds the trade-off between changes and arrival`() {
        val results = search(network(), hm(7, 55))
        assertEquals(listOf(1 to hm(8, 50), 2 to hm(8, 30)), results.map { it.rides to it.arrival })
        val legs = results[1].legs
        assertTrue(legs[1] is Raptor.RawLeg.Ride)
        assertEquals(Raptor.RawLeg.Walk(2, 6, 120), legs[2])
    }

    @Test
    fun `respects the minimum change time`() {
        assertEquals(hm(8, 40), search(network(changeAt3 = 60, withD = false), hm(7, 55)).last().arrival)
        assertEquals(hm(8, 35), search(network(changeAt3 = 0, withD = false), hm(7, 55)).last().arrival)
    }

    @Test
    fun `misses trips that already left`() {
        // After 8:05 only the second A trip and the 8:25 B remain; C has gone.
        val results = search(network(withD = false), hm(8, 6))
        assertEquals(listOf(2 to hm(8, 40)), results.map { it.rides to it.arrival })
    }

    @Test
    fun `no journey when nothing runs`() {
        assertTrue(search(network(), hm(9, 0)).isEmpty())
    }

    private fun planner(net: TestNetwork) = TransitPlanner(net.build(), net.source())

    private val tuesday = LocalDate.of(2026, 9, 8)

    @Test
    fun `planner builds legs with times and stops`() {
        val net = network()
        val stop1 = net.build().stop(1)!!
        val stop4 = net.build().stop(4)!!
        val journeys = planner(net).plan(stop1.lat, stop1.lon, stop4.lat, stop4.lon, tuesday, hm(7, 55), options = 1)
        val fastest = journeys.first()
        assertEquals(hm(8, 30), fastest.arrive)
        assertEquals(hm(8, 0), fastest.depart)
        assertEquals(1, fastest.transfers)
        val first = fastest.rides.first()
        assertEquals(listOf(1, 2), first.stops.map { it.id })
        assertEquals(listOf(hm(8, 0), hm(8, 5)), first.times)
        val walk = fastest.legs[1] as JourneyLeg.Walk
        assertEquals(hm(8, 5) to hm(8, 7), walk.depart to walk.arrive)
    }

    @Test
    fun `planner prefers the later departure that arrives as early`() {
        val net = network(withD = false)
        val s1 = net.build().stop(1)!!
        val s4 = net.build().stop(4)!!
        // The 8:00 and 8:10 A trips both make the 8:25 B; the direct C arrives
        // 8:50. Only leaving at 8:10 is worth showing.
        val journeys = planner(net).plan(s1.lat, s1.lon, s4.lat, s4.lon, tuesday, hm(7, 55), options = 3)
        assertEquals(listOf(hm(8, 10) to hm(8, 40)), journeys.map { it.depart to it.arrive })
    }

    @Test
    fun `pruning keeps a slower trip with fewer changes when the saving is small`() {
        val net = network()
        val planner = planner(net)
        fun journey(depart: Int, arrive: Int, rides: Int): Journey {
            val stop = net.build().stop(1)!!
            val route = net.build().route(1)!!
            val trip = net.trips.first()
            return Journey(List(rides) { JourneyLeg.Ride(route, null, trip, listOf(stop, stop), listOf(depart, arrive), depart, arrive) })
        }
        val direct = journey(hm(8, 0), hm(8, 50), 1)
        val oneChangeMuchFaster = journey(hm(8, 0), hm(8, 30), 2)
        val twoChangesBarelyFaster = journey(hm(8, 0), hm(8, 48), 3)
        val kept = planner.pruned(listOf(direct, oneChangeMuchFaster, twoChangesBarelyFaster))
        assertEquals(listOf(oneChangeMuchFaster), kept)
        assertEquals(listOf(direct), planner.pruned(listOf(direct, twoChangesBarelyFaster)))
    }

    @Test
    fun `arrive-by finds the latest departure that makes it`() {
        val net = network()
        val s1 = net.build().stop(1)!!
        val s4 = net.build().stop(4)!!
        val journeys = planner(net).plan(s1.lat, s1.lon, s4.lat, s4.lon, tuesday, hm(8, 45), arriveBy = true, options = 1)
        assertTrue(journeys.isNotEmpty())
        journeys.forEach { assertTrue("arrives ${it.arrive}", it.arrive <= hm(8, 45)) }
        // The 8:10 A still makes the 8:25 B, arriving 8:40.
        val best = journeys.maxBy { it.depart }
        assertEquals(hm(8, 10), best.depart)
        assertEquals(hm(8, 40), best.arrive)
        // Legs come back in travel order with forward times.
        assertEquals(1, best.rides.first().from.id)
        assertEquals(4, best.rides.last().to.id)
        assertTrue(best.legs.zipWithNext().all { (a, b) -> a.arrive <= b.depart })
    }

    @Test
    fun `trips after midnight belong to the previous service day`() {
        val net = TestNetwork().apply {
            stop(1)
            stop(2, lat = 40.1) // too far to walk
            route(1, listOf(1, 2), listOf(hm(24, 30), hm(24, 50)))
        }
        val s1 = net.build().stop(1)!!
        val s2 = net.build().stop(2)!!
        val journeys = planner(net).plan(s1.lat, s1.lon, s2.lat, s2.lon, tuesday, hm(0, 20))
        assertEquals(hm(0, 30), journeys.single().depart)
        assertEquals(hm(0, 50), journeys.single().arrive)
    }

    @Test
    fun `short trips include walking the whole way`() {
        val net = network()
        val journeys = planner(net).plan(40.0, -74.0, 40.005, -74.0, tuesday, hm(8, 0))
        val walk = journeys.single().legs.single() as JourneyLeg.Walk
        assertEquals(hm(8, 0), walk.depart)
    }
}
