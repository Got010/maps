package app.southbound.core.weather

import app.southbound.core.network.InMemoryNetworkToggles
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkGate
import app.southbound.core.traffic.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RouteWeatherTest {
    // A straight route going east along 42.70N, about 16 km (0.2 degree of longitude), only its two ends.
    private val east = listOf(GeoPoint(42.70, -74.00), GeoPoint(42.70, -73.80))

    private fun square(south: Double, west: Double, size: Double): Ring =
        listOf(GeoPoint(south, west), GeoPoint(south, west + size), GeoPoint(south + size, west + size), GeoPoint(south + size, west))

    private fun alert(id: String, polygons: List<Ring> = emptyList(), zones: List<String> = emptyList(), ends: Long? = null,
        severity: AlertSeverity = AlertSeverity.MODERATE) =
        WeatherAlert(id, "Winter Storm Warning", null, severity, null, "Albany", null, null, null, ends, null, polygons, zones)

    @Test
    fun `an area the route crosses is found with where it starts, even between two far points`() {
        // A small square straddling the line about 8.2 km along, between the route's only two points.
        val a = alert("a", listOf(square(42.69, -73.90, 0.02)))
        val found = RouteWeather.alongRoute(east, listOf(a), emptyMap(), now = 0)
        assertEquals(listOf("a"), found.map { it.alert.id })
        assertEquals(8200.0, found.single().metersAhead, 600.0)
    }

    @Test
    fun `inside at the start is zero ahead, nearest first, misses and ended alerts left out`() {
        val here = alert("here", listOf(square(42.69, -74.01, 0.02)))
        val later = alert("later", listOf(square(42.69, -73.85, 0.02)))
        val north = alert("north", listOf(square(42.80, -73.95, 0.05)))
        val over = alert("over", listOf(square(42.69, -73.95, 0.02)), ends = 1_000)
        val found = RouteWeather.alongRoute(east, listOf(later, north, over, here), emptyMap(), now = 2_000)
        assertEquals(listOf("here", "later"), found.map { it.alert.id })
        assertEquals(0.0, found.first().metersAhead, 1e-9)
    }

    @Test
    fun `zone-only alerts use the zone shapes they were given`() {
        val z = alert("z", zones = listOf("https://api.weather.gov/zones/forecast/NYZ052"))
        assertTrue(RouteWeather.alongRoute(east, listOf(z), emptyMap(), 0).isEmpty())
        val found = RouteWeather.alongRoute(east, listOf(z), mapOf("z" to listOf(square(42.60, -73.90, 0.2))), 0)
        assertEquals(listOf("z"), found.map { it.alert.id })
    }

    @Test
    fun `alerts at a point, for here and the destination`() {
        val a = alert("a", listOf(square(42.0, -74.0, 1.0)))
        val snapshot = WeatherSnapshot(listOf(a), mapOf("a" to a.polygons))
        assertEquals(listOf("a"), snapshot.alertsAt(GeoPoint(42.5, -73.5), 0).map { it.id })
        assertTrue(snapshot.alertsAt(GeoPoint(43.5, -73.5), 0).isEmpty())
    }

    // --- The feed: what it asks for, and that nothing in a request depends on the user.

    private val polygonAlert = """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-74,42],[-73,42],[-73,43],[-74,42]]]},
        "properties":{"id":"p","event":"Flood Warning","status":"Actual","messageType":"Alert"}}"""
    private val zoneAlert = """{"type":"Feature","geometry":null,"properties":{"id":"z","event":"Wind Advisory","status":"Actual",
        "messageType":"Alert","affectedZones":["https://api.weather.gov/zones/forecast/NYZ052"]}}"""
    private val zone = """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[-74,42],[-73,42],[-73,43],[-74,42]]]}}"""
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)

    private fun feed(on: Boolean, clock: () -> Long, asked: MutableList<String>, fail: Boolean = false): WeatherFeed {
        val gate = NetworkGate(InMemoryNetworkToggles(mapOf(NetworkFeature.WEATHER to on)))
        return WeatherFeed(gate, clock) { feature, url ->
            assertEquals(NetworkFeature.WEATHER, feature)
            asked += url
            if (fail) throw IOException("offline")
            when {
                url == NwsAlerts.URL -> """{"type":"FeatureCollection","features":[$polygonAlert,$zoneAlert]}""".toByteArray()
                url.startsWith(NwsAlerts.ZONE_PREFIX) -> zone.toByteArray()
                url == Radar.URL -> png
                else -> error("unexpected $url")
            }
        }
    }

    @Test
    fun `off asks for nothing`() = runBlocking {
        val asked = ArrayList<String>()
        val s = feed(false, { 0L }, asked).refresh(withRadar = true)
        assertTrue(asked.isEmpty())
        assertTrue(s.alerts.isEmpty())
    }

    @Test
    fun `alerts, zone shapes once, radar only when asked, all cached`() = runBlocking {
        val asked = ArrayList<String>()
        var now = 0L
        val f = feed(true, { now }, asked)
        val first = f.refresh()
        assertEquals(listOf(NwsAlerts.URL, "https://api.weather.gov/zones/forecast/NYZ052"), asked)
        assertEquals(listOf("p", "z"), first.alerts.map { it.id })
        assertEquals(1, first.areas.getValue("z").size)
        assertNull(first.radar)

        asked.clear()
        now = 60_000
        val second = f.refresh(withRadar = true)
        assertEquals(listOf(Radar.URL), asked) // alerts still fresh, the zone already read
        assertTrue(Radar.isPng(second.radar!!.png))

        asked.clear()
        now = 60_000 + WeatherFeed.ALERTS_TTL_MILLIS
        f.refresh()
        assertEquals(listOf(NwsAlerts.URL), asked)
    }

    @Test
    fun `a failure keeps the last good alerts and says so`() = runBlocking {
        val asked = ArrayList<String>()
        var now = 0L
        var fail = false
        val gate = NetworkGate(InMemoryNetworkToggles(mapOf(NetworkFeature.WEATHER to true)))
        val f = WeatherFeed(gate, { now }) { _, url ->
            asked += url
            if (fail) throw IOException("offline")
            if (url == NwsAlerts.URL) """{"type":"FeatureCollection","features":[$polygonAlert]}""".toByteArray() else error(url)
        }
        f.refresh()
        fail = true
        now = WeatherFeed.ALERTS_TTL_MILLIS
        val s = f.refresh()
        assertEquals(listOf("p"), s.alerts.map { it.id })
        assertEquals(0L, s.alertsAt)
        assertTrue(s.errors.single().startsWith("Weather alerts"))
    }

    @Test
    fun `the requests are fixed, the same for every phone`() {
        assertEquals("https://api.weather.gov/alerts/active?area=NY,NJ,CT,PA,MD,DE,DC", NwsAlerts.URL)
        // Every state the alerts are asked for, in the same order (the request is the same for every phone).
        assertEquals("https://api.weather.gov/alerts/active?area=" + NwsAlerts.STATES.joinToString(","), NwsAlerts.URL)
        assertTrue(Radar.URL.startsWith("https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows?"))
        assertTrue("crs=EPSG:3857" in Radar.URL)
        assertTrue(Radar.isPng(png))
        assertTrue(!Radar.isPng("<html>".toByteArray()))
    }
}
