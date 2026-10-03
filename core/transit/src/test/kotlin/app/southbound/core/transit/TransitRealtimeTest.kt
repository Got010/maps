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

class TransitRealtimeTest {

    private val tripUpdates = File("src/test/resources/gtfsrt/trip_updates.pb").readBytes()
    private val alerts = File("src/test/resources/gtfsrt/alerts.pb").readBytes()
    private val requested = ArrayList<Pair<NetworkFeature, String>>()

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
}
