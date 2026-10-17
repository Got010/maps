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
        assertEquals(listOf("NY-511-1001", "NY-511-1002", "NY-511-1003", "NY-511-1005", "NY-511-1006", "NY-511-1007", "NY-511-1008", "NY-511-1009"), events.map { it.id })

        val crash = events[0]
        assertEquals(IncidentKind.CLOSURE, crash.kind) // a crash, but every lane is shut
        assertTrue(crash.isClosure)
        assertEquals("I-87 Northbound", crash.title)
        assertEquals(GeoPoint(43.5300, -73.6200), crash.location)
        assertEquals("Major", crash.severity)
        assertEquals("All lanes closed", crash.lanesStatus)
        val expected = ZonedDateTime.of(2026, 10, 2, 22, 15, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertEquals(expected, crash.updatedAt)

        val work = events[1]
        assertEquals(IncidentKind.ROADWORK, work.kind)
        assertFalse(work.isClosure)
        assertEquals(GeoPoint(40.7995, -73.9685), work.location) // numbers as strings

        val ramp = events[2]
        // Filed under closures, but a ramp: NY-17 itself stays open.
        assertEquals(IncidentKind.OTHER, ramp.kind)
        assertFalse(ramp.isClosure)
        assertNull(ramp.updatedAt)

        val parade = events[3]
        assertEquals(IncidentKind.EVENT, parade.kind)
        assertNull(parade.roadway)
        assertEquals("Parade on 5th Avenue.", parade.title)

        val police = events[4]
        assertEquals(IncidentKind.POLICE, police.kind) // subtype, though filed under accidentsAndIncidents
        assertFalse(police.isClosure)
        assertEquals("I-87 Southbound", police.title)

        assertEquals(IncidentKind.POLICE, events[5].kind) // wording alone, in an unclassified event
        assertEquals(IncidentKind.CLOSURE, events[6].kind) // police activity that shuts every lane
        assertTrue(events[6].isClosure)
        assertEquals(IncidentKind.ACCIDENT, events[7].kind) // "police on scene" doesn't turn a disabled vehicle into police activity
    }

    @Test
    fun `reads the v2 layout - numeric ids, Unix times and IsFullClosure`() {
        val events = FiveElevenNy.parseEvents(File("src/test/resources/511ny-events-v2.json").readText())
        assertEquals(listOf("482913", "482914"), events.map { it.id })

        val crash = events[0]
        assertEquals(IncidentKind.ACCIDENT, crash.kind)
        assertEquals("I-90 Eastbound", crash.title)
        assertEquals("1 Right lane closed", crash.lanesStatus)
        assertEquals(1791334800_000L, crash.updatedAt) // seconds

        val bridge = events[1]
        assertEquals(IncidentKind.CLOSURE, bridge.kind) // IsFullClosure, though filed as roadwork
        assertTrue(bridge.isClosure)
        assertEquals(1791334800_000L, bridge.updatedAt) // already milliseconds
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
    fun `police activity is found by type, subtype or wording`() {
        assertEquals(IncidentKind.POLICE, FiveElevenNy.kindOf("policeActivity", null, null, "x"))
        assertEquals(IncidentKind.POLICE, FiveElevenNy.kindOf("accidentsAndIncidents", "Police Activity", null, "x"))
        assertEquals(IncidentKind.POLICE, FiveElevenNy.kindOf("accidentsAndIncidents", "Law Enforcement Activity", "1 lane closed", "x"))
        assertEquals(IncidentKind.POLICE, FiveElevenNy.kindOf("generalInfo", null, null, "Traffic stop on the shoulder"))
        assertEquals(IncidentKind.POLICE, FiveElevenNy.kindOf("generalInfo", null, null, "Radar enforcement near Exit 4"))
        assertEquals(IncidentKind.POLICE, FiveElevenNy.kindOf("generalInfo", null, null, "Speed enforcement on NY-17"))
        // A police event that is also a closure stays a closure, by type, lanes or wording.
        assertEquals(IncidentKind.CLOSURE, FiveElevenNy.kindOf("closures", "Police Activity", null, "x"))
        assertEquals(IncidentKind.CLOSURE, FiveElevenNy.kindOf("accidentsAndIncidents", "Police Activity", "All lanes closed", "x"))
        assertEquals(IncidentKind.CLOSURE, FiveElevenNy.kindOf(null, null, null, "Road closed for police activity"))
        // Wording does not override an event the feed already classed.
        assertEquals(IncidentKind.ACCIDENT, FiveElevenNy.kindOf("accidentsAndIncidents", "Crash", null, "Police on scene"))
        assertEquals(IncidentKind.ROADWORK, FiveElevenNy.kindOf("roadwork", null, null, "Police detail for work zone"))
    }

    @Test
    fun `the key goes in the query, encoded`() {
        assertEquals("https://511ny.org/api/getevents?key=a%26b&format=json", FiveElevenNy.eventsUrl("a&b"))
        assertNull(FiveElevenNy.parseDate(""))
        assertNull(FiveElevenNy.parseDate("yesterday"))
    }
}
