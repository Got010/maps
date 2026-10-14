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

    // Teterboro, as New Jersey's answer would carry it.
    private val teterboro =
        """[{"icaoId":"KTEB","obsTime":1791679860,"temp":12.2,"wspd":0,"cover":"CLR","lat":40.859,"lon":-74.0562,"name":"Teterboro Arpt, NJ, US"}]"""

    private fun feed(now: () -> Long, answer: (String) -> String?, asked: MutableList<String>): WeatherFeed {
        val gate = NetworkGate(InMemoryNetworkToggles(mapOf(NetworkFeature.WEATHER to true)))
        return WeatherFeed(gate, now) { _, url ->
            asked += url
            when (url) {
                NwsAlerts.URL -> """{"type":"FeatureCollection","features":[]}""".toByteArray()
                in Metar.URLS -> (answer(url) ?: throw java.io.IOException("timeout")).toByteArray()
                else -> error(url)
            }
        }
    }

    @Test
    fun `the feed asks every state for conditions only when wanted, then caches them`() = runBlocking {
        val asked = ArrayList<String>()
        var now = 0L
        val feed = feed({ now }, { url -> if ("@NY" in url) json else if ("@NJ" in url) teterboro else "" }, asked)
        assertTrue(feed.refresh().conditions.isEmpty())
        assertEquals(listOf(NwsAlerts.URL), asked)
        asked.clear()
        // New York's three and New Jersey's one; the others reported nothing (204, an empty body).
        assertEquals(listOf("KART", "KJHW", "KHPN", "KTEB"), feed.refresh(withConditions = true).conditions.map { it.station })
        assertEquals(Metar.URLS, asked)
        asked.clear()
        now = WeatherFeed.CONDITIONS_TTL_MILLIS - 1
        feed.refresh(withConditions = true)
        assertTrue(asked.none { it in Metar.URLS })
    }

    @Test
    fun `a state that doesn't answer keeps its last stations`() = runBlocking {
        val asked = ArrayList<String>()
        var now = 0L
        var newJerseyUp = true
        val feed = feed({ now }, { url -> if ("@NY" in url) json else if ("@NJ" in url) teterboro.takeIf { newJerseyUp } else "[]" }, asked)
        assertEquals(4, feed.refresh(withConditions = true).conditions.size)
        newJerseyUp = false
        now = WeatherFeed.CONDITIONS_TTL_MILLIS
        val snapshot = feed.refresh(withConditions = true)
        assertEquals(listOf("KART", "KJHW", "KHPN", "KTEB"), snapshot.conditions.map { it.station })
        assertEquals(listOf("Current weather: timeout"), snapshot.errors)
    }

    @Test
    fun `when nothing answers, nothing is shown and it is asked again next time`() = runBlocking {
        val asked = ArrayList<String>()
        val feed = feed({ 0L }, { null }, asked)
        val snapshot = feed.refresh(withConditions = true)
        assertTrue(snapshot.conditions.isEmpty())
        assertEquals(listOf("Current weather: timeout"), snapshot.errors)
        asked.clear()
        feed.refresh(withConditions = true)
        assertEquals(Metar.URLS, asked.filter { it in Metar.URLS })
    }

    @Test
    fun `one fixed request per state, the same for every phone`() {
        assertEquals(
            listOf("NY", "NJ", "CT", "PA", "MD", "DE", "OH", "IN", "IL", "MI", "WI", "MN", "IA", "MO", "ND", "SD", "NE", "KS", "VA", "WV", "KY").map { "https://aviationweather.gov/api/data/metar?ids=@$it&format=json" },
            Metar.URLS,
        )
        // Every covered state's alerts are asked for; DC alone has no station of its own.
        assertEquals(NwsAlerts.STATES - "DC", Metar.STATES)
    }
}
