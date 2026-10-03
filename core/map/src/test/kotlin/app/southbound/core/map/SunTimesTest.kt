package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SunTimesTest {
    private val nycLat = 40.7128
    private val nycLon = -74.0060
    private val ny = ZoneId.of("America/New_York")

    private fun local(ms: Long): ZonedDateTime = java.time.Instant.ofEpochMilli(ms).atZone(ny)
    private fun minutesOfDay(z: ZonedDateTime) = (z.hour * 60 + z.minute).toDouble()

    @Test
    fun `nyc summer solstice sunrise and sunset match published times`() {
        val day = SunTimes.of(2026, 6, 21, nycLat, nycLon)
        // timeanddate.com: sunrise 5:25 am, sunset 8:31 pm EDT.
        assertEquals((5 * 60 + 25).toDouble(), minutesOfDay(local(day.sunriseUtcMs!!)), 3.0)
        assertEquals((20 * 60 + 31).toDouble(), minutesOfDay(local(day.sunsetUtcMs!!)), 3.0)
    }

    @Test
    fun `nyc winter solstice`() {
        val day = SunTimes.of(2026, 12, 21, nycLat, nycLon)
        // Sunrise 7:17 am, sunset 4:32 pm EST.
        assertEquals((7 * 60 + 17).toDouble(), minutesOfDay(local(day.sunriseUtcMs!!)), 3.0)
        assertEquals((16 * 60 + 32).toDouble(), minutesOfDay(local(day.sunsetUtcMs!!)), 3.0)
    }

    @Test
    fun `equinox on the equator at greenwich is about twelve hours of day around noon`() {
        val day = SunTimes.of(2026, 3, 20, 0.0, 0.0)
        val utc = ZoneId.of("UTC")
        fun minutes(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(utc).let { (it.hour * 60 + it.minute).toDouble() }
        assertEquals(6 * 60 + 3.0, minutes(day.sunriseUtcMs!!), 8.0)
        assertEquals(18 * 60 + 8.0, minutes(day.sunsetUtcMs!!), 8.0)
    }

    @Test
    fun `isNight flips at sunset and sunrise`() {
        val day = SunTimes.of(2026, 10, 3, nycLat, nycLon)
        val sunset = day.sunsetUtcMs!!
        assertFalse(SunTimes.isNight(sunset - 10 * 60_000, nycLat, nycLon))
        assertTrue(SunTimes.isNight(sunset + 10 * 60_000, nycLat, nycLon))
        val sunrise = day.sunriseUtcMs!!
        assertTrue(SunTimes.isNight(sunrise - 10 * 60_000, nycLat, nycLon))
        assertFalse(SunTimes.isNight(sunrise + 10 * 60_000, nycLat, nycLon))
        // Midday and the small hours.
        assertFalse(SunTimes.isNight(ZonedDateTime.of(2026, 10, 3, 13, 0, 0, 0, ny).toInstant().toEpochMilli(), nycLat, nycLon))
        assertTrue(SunTimes.isNight(ZonedDateTime.of(2026, 10, 3, 2, 0, 0, 0, ny).toInstant().toEpochMilli(), nycLat, nycLon))
    }

    @Test
    fun `polar day and night have no sunrise and are never or always night`() {
        val summer = SunTimes.of(2026, 6, 21, 80.0, 0.0)
        assertNull(summer.sunriseUtcMs)
        assertNull(summer.sunsetUtcMs)
        val midnightSun = ZonedDateTime.of(2026, 6, 21, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertFalse(SunTimes.isNight(midnightSun, 80.0, 0.0))
        val winterNoon = ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertTrue(SunTimes.isNight(winterNoon, 80.0, 0.0))
        assertNotNull(SunTimes.of(2026, 3, 20, 80.0, 0.0).sunriseUtcMs)
    }

    @Test
    fun `elevation at computed sunset is the horizon`() {
        val sunset = SunTimes.of(2026, 4, 10, nycLat, nycLon).sunsetUtcMs!!
        assertEquals(-0.833, SunTimes.elevationDegrees(sunset, nycLat, nycLon), 0.15)
    }

    @Test
    fun `night mode needs navigation, the setting and a fix, or the system theme`() {
        val noon = ZonedDateTime.of(2026, 10, 3, 13, 0, 0, 0, ny).toInstant().toEpochMilli()
        val night = ZonedDateTime.of(2026, 10, 3, 22, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertTrue(NightMode.useDark(true, false, false, null, null, noon))
        assertFalse(NightMode.useDark(false, true, true, nycLat, nycLon, noon))
        assertTrue(NightMode.useDark(false, true, true, nycLat, nycLon, night))
        assertFalse(NightMode.useDark(false, false, true, nycLat, nycLon, night)) // not navigating
        assertFalse(NightMode.useDark(false, true, false, nycLat, nycLon, night)) // setting off
        assertFalse(NightMode.useDark(false, true, true, null, null, night)) // no fix yet
    }
}
