package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class FiveElevenNyTest {

    private val fixture = File("src/test/resources/511ny-events.json").readText()

    @Test
    fun `parses the events feed and skips rows without a position`() {
        val events = FiveElevenNy.parseEvents(fixture)
        assertEquals(listOf("NY-511-1001", "NY-511-1002", "NY-511-1003", "NY-511-1005"), events.map { it.id })

        val crash = events[0]
        assertEquals(IncidentKind.CLOSURE, crash.kind) // a crash, but every lane is shut
        assertTrue(crash.isClosure)
        assertEquals("I-87 Northbound", crash.title)
        assertEquals(GeoPoint(41.4518, -74.0547), crash.location)
        assertEquals("Major", crash.severity)
        assertEquals("All lanes closed", crash.lanesStatus)
        val expected = ZonedDateTime.of(2026, 10, 2, 22, 15, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertEquals(expected, crash.updatedAt)

        val work = events[1]
        assertEquals(IncidentKind.ROADWORK, work.kind)
        assertFalse(work.isClosure)
        assertEquals(GeoPoint(40.7995, -73.9685), work.location) // numbers as strings

        val ramp = events[2]
        assertEquals(IncidentKind.CLOSURE, ramp.kind)
        assertNull(ramp.updatedAt)

        val parade = events[3]
        assertEquals(IncidentKind.EVENT, parade.kind)
        assertNull(parade.roadway)
        assertEquals("Parade on 5th Avenue.", parade.title)
    }

    @Test
    fun `an error document is reported, not treated as no incidents`() {
        try {
            FiveElevenNy.parseEvents("""{"Message":"Invalid key"}""")
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertEquals("511NY: Invalid key", e.message)
        }
        try {
            FiveElevenNy.parseEvents("<html>")
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            // MiniJson.JsonException
        }
        assertEquals(emptyList<Incident>(), FiveElevenNy.parseEvents("[]"))
    }

    @Test
    fun `kinds come from the type, subtype, lanes or wording`() {
        assertEquals(IncidentKind.CLOSURE, FiveElevenNy.kindOf("closures", null, null, "x"))
        assertEquals(IncidentKind.CLOSURE, FiveElevenNy.kindOf("roadwork", "Construction", "All Lanes Closed", "x"))
        assertEquals(IncidentKind.CLOSURE, FiveElevenNy.kindOf(null, null, null, "Road closed on NY-9 due to flooding"))
        assertEquals(IncidentKind.ACCIDENT, FiveElevenNy.kindOf("accidentsAndIncidents", "Disabled Vehicle", "1 lane closed", "x"))
        assertEquals(IncidentKind.ROADWORK, FiveElevenNy.kindOf("roadwork", null, null, "x"))
        assertEquals(IncidentKind.EVENT, FiveElevenNy.kindOf("specialEvents", null, null, "x"))
        assertEquals(IncidentKind.OTHER, FiveElevenNy.kindOf("generalInfo", null, null, "x"))
    }

    @Test
    fun `the key goes in the query, encoded`() {
        assertEquals("https://511ny.org/api/getevents?key=a%26b&format=json", FiveElevenNy.eventsUrl("a&b"))
        assertNull(FiveElevenNy.parseDate(""))
        assertNull(FiveElevenNy.parseDate("yesterday"))
    }
}
