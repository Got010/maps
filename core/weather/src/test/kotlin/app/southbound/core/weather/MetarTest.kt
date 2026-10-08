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
import java.io.File

/**
 * metar-ny.json holds three real METARs from aviationweather.gov/api/data/metar?ids=@NY&format=json,
 * fetched 2026-10-08 00:00Z by the feed probe (the answer had 49 stations, 25 KB): Watertown in light
 * rain, Jamestown with a few clouds, White Plains clear.
 */
class MetarTest {
    private val json = File("src/test/resources/metar-ny.json").readText()

    @Test
    fun `real reports parse`() {
        val list = Metar.parse(json)
        assertEquals(listOf("KART", "KJHW", "KHPN"), list.map { it.station })
        val watertown = list[0]
        assertEquals("Watertown Intl", watertown.name)
        assertEquals(57, watertown.tempF) // 13.9 C
        assertEquals(Sky.RAIN, watertown.sky)
        assertEquals(1791417360_000L, watertown.observedAt)
        assertEquals(43.9888, watertown.location.lat, 1e-9)
        assertEquals(5, watertown.windMph) // 4 knots
        assertEquals("Jamestown/Chautauqua Cnty", list[1].name)
        assertEquals(Sky.PARTLY_CLOUDY, list[1].sky)
        assertEquals(62, list[1].tempF)
        assertEquals(Sky.CLEAR, list[2].sky)
        assertEquals(55, list[2].tempF)
    }

    @Test
    fun `weather codes, worst first, then the clouds`() {
        assertEquals(Sky.THUNDER, Metar.sky("VCTS -DZ", "OVC"))
        assertEquals(Sky.SNOW, Metar.sky("-SHSN", "BKN"))
        assertEquals(Sky.SLEET, Metar.sky("FZRA", "OVC"))
        assertEquals(Sky.RAIN, Metar.sky("+RA BR", "OVC"))
        assertEquals(Sky.DRIZZLE, Metar.sky("-DZ", "OVC"))
        assertEquals(Sky.FOG, Metar.sky("BR", "OVC"))
        // Showers in the vicinity aren't here.
        assertEquals(Sky.OVERCAST, Metar.sky("VCSH", "OVC"))
        assertEquals(Sky.MOSTLY_CLOUDY, Metar.sky(null, "BKN"))
        assertEquals(Sky.PARTLY_CLOUDY, Metar.sky(null, "SCT"))
        assertEquals(Sky.CLEAR, Metar.sky(null, "SKC"))
        assertEquals(Sky.CLEAR, Metar.sky(null, null))
    }

    @Test
    fun `nearest station within reach`() {
        val list = Metar.parse(json)
        // Scarsdale is about 6 km from White Plains airport.
        assertEquals("KHPN", Metar.nearest(list, GeoPoint(41.005, -73.784))?.station)
        // The middle of the Adirondacks: no station within 40 km in this sample.
        assertNull(Metar.nearest(list, GeoPoint(43.9, -74.4)))
        assertNull(Metar.nearest(emptyList(), GeoPoint(41.0, -73.7)))
    }

    @Test
    fun `nothing reported is an empty list`() {
        assertTrue(Metar.parse("").isEmpty())
        assertTrue(Metar.parse("[]").isEmpty())
    }

    @Test
    fun `the feed asks for conditions only when wanted, then caches them`() = runBlocking {
        val asked = ArrayList<String>()
        var now = 0L
        val gate = NetworkGate(InMemoryNetworkToggles(mapOf(NetworkFeature.WEATHER to true)))
        val feed = WeatherFeed(gate, { now }) { _, url ->
            asked += url
            when (url) {
                NwsAlerts.URL -> """{"type":"FeatureCollection","features":[]}""".toByteArray()
                Metar.URL -> json.toByteArray()
                else -> error(url)
            }
        }
        assertTrue(feed.refresh().conditions.isEmpty())
        assertEquals(listOf(NwsAlerts.URL), asked)
        asked.clear()
        assertEquals(3, feed.refresh(withConditions = true).conditions.size)
        assertEquals(listOf(Metar.URL), asked)
        asked.clear()
        now = WeatherFeed.CONDITIONS_TTL_MILLIS - 1
        feed.refresh(withConditions = true)
        assertTrue(Metar.URL !in asked)
        assertEquals("https://aviationweather.gov/api/data/metar?ids=@NY&format=json", Metar.URL)
    }
}
