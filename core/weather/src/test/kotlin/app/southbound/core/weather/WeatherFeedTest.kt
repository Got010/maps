package app.southbound.core.weather

import app.southbound.core.network.InMemoryNetworkToggles
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the feed keeps and shows when requests fail, and that a slow refresh holds up no other. */
class WeatherFeedTest {
    private val gate = NetworkGate(InMemoryNetworkToggles(mapOf(NetworkFeature.WEATHER to true)))

    // 2026-10-07 18:00 EDT.
    private val evening = NwsAlerts.time("2026-10-07T18:00:00-04:00")!!

    private val square = """{"type":"Polygon","coordinates":[[[-74.0,42.0],[-73.0,42.0],[-73.0,43.0],[-74.0,42.0]]]}"""
    private val zone = "https://api.weather.gov/zones/forecast/NYZ052"

    private fun alerts(vararg features: String) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun alert(id: String, ends: String, geometry: String = square) =
        """{"id":"$id","type":"Feature","geometry":$geometry,"properties":{"id":"$id","status":"Actual",""" +
            """"messageType":"Alert","event":"Flood Warning","severity":"Severe","areaDesc":"Albany","ends":"$ends",""" +
            """"affectedZones":["$zone"]}}"""

    private fun feed(now: () -> Long, answer: (String) -> String) = WeatherFeed(gate, now) { _, url -> answer(url).toByteArray() }

    @Test
    fun `an alert past its end is no longer shown, offline too`() = runBlocking {
        var now = evening
        var online = true
        val f = feed({ now }) { url ->
            if (!online) throw IOException("offline")
            assertEquals(NwsAlerts.URL, url)
            alerts(alert("ended", "2026-10-07T17:00:00-04:00"), alert("soon", "2026-10-07T19:00:00-04:00"))
        }
        val s = f.refresh()
        assertEquals(listOf("soon"), s.alerts.map { it.id })
        assertEquals(setOf("soon"), s.areas.keys)
        // Offline past 19:00: the last list is kept, but its other alert has ended too.
        online = false
        now = evening + 2 * 3_600_000L
        val later = f.refresh()
        assertTrue(later.alerts.isEmpty())
        assertTrue(later.areas.isEmpty())
        assertEquals(listOf("Weather alerts: offline"), later.errors)
    }

    @Test
    fun `an observation hours old is no longer shown`() = runBlocking {
        val teterboro =
            """[{"icaoId":"KTEB","obsTime":1791679860,"temp":12.2,"wspd":0,"cover":"CLR","lat":40.859,"lon":-74.0562,"name":"Teterboro Arpt, NJ, US"}]"""
        val observed = 1791679860_000L
        var now = observed + 10 * 60_000L
        var online = true
        val f = feed({ now }) { url ->
            when {
                url == NwsAlerts.URL -> alerts()
                !online -> throw IOException("offline")
                "@NJ" in url -> teterboro
                else -> "[]"
            }
        }
        assertEquals(listOf("KTEB"), f.refresh(withConditions = true).conditions.map { it.station })
        online = false
        now = observed + Metar.MAX_AGE_MILLIS
        assertEquals(listOf("KTEB"), f.refresh(withConditions = true).conditions.map { it.station })
        now += 60_000L
        assertTrue(f.refresh(withConditions = true).conditions.isEmpty())
    }

    @Test
    fun `a zone answered with something that isn't JSON is asked again`() = runBlocking {
        var portal = true
        val asked = ArrayList<String>()
        val f = feed({ evening }) { url ->
            asked += url
            when (url) {
                NwsAlerts.URL -> alerts(alert("zoned", "2026-10-07T23:00:00-04:00", geometry = "null"))
                zone -> if (portal) "<html><body>Sign in to the Wi-Fi</body></html>" else """{"type":"Feature","geometry":$square}"""
                else -> error(url)
            }
        }
        val first = f.refresh()
        assertTrue(first.areas.getValue("zoned").isEmpty())
        assertEquals(listOf("Weather zones: 1 couldn't be read"), first.errors)
        portal = false
        val second = f.refresh()
        assertEquals(2, asked.count { it == zone })
        assertEquals(1, second.areas.getValue("zoned").size)
        assertTrue(second.errors.isEmpty())
    }

    @Test
    fun `a slow refresh holds up no other, and nothing is asked twice`() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)
        val asked = Collections.synchronizedList(ArrayList<String>())
        val stuck = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = WeatherFeed(gate, { evening }) { _, url ->
            asked += url
            if (url == Metar.URLS.first()) {
                stuck.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            when (url) {
                NwsAlerts.URL -> alerts().toByteArray()
                Radar.URL -> png
                else -> "[]".toByteArray()
            }
        }
        // One refresh waits on a slow station...
        val slow = async(Dispatchers.Default) { f.refresh(withConditions = true) }
        assertTrue(withContext(Dispatchers.IO) { stuck.await(10, TimeUnit.SECONDS) })
        // ...while another goes ahead with the radar, leaving the alerts and the stations to the first.
        val quick = withTimeout(5_000) { f.refresh(withConditions = true, withRadar = true) }
        assertNotNull(quick.radar)
        release.countDown()
        slow.await()
        assertEquals(listOf(NwsAlerts.URL), asked.filter { it == NwsAlerts.URL })
        assertEquals(Metar.URLS, asked.filter { it in Metar.URLS })
        assertEquals(listOf(Radar.URL), asked.filter { it == Radar.URL })
        assertNotNull(f.snapshot.value.radar)
    }
}
