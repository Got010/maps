package app.southbound.core.transit

import app.southbound.core.network.InMemoryNetworkToggles
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Collections

class TransitRealtimeTest {

    private val tripUpdates = File("src/test/resources/gtfsrt/trip_updates.pb").readBytes()
    private val alerts = File("src/test/resources/gtfsrt/alerts.pb").readBytes()
    // TransitRealtime fetches feeds in parallel on Dispatchers.IO, so the fake
    // fetcher records from several threads at once; a plain ArrayList drops adds.
    private val requested: MutableList<Pair<NetworkFeature, String>> = Collections.synchronizedList(ArrayList())

    private fun realtime(realtime: Boolean, bus: Boolean, key: String? = "SECRET", now: Long = 1_000_000L): TransitRealtime {
        val toggles = InMemoryNetworkToggles(mapOf(NetworkFeature.TRANSIT_REALTIME to realtime, NetworkFeature.TRANSIT_BUS_TIME to bus))
        val gate = NetworkGate(toggles)
        return TransitRealtime(gate, { key }, { now }) { feature, url ->
            gate.request(feature) {
                requested.add(feature to url)
                if (url.contains("alerts")) alerts else tripUpdates
            }
        }
    }

    @Test
    fun `off by default fetches nothing`() = runBlocking {
        assertNull(realtime(realtime = false, bus = false).snapshot(includeBus = true))
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `fetches every subway group, rail and alerts, never bus without asking`() = runBlocking {
        realtime(realtime = true, bus = true).snapshot(includeBus = false)
        val urls = requested.map { it.second }
        assertEquals(8, urls.count { "nyct%2Fgtfs" in it })
        assertTrue(urls.any { "lirr" in it } && urls.any { "mnr" in it } && urls.any { "all-alerts" in it })
        assertTrue(urls.none { "obanyc" in it })
        assertTrue(requested.all { it.first == NetworkFeature.TRANSIT_REALTIME })
    }

    @Test
    fun `bus time needs its toggle and a key`() = runBlocking {
        realtime(realtime = false, bus = true, key = null).snapshot(includeBus = true)
        assertTrue(requested.isEmpty())
        realtime(realtime = false, bus = true, key = "K&Y").snapshot(includeBus = true)
        assertEquals(listOf("https://gtfsrt.prod.obanyc.com/tripUpdates?key=K%26Y", "https://gtfsrt.prod.obanyc.com/alerts?key=K%26Y"),
            requested.map { it.second }.sorted().reversed())
        assertTrue(requested.all { it.first == NetworkFeature.TRANSIT_BUS_TIME })
    }

    @Test
    fun `caches for a short while`() = runBlocking {
        val rt = realtime(realtime = true, bus = false)
        rt.snapshot()
        val first = requested.size
        rt.snapshot()
        assertEquals(first, requested.size)
    }

    private fun stop(gtfsId: String) = TransitStop(1, TransitSystem.SUBWAY, gtfsId, gtfsId, 0.0, 0.0, null, false, 0)
    private fun trip(gtfsId: String) = TripRow(1, 1, 1, gtfsId, null, null, 0, ByteArray(0))
    private val date = LocalDate.of(2026, 10, 6)

    @Test
    fun `predictions match subway trips by the tail of their id`() = runBlocking {
        val snapshot = realtime(realtime = true, bus = false).snapshot()!!
        val trip = trip("L0S6-R-1097-S01_050950_R..N93R")
        val midnight = date.atStartOfDay(RealtimeSnapshot.NEW_YORK).toEpochSecond()
        val atR31 = snapshot.predict(TransitSystem.SUBWAY, trip, stop("R31N"), date, scheduled = 0)!!
        assertEquals((1_790_985_600L + 130 - midnight).toInt(), atR31.time)
        val atR30 = snapshot.predict(TransitSystem.SUBWAY, trip, stop("R30N"), date, scheduled = 30_000)!!
        assertEquals(30_000 - 45, atR30.time)
        assertTrue(snapshot.predict(TransitSystem.SUBWAY, trip, stop("R29N"), date, 0)!!.skipped)
        // Another day's trip with the same tail isn't this one.
        assertNull(snapshot.predict(TransitSystem.SUBWAY, trip, stop("R31N"), date.plusDays(1), 0))
    }

    @Test
    fun `rail trips use trip-wide delays and cancellations`() = runBlocking {
        // The fixture serves the same trip updates for every feed.
        val snapshot = realtime(realtime = true, bus = false).snapshot()!!
        val late = snapshot.predict(TransitSystem.LIRR, trip("GO202_26_1916"), stop("15"), date, scheduled = 30_000)!!
        assertEquals(300, late.delayFrom(30_000))
        assertTrue(snapshot.predict(TransitSystem.LIRR, trip("GONE"), stop("15"), date, 0)!!.canceled)
        assertNull(snapshot.predict(TransitSystem.LIRR, trip("UNKNOWN"), stop("15"), date, 0))
    }

    @Test
    fun `alerts go to the right system's route`() = runBlocking {
        val snapshot = realtime(realtime = true, bus = false).snapshot()!!
        fun route(system: TransitSystem, id: String) = TransitRoute(1, system, id, id, null, 1, null, null, null)
        val now = 1_790_985_600L
        assertEquals(1, snapshot.alertsFor(route(TransitSystem.SUBWAY, "A"), now).size)
        // Same route id, other agency: not this alert.
        assertTrue(snapshot.alertsFor(route(TransitSystem.BUS, "A"), now).isEmpty())
        assertTrue(snapshot.alertsFor(route(TransitSystem.SUBWAY, "A"), now + 7200).isEmpty())
    }

    private fun alert(id: String, header: String?, description: String?) =
        ServiceAlert(id, emptyList(), setOf(AlertRoute("LI", "1")), emptySet(), header, description, null)

    private fun alertsOnLine(vararg alerts: ServiceAlert): List<ServiceAlert> {
        val snapshot = RealtimeSnapshot(mapOf(TransitSystem.LIRR to listOf(RealtimeFeed(0, emptyList(), alerts.toList()))), fetchedAt = 0)
        return snapshot.alertsFor(TransitRoute(1, TransitSystem.LIRR, "1", "1", null, 2, null, null, null), 0)
    }

    @Test
    fun `alerts without a header are not all one alert`() {
        assertEquals(listOf("a", "b"), alertsOnLine(alert("a", null, "Track work"), alert("b", null, "Shuttle buses")).map { it.id })
        assertEquals(listOf("a", "b"), alertsOnLine(alert("a", null, null), alert("b", null, null)).map { it.id })
        // The same text twice is still one.
        assertEquals(listOf("a"), alertsOnLine(alert("a", null, "Track work"), alert("b", null, "Track work")).map { it.id })
        assertEquals(listOf("a"), alertsOnLine(alert("a", "Delays", "x"), alert("b", "Delays", "y")).map { it.id })
        // A header and another alert's description that read alike are two alerts.
        assertEquals(2, alertsOnLine(alert("a", "Delays", null), alert("b", null, "Delays")).size)
    }

    // A rail line over stops 1 to 5, ten minutes apart from 8:00.
    private val line = TestNetwork().apply {
        (1..5).forEach { stop(it) }
        route(1, listOf(1, 2, 3, 4, 5), listOf(TestNetwork.hm(8, 0), TestNetwork.hm(8, 10), TestNetwork.hm(8, 20), TestNetwork.hm(8, 30), TestNetwork.hm(8, 40)))
    }
    private val network = line.build()
    private val train = line.trips.single()

    private fun update(vararg stops: StopTimeUpdate, delay: Int? = null, on: LocalDate = date) =
        TripUpdate(TripDescriptor(train.gtfsId, "1", null, null, on.toString().replace("-", ""), false), stops.toList(), delay, null)

    private fun at(stop: Int, arrivalDelay: Int? = null, departureDelay: Int? = null, departureTime: Long? = null, skipped: Boolean = false) =
        StopTimeUpdate(
            null,
            "$stop",
            arrivalDelay?.let { StopTimeEvent(it, null) },
            if (departureDelay != null || departureTime != null) StopTimeEvent(departureDelay, departureTime) else null,
            skipped,
        )

    private fun snapshotOf(update: TripUpdate) =
        RealtimeSnapshot(mapOf(TransitSystem.LIRR to listOf(RealtimeFeed(0, listOf(update), emptyList()))), fetchedAt = 0)

    /** The predicted time at stop [stop] (scheduled ten minutes apart from 8:00), with the network. */
    private fun RealtimeSnapshot.timeAt(stop: Int, on: LocalDate = date, withNetwork: Boolean = true): Int? =
        predict(TransitSystem.LIRR, train, network.stop(stop)!!, on, TestNetwork.hm(8, 0) + (stop - 1) * 600,
            network = network.takeIf { withNetwork })?.time

    @Test
    fun `a delay carries to later stops until the next update`() {
        val snapshot = snapshotOf(update(at(2, arrivalDelay = 120), at(4, departureDelay = 300)))
        assertEquals(TestNetwork.hm(8, 10) + 120, snapshot.timeAt(2))
        assertEquals(TestNetwork.hm(8, 20) + 120, snapshot.timeAt(3))
        assertEquals(TestNetwork.hm(8, 30) + 300, snapshot.timeAt(4))
        assertEquals(TestNetwork.hm(8, 40) + 300, snapshot.timeAt(5))
        // Nothing before the first update, and nothing carried without the trip's stops.
        assertNull(snapshot.timeAt(1))
        assertNull(snapshot.timeAt(3, withNetwork = false))
    }

    @Test
    fun `a predicted time carries as its delay`() {
        val leaves = GtfsTime.dayStart(date) + TestNetwork.hm(8, 13) // three minutes late leaving stop 2
        val snapshot = snapshotOf(update(at(2, departureTime = leaves)))
        assertEquals(TestNetwork.hm(8, 13), snapshot.timeAt(2))
        assertEquals(TestNetwork.hm(8, 30) + 180, snapshot.timeAt(4))
    }

    @Test
    fun `a skipped stop passes the delay on, a stop with no data stops it`() {
        assertEquals(TestNetwork.hm(8, 20) + 60, snapshotOf(update(at(1, departureDelay = 60), at(2, skipped = true))).timeAt(3))
        val noData = StopTimeUpdate(null, "2", null, null, false)
        assertNull(snapshotOf(update(at(1, departureDelay = 60), noData)).timeAt(3))
        // The trip-wide delay still applies where nothing else does.
        assertEquals(TestNetwork.hm(8, 20) + 30, snapshotOf(update(at(1, departureDelay = 60), noData, delay = 30)).timeAt(3))
        assertEquals(TestNetwork.hm(8, 20) + 30, snapshotOf(update(at(4, departureDelay = 60), delay = 30)).timeAt(3))
    }

    @Test
    fun `gtfs times count from noon minus twelve hours`() {
        val newYork = RealtimeSnapshot.NEW_YORK
        // An ordinary day: midnight.
        assertEquals(date.atStartOfDay(newYork).toEpochSecond(), GtfsTime.dayStart(date))
        // Spring forward, 2026-03-08: 23:00 the evening before, so 08:00:00 is 8 am EDT.
        val spring = LocalDate.of(2026, 3, 8)
        assertEquals(OffsetDateTime.of(2026, 3, 7, 23, 0, 0, 0, ZoneOffset.ofHours(-5)).toEpochSecond(), GtfsTime.dayStart(spring))
        assertEquals(ZonedDateTime.of(2026, 3, 8, 8, 0, 0, 0, newYork).toEpochSecond(), GtfsTime.dayStart(spring) + 8 * 3600)
        // Fall back, 2026-11-01: the first 01:00 (EDT), so 08:00:00 is 8 am EST.
        val fall = LocalDate.of(2026, 11, 1)
        assertEquals(OffsetDateTime.of(2026, 11, 1, 1, 0, 0, 0, ZoneOffset.ofHours(-4)).toEpochSecond(), GtfsTime.dayStart(fall))
        assertEquals(ZonedDateTime.of(2026, 11, 1, 8, 0, 0, 0, newYork).toEpochSecond(), GtfsTime.dayStart(fall) + 8 * 3600)
        assertEquals(8 * 3600 + 300, GtfsTime.secondsOf(ZonedDateTime.of(2026, 11, 1, 8, 5, 0, 0, newYork).toEpochSecond(), fall))
    }

    @Test
    fun `predicted times match the timetable on the days the clocks change`() {
        for (day in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
            // Five minutes late at stop 3, due 8:20 on the wall clock.
            val due = ZonedDateTime.of(day, LocalTime.of(8, 25), RealtimeSnapshot.NEW_YORK).toEpochSecond()
            val snapshot = snapshotOf(update(at(3, departureTime = due), on = day))
            assertEquals("$day", TestNetwork.hm(8, 25), snapshot.timeAt(3, on = day))
            // And the delay it carries is five minutes, not an hour and five.
            assertEquals("$day", TestNetwork.hm(8, 35), snapshot.timeAt(4, on = day))
        }
    }
}
