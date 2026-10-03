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

/** 511NY cameras and message signs in [TrafficFeed]: same toggle and key, own intervals, only for the map. */
class TrafficFeedCamerasSignsTest {

    private val events = File("src/test/resources/511ny-events.json").readBytes()
    private val cameras = File("src/test/resources/511ny-cameras.json").readBytes()
    private val signs = File("src/test/resources/511ny-signs.json").readBytes()
    private val requested = ArrayList<String>()
    private var now = 1_790_000_000_000L
    private var fail: String? = null
    private val toggles = InMemoryNetworkToggles(mapOf(NetworkFeature.TRAFFIC_511NY to true))

    private fun feed(key: String? = "KEY"): TrafficFeed {
        val gate = NetworkGate(toggles)
        return TrafficFeed(gate, { key }, { now }) { feature, url ->
            gate.request(feature) {
                assertEquals(NetworkFeature.TRAFFIC_511NY, feature)
                synchronized(requested) { requested.add(url) }
                fail?.let { throw IOException(it) }
                when {
                    "/getcameras?" in url -> cameras
                    "/getmessagesigns?" in url -> signs
                    "/getevents?" in url -> events
                    else -> throw IOException("unexpected $url")
                }
            }
        }
    }

    private fun count(path: String) = synchronized(requested) { requested.count { "/$path?" in it } }

    @Test
    fun `fetched only for the map, under the 511NY toggle and key`() = runBlocking {
        val feed = feed()
        val plain = feed.refresh()
        assertTrue(plain.cameras.isEmpty())
        assertEquals(0, count("getcameras") + count("getmessagesigns"))

        val shown = feed.refresh(mapExtras = true)
        assertEquals(5, shown.cameras.size)
        assertEquals(5, shown.signs.size)
        assertEquals(1, count("getcameras"))
        assertEquals(1, count("getmessagesigns"))
        // The whole state: only the key in the query.
        assertTrue(requested.all { it.startsWith("https://511ny.org/api/") && it.endsWith("?key=KEY&format=json") })

        // Hidden again: the last lists are kept, nothing more is asked.
        assertEquals(5, feed.refresh(force = true).cameras.size)
        assertEquals(1, count("getcameras"))
    }

    @Test
    fun `nothing without the toggle or the key`() = runBlocking {
        assertTrue(feed(key = null).refresh(mapExtras = true).cameras.isEmpty())
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
    fun `cameras are kept a day, signs five minutes`() = runBlocking {
        val feed = feed()
        feed.refresh(mapExtras = true)
        now += TrafficFeed.SIGNS_TTL_MILLIS + 1
        feed.refresh(mapExtras = true)
        assertEquals(1, count("getcameras"))
        assertEquals(2, count("getmessagesigns"))
        now += TrafficFeed.CAMERAS_TTL_MILLIS
        feed.refresh(mapExtras = true)
        assertEquals(2, count("getcameras"))
        assertEquals(3, count("getmessagesigns"))
    }

    @Test
    fun `a failure keeps the last lists, and one cause is reported once`() = runBlocking {
        val feed = feed()
        val first = feed.refresh(mapExtras = true)
        now += TrafficFeed.CAMERAS_TTL_MILLIS + 1
        fail = "HTTP 401 from 511ny.org"
        val second = feed.refresh(mapExtras = true)
        assertEquals(first.cameras, second.cameras)
        assertEquals(first.signs, second.signs)
        assertEquals(listOf("511NY: HTTP 401 from 511ny.org"), second.errors)
    }

    @Test
    fun `a sign failure alone is reported by name`() = runBlocking {
        val gate = NetworkGate(toggles)
        val feed = TrafficFeed(gate, { "KEY" }, { now }) { feature, url ->
            gate.request(feature) {
                when {
                    "/getmessagesigns?" in url -> throw IOException("HTTP 503 from 511ny.org")
                    "/getcameras?" in url -> cameras
                    else -> events
                }
            }
        }
        val snapshot = feed.refresh(mapExtras = true)
        assertEquals(5, snapshot.cameras.size)
        assertTrue(snapshot.signs.isEmpty())
        assertEquals(listOf("511NY signs: HTTP 503 from 511ny.org"), snapshot.errors)
    }
}
