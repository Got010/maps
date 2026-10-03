package app.southbound.core.traffic

import app.southbound.core.network.InMemoryNetworkToggles
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class TrafficFeedTest {

    private val events = File("src/test/resources/511ny-events.json").readBytes()
    private val speeds = File("src/test/resources/nycdot-speeds.json").readBytes()
    private val requested = ArrayList<Pair<NetworkFeature, String>>()
    private var now = 1_790_000_000_000L
    private var fail = false

    private fun feed(ny511: Boolean, dot: Boolean, key: String? = "KEY"): TrafficFeed {
        val toggles = InMemoryNetworkToggles(mapOf(NetworkFeature.TRAFFIC_511NY to ny511, NetworkFeature.TRAFFIC_NYC_DOT to dot))
        val gate = NetworkGate(toggles)
        return TrafficFeed(gate, { key }, { now }) { feature, url ->
            gate.request(feature) {
                requested.add(feature to url)
                if (fail) throw IOException("HTTP 503 from somewhere")
                if ("511ny" in url) events else speeds
            }
        }
    }

    @Test
    fun `off by default fetches nothing`() = runBlocking {
        val feed = feed(ny511 = false, dot = false)
        assertFalse(feed.isEnabled)
        assertTrue(feed.refresh().isEmpty)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `511NY needs its toggle and a key`() = runBlocking {
        assertTrue(feed(ny511 = true, dot = false, key = null).refresh().incidents.isEmpty())
        assertTrue(requested.isEmpty())
        val snapshot = feed(ny511 = true, dot = false).refresh()
        assertEquals(8, snapshot.incidents.size)
        assertEquals(3, snapshot.closures.size)
        assertEquals(listOf(NetworkFeature.TRAFFIC_511NY), requested.map { it.first })
        assertTrue(requested.single().second.startsWith("https://511ny.org/api/getevents?key=KEY"))
    }

    @Test
    fun `NYC DOT readings that are all too old are flagged stale, not an error`() = runBlocking {
        val feed = feed(ny511 = false, dot = true)
        val newest = NycDotSpeeds.parseDate("2026-10-02T22:16:04.000")!!
        now = newest + 3 * 24 * 60 * 60_000L
        val snapshot = feed.refresh()
        assertTrue(snapshot.speeds.isEmpty())
        assertTrue(snapshot.errors.isEmpty())
        assertTrue(snapshot.speedsStale)
        // Still flagged while the cached result is served.
        now += 10_000
        assertTrue(feed.refresh().speedsStale)
        // Fresh readings clear it.
        now = newest + 60_000
        assertFalse(feed.refresh(force = true).speedsStale)
    }

    @Test
    fun `a missing 511NY key is visible`() {
        assertTrue(feed(ny511 = true, dot = false, key = null).needsIncidentsKey)
        assertTrue(feed(ny511 = true, dot = false, key = "  ").needsIncidentsKey)
        assertFalse(feed(ny511 = true, dot = false).needsIncidentsKey)
        assertFalse(feed(ny511 = false, dot = false, key = null).needsIncidentsKey)
    }

    @Test
    fun `each feed is cached for its own interval`() = runBlocking {
        val feed = feed(ny511 = true, dot = true)
        feed.refresh()
        assertEquals(2, requested.size)
        now += 60_000
        feed.refresh()
        assertEquals(2, requested.size)
        now += 40_000 // 100 s: speeds are stale, incidents not yet
        feed.refresh()
        assertEquals(3, requested.size)
        assertEquals(NetworkFeature.TRAFFIC_NYC_DOT, requested.last().first)
        now += 100_000
        feed.refresh()
        assertEquals(5, requested.size)
        feed.refresh(force = true)
        assertEquals(7, requested.size)
    }

    @Test
    fun `a failing feed keeps its last data and reports the error`() = runBlocking {
        val feed = feed(ny511 = true, dot = true)
        val first = feed.refresh()
        assertTrue(first.errors.isEmpty())
        now += 10 * 60_000
        fail = true
        val second = feed.refresh()
        assertEquals(first.incidents, second.incidents)
        assertEquals(first.speeds.size, second.speeds.size)
        assertEquals(2, second.errors.size)
        assertTrue(second.errors.any { it.startsWith("511NY: HTTP 503") })
        assertEquals(second, feed.snapshot.value)
    }
}
