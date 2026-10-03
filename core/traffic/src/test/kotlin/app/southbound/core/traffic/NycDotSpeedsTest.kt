package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class NycDotSpeedsTest {

    private val fixture = File("src/test/resources/nycdot-speeds.json").readText()
    private val now = ZonedDateTime.of(2026, 10, 2, 22, 20, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    @Test
    fun `keeps the newest reading per link and drops stale or broken rows`() {
        val links = NycDotSpeeds.parse(fixture, now)
        assertEquals(listOf("4616337", "4456479"), links.map { it.id })

        val eleventh = links[0]
        assertEquals(52.19, eleventh.speedMph, 1e-9) // the newer of two readings
        assertEquals(122, eleventh.travelTimeSeconds)
        assertEquals(3, eleventh.points.size)
        assertEquals("Manhattan", eleventh.borough)
        assertEquals(now - 4 * 60_000L + 4_000L, eleventh.asOf)

        // A bare latitude in the middle of the shape is skipped.
        assertEquals(3, links[1].points.size)
        assertEquals(GeoPoint(40.83706, -73.9289), links[1].points.last())
    }

    @Test
    fun `everything is kept when age doesn't matter`() {
        val links = NycDotSpeeds.parse(fixture, now, maxAgeMillis = Long.MAX_VALUE)
        assertEquals(listOf("4616337", "4456479", "4329500"), links.map { it.id })
    }

    @Test
    fun `reports how many rows were dropped as too old`() {
        val report = NycDotSpeeds.parseReport(fixture, now)
        assertEquals(2, report.links.size)
        assertEquals(1, report.staleDropped) // the September sensor
        assertFalse(report.allStale)
        assertEquals(report.links[0].asOf, report.newestAsOf)
    }

    @Test
    fun `a feed with only old readings is stale rather than just empty`() {
        val threeDaysLater = now + 3 * 24 * 60 * 60_000L
        val report = NycDotSpeeds.parseReport(fixture, threeDaysLater)
        assertTrue(report.links.isEmpty())
        assertEquals(4, report.staleDropped) // the four rows with a speed and a shape
        assertTrue(report.allStale)
        assertEquals(now - 4 * 60_000L + 4_000L, report.newestAsOf)
        assertTrue(NycDotSpeeds.parse(fixture, threeDaysLater).isEmpty())
    }

    @Test
    fun `an empty feed is not stale`() {
        val report = NycDotSpeeds.parseReport("[]", now)
        assertTrue(report.links.isEmpty())
        assertEquals(0, report.staleDropped)
        assertFalse(report.allStale)
        assertEquals(null, report.newestAsOf)
    }

    @Test
    fun `the request has no parameters about the user`() {
        assertTrue(NycDotSpeeds.URL.startsWith("https://data.cityofnewyork.us/resource/i4gi-tjb9.json?"))
        assertTrue("%24order=data_as_of%20DESC" in NycDotSpeeds.URL)
        assertTrue("where" !in NycDotSpeeds.URL.lowercase())
    }

    @Test
    fun `link points tolerate junk`() {
        assertEquals(emptyList<GeoPoint>(), NycDotSpeeds.parseLinkPoints(""))
        assertEquals(listOf(GeoPoint(40.7, -74.0)), NycDotSpeeds.parseLinkPoints("40.7,-74.0 abc 1,2 40.7,"))
    }
}
