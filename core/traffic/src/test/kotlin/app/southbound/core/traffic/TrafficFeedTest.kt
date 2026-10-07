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

    private val incidents = File("src/test/resources/511ny-cars-incidents.json").readBytes()
    private val closures = File("src/test/resources/511ny-cars-closures.json").readBytes()
    private val construction = File("src/test/resources/511ny-cars-construction.json").readBytes()
    private val speeds = File("src/test/resources/nycdot-speeds.json").readBytes()
    private val requested = ArrayList<Pair<NetworkFeature, String>>()
    private var now = 1_790_000_000_000L
    private var fail = false

    /** Changes every layer's hash, as 511NY does when an event changes. */
    private var version = 1

    private fun feed(ny511: Boolean, dot: Boolean): TrafficFeed {
        val toggles = InMemoryNetworkToggles(mapOf(NetworkFeature.TRAFFIC_511NY to ny511, NetworkFeature.TRAFFIC_NYC_DOT to dot))
        val gate = NetworkGate(toggles)
        return TrafficFeed(gate, { now }) { feature, url ->
            gate.request(feature) {
                synchronized(requested) { requested.add(feature to url) }
                if (fail) throw IOException("HTTP 503 from somewhere")
                when {
                    "/events/hash?" in url -> """{"hash":"${url.substringAfter("eventClassifications=").substringBefore('&')}-$version"}""".toByteArray()
                    "eventClassifications=incidents" in url -> incidents
                    "eventClassifications=closures" in url -> closures
                    "eventClassifications=construction" in url -> construction
                    "data.cityofnewyork.us" in url -> speeds
                    else -> throw IOException("unexpected $url")
                }
            }
        }
    }

    private fun downloads() = synchronized(requested) { requested.count { "/map-features" in it.second } }
    private fun dotRequests() = synchronized(requested) { requested.count { it.first == NetworkFeature.TRAFFIC_NYC_DOT } }

    @Test
    fun `off by default fetches nothing`() = runBlocking {
        val feed = feed(ny511 = false, dot = false)
        assertFalse(feed.isEnabled)
        assertTrue(feed.refresh().isEmpty)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `511NY needs only its toggle and reads the three event layers whole`() = runBlocking {
        val feed = feed(ny511 = true, dot = false)
        assertTrue(feed.hasIncidents)
        val snapshot = feed.refresh()
        assertEquals(9, snapshot.incidents.size)
        assertEquals(3, snapshot.closures.size)
        assertEquals(setOf(NetworkFeature.TRAFFIC_511NY), requested.map { it.first }.toSet())
        // The site's own whole-state queries: nothing about where the user is.
        assertEquals(
            listOf(
                "https://api-511x-nysdot.carsprogram.org/events/map-features?eventClassifications=closures&maxBeginDateOffset=300000",
                "https://api-511x-nysdot.carsprogram.org/events/map-features?eventClassifications=incidents",
                "https://api-511x-nysdot.carsprogram.org/events/map-features?eventClassifications=construction&maxBeginDateOffset=300000",
            ),
            requested.map { it.second }.filter { "/map-features" in it },
        )
    }

    @Test
    fun `an unchanged layer is not downloaded again`() = runBlocking {
        val feed = feed(ny511 = true, dot = false)
        val first = feed.refresh()
        assertEquals(3, downloads())
        val again = feed.refresh(force = true)
        assertEquals(3, downloads()) // three hash checks, no downloads
        assertEquals(first.incidents, again.incidents)
        version++
        feed.refresh(force = true)
        assertEquals(6, downloads())
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
    fun `each feed is cached for its own interval`() = runBlocking {
        val feed = feed(ny511 = true, dot = true)
        feed.refresh()
        assertEquals(3, downloads())
        assertEquals(1, dotRequests())
        now += 60_000
        feed.refresh()
        assertEquals(1, dotRequests())
        now += 40_000 // 100 s: speeds are stale, incidents not yet
        val hashChecks = requested.size
        feed.refresh()
        assertEquals(2, dotRequests())
        assertEquals(hashChecks + 1, requested.size)
        now += 100_000 // 200 s: both are stale
        version++
        feed.refresh()
        assertEquals(6, downloads())
        assertEquals(3, dotRequests())
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

    @Test
    fun `the old API's HTML page is reported, not shown as no traffic`() = runBlocking {
        val gate = NetworkGate(InMemoryNetworkToggles(mapOf(NetworkFeature.TRAFFIC_511NY to true)))
        val feed = TrafficFeed(gate, { now }) { feature, _ -> gate.request(feature) { "<!doctype html><html>".toByteArray() } }
        val snapshot = feed.refresh()
        assertTrue(snapshot.incidents.isEmpty())
        assertEquals(1, snapshot.errors.size)
        assertTrue(snapshot.errors.single().startsWith("511NY: "))
    }
}
