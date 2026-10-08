package app.southbound.core.weather

import app.southbound.core.network.InMemoryNetworkToggles
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkGate
import app.southbound.core.traffic.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * forecast-sample.json.gz is a pack as pipeline/weather/forecast_pack.py writes it, from the real NWS answer for
 * the grid cell BUF 77,43 south of Rochester (2026-10-08 00:41Z, pipeline/weather/tests/gridpoints_buf_77_43.json):
 * one point with all 14 periods and one beside it with the first 4.
 */
class ForecastTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = File("src/test/resources/forecast-sample.json.gz").readBytes()

    // 2026-10-07 21:00 EDT: tonight has begun.
    private val evening = 1791421200_000L

    @Test
    fun `the pack parses`() {
        val pack = Forecasts.parse(bytes)
        assertEquals(1791420092_000L, pack.generatedAt)
        assertEquals(2, pack.points.size)
        val p = pack.points[0]
        assertEquals(14, p.periods.size)
        val tonight = p.periods[0]
        assertEquals("Tonight", tonight.name)
        assertEquals(false, tonight.isDay)
        assertEquals(51, tonight.tempF)
        assertEquals(24, tonight.pop)
        assertEquals(Sky.RAIN, tonight.sky)
        assertEquals("Slight Chance Rain Showers", tonight.text)
        assertEquals(1791417600_000L, tonight.start)
        assertEquals(Sky.PARTLY_CLOUDY, p.periods[1].sky) // "few"
        assertEquals(Sky.MOSTLY_CLOUDY, p.periods[9].sky) // "bkn", Columbus Day
    }

    @Test
    fun `seven days from tonight`() {
        val p = Forecasts.parse(bytes).points[0]
        val days = Forecasts.days(p, evening)
        assertEquals(7, days.size)
        assertEquals(listOf("Tonight", "Thursday", "Friday", "Saturday", "Sunday", "Columbus Day", "Tuesday"), days.map { it.name })
        assertEquals(null, days[0].high)
        assertEquals(51, days[0].low)
        assertEquals(62, days[1].high)
        assertEquals(45, days[1].low)
        assertEquals(19, days[1].pop) // the larger of the day's and the night's
        assertEquals(91, days[4].pop)
        assertEquals(Sky.RAIN, days[4].sky)
        // Thursday morning: tonight is over, Thursday leads.
        assertEquals("Thursday", Forecasts.days(p, 1791460000_000L).first().name)
    }

    @Test
    fun `the period at a time, for the weather on arrival`() {
        val p = Forecasts.parse(bytes).points[0]
        assertEquals("Tonight", Forecasts.at(p, evening)?.name)
        assertEquals("Saturday", Forecasts.at(p, 1791640000_000L)?.name)
        assertNull(Forecasts.at(p, 1792100000_000L)) // past the end of the forecast
    }

    @Test
    fun `nearest point within reach`() {
        val pack = Forecasts.parse(bytes)
        // Livonia, about 10 km from the first point.
        assertEquals(42.7, pack.nearest(GeoPoint(42.82, -77.67))!!.location.lat, 1e-9)
        assertNull(pack.nearest(GeoPoint(40.75, -73.98))) // Manhattan: not in this sample
    }

    @Test
    fun `NWS icon codes`() {
        assertEquals(Sky.THUNDER, Forecasts.sky("tsra_sct"))
        assertEquals(Sky.SLEET, Forecasts.sky("rain_fzra"))
        assertEquals(Sky.SNOW, Forecasts.sky("snow"))
        assertEquals(Sky.RAIN, Forecasts.sky("rain_showers_hi"))
        assertEquals(Sky.FOG, Forecasts.sky("haze"))
        assertEquals(Sky.OVERCAST, Forecasts.sky("wind_ovc"))
        assertEquals(Sky.CLEAR, Forecasts.sky("skc"))
        // An unknown code: the words decide.
        assertEquals(Sky.SNOW, Forecasts.sky("new_code", "Chance Snow Showers"))
        assertEquals(Sky.MOSTLY_CLOUDY, Forecasts.sky(null, "Mostly Cloudy"))
    }

    @Test
    fun `not a pack is an error`() {
        for (bad in listOf("not gzip".toByteArray(), java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write("""{"v":9,"points":[]}""".toByteArray()) }
        }.toByteArray())) {
            try {
                Forecasts.parse(bad)
                throw AssertionError("accepted")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // --- The feed: the release asked at most hourly, the file downloaded only when it changed, kept on disk.

    private class FakeSource(var id: Long?, val bytes: ByteArray) : ForecastSource {
        val calls = ArrayList<String>()
        var fail = false
        override fun latestId(): Long? {
            calls += "latest"
            if (fail) throw IOException("offline")
            return id
        }
        override fun download(id: Long): ByteArray {
            calls += "download $id"
            return bytes
        }
    }

    private fun feed(source: ForecastSource, dir: File, now: () -> Long) =
        WeatherFeed(
            NetworkGate(InMemoryNetworkToggles(mapOf(NetworkFeature.WEATHER to true))),
            now,
            forecastSource = source,
            forecastDir = dir,
        ) { _, url -> if (url == NwsAlerts.URL) """{"type":"FeatureCollection","features":[]}""".toByteArray() else error(url) }

    @Test
    fun `downloaded once, checked hourly, kept for offline`() = runBlocking {
        val dir = tmp.newFolder()
        val source = FakeSource(7, bytes)
        var now = 1791420092_000L + 3_600_000L
        val f = feed(source, dir) { now }
        assertNull(f.refresh().forecast) // not asked for
        assertTrue(source.calls.isEmpty())
        assertNotNull(f.refresh(withForecast = true).forecast)
        assertEquals(listOf("latest", "download 7"), source.calls)
        source.calls.clear()
        now += 10 * 60_000L
        f.refresh(withForecast = true)
        assertTrue(source.calls.isEmpty()) // checked less than an hour ago
        now += WeatherFeed.FORECAST_CHECK_MILLIS
        f.refresh(withForecast = true)
        assertEquals(listOf("latest"), source.calls) // same file: not downloaded again

        // A new process, offline: the pack on disk is used.
        val offline = FakeSource(7, bytes).also { it.fail = true }
        val again = feed(offline, dir) { now }
        val s = again.refresh(withForecast = true)
        assertNotNull(s.forecast)
        assertTrue(s.errors.single().startsWith("Forecast"))
    }

    @Test
    fun `a pack too old is not shown`() = runBlocking {
        val dir = tmp.newFolder()
        val f = feed(FakeSource(7, bytes), dir) { 1791420092_000L + Forecasts.MAX_AGE_MILLIS + 1 }
        assertNull(f.refresh(withForecast = true).forecast)
    }
}
