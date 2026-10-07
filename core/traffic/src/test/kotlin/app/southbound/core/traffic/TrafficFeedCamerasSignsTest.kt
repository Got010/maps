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

/** 511NY cameras and message signs in [TrafficFeed]: same toggle, own intervals, only for the map. */
class TrafficFeedCamerasSignsTest {

    private val events = File("src/test/resources/511ny-cars-incidents.json").readBytes()
    private val cameras = File("src/test/resources/511ny-cars-cameras.json").readBytes()
    private val signs = File("src/test/resources/511ny-cars-signs.json").readBytes()
    private val requested = ArrayList<String>()
    private var now = 1_790_000_000_000L
    private var fail: String? = null
    private var signsHash = "s1"
    private val toggles = InMemoryNetworkToggles(mapOf(NetworkFeature.TRAFFIC_511NY to true))

    private fun feed(): TrafficFeed {
        val gate = NetworkGate(toggles)
        return TrafficFeed(gate, { now }) { feature, url ->
            gate.request(feature) {
                assertEquals(NetworkFeature.TRAFFIC_511NY, feature)
                synchronized(requested) { requested.add(url) }
                fail?.let { throw IOException(it) }
                when {
                    url.endsWith("/cameras/map-features") -> cameras
                    url.endsWith("/signs/map-features") -> signs
                    url.endsWith("/signs/hash") -> """{"hash":"$signsHash"}""".toByteArray()
                    "/events/hash?" in url -> """{"hash":"e"}""".toByteArray()
                    "/events/map-features?" in url -> events
                    else -> throw IOException("unexpected $url")
                }
            }
        }
    }

    private fun count(path: String) = synchronized(requested) { requested.count { it.endsWith("/$path/map-features") } }

    @Test
    fun `fetched only for the map, under the 511NY toggle`() = runBlocking {
        val feed = feed()
        val plain = feed.refresh()
        assertTrue(plain.cameras.isEmpty())
        assertEquals(0, count("cameras") + count("signs"))

        val shown = feed.refresh(mapExtras = true)
        assertEquals(3, shown.cameras.size)
        assertEquals(3, shown.signs.size)
        assertEquals(1, count("cameras"))
        assertEquals(1, count("signs"))
        // The whole state: no query at all.
        assertTrue(requested.filter { "/cameras/" in it || "/signs/" in it }.none { '?' in it })

        // Hidden again: the last lists are kept, nothing more is asked.
        assertEquals(3, feed.refresh(force = true).cameras.size)
        assertEquals(1, count("cameras"))
    }

    @Test
    fun `nothing without the toggle`() = runBlocking {
        toggles.set(NetworkFeature.TRAFFIC_511NY, false)
        val off = feed().refresh(mapExtras = true)
        assertTrue(off.cameras.isEmpty() && off.signs.isEmpty())
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `turning 511NY off drops the lists`() = runBlocking {
        val feed = feed()
        assertFalse(feed.refresh(mapExtras = true).signs.isEmpty())
        toggles.set(NetworkFeature.TRAFFIC_511NY, false)
        val off = feed.refresh(mapExtras = true)
        assertTrue(off.cameras.isEmpty() && off.signs.isEmpty())
    }

    @Test
    fun `cameras are kept a day, signs five minutes and only downloaded when they changed`() = runBlocking {
        val feed = feed()
        feed.refresh(mapExtras = true)
        now += TrafficFeed.SIGNS_TTL_MILLIS + 1
        feed.refresh(mapExtras = true)
        assertEquals(1, count("cameras"))
        assertEquals(1, count("signs")) // same hash
        signsHash = "s2"
        now += TrafficFeed.CAMERAS_TTL_MILLIS
        feed.refresh(mapExtras = true)
        assertEquals(2, count("cameras"))
        assertEquals(2, count("signs"))
    }

    @Test
    fun `a failure keeps the last lists, and one cause is reported once`() = runBlocking {
        val feed = feed()
        val first = feed.refresh(mapExtras = true)
        now += TrafficFeed.CAMERAS_TTL_MILLIS + 1
        fail = "HTTP 503 from api-511x-nysdot.carsprogram.org"
        val second = feed.refresh(mapExtras = true)
        assertEquals(first.cameras, second.cameras)
        assertEquals(first.signs, second.signs)
        assertEquals(listOf("511NY: HTTP 503 from api-511x-nysdot.carsprogram.org"), second.errors)
    }

    @Test
    fun `a sign failure alone is reported by name`() = runBlocking {
        val gate = NetworkGate(toggles)
        val feed = TrafficFeed(gate, { now }) { feature, url ->
            gate.request(feature) {
                when {
                    "/signs/" in url -> throw IOException("HTTP 503 from api-511x-nysdot.carsprogram.org")
                    "/cameras/" in url -> cameras
                    "/hash" in url -> """{"hash":"e"}""".toByteArray()
                    else -> events
                }
            }
        }
        val snapshot = feed.refresh(mapExtras = true)
        assertEquals(3, snapshot.cameras.size)
        assertTrue(snapshot.signs.isEmpty())
        assertEquals(listOf("511NY signs: HTTP 503 from api-511x-nysdot.carsprogram.org"), snapshot.errors)
    }
}
