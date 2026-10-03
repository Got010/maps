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

/** 511NY's getcameras and getmessagesigns, from hand-written fixtures (field names in FiveElevenNy). */
class FiveElevenNyCamerasSignsTest {

    private val cameras = File("src/test/resources/511ny-cameras.json").readText()
    private val signs = File("src/test/resources/511ny-signs.json").readText()

    @Test
    fun `parses cameras and skips rows without an id or a position`() {
        val list = FiveElevenNy.parseCameras(cameras)
        assertEquals(listOf("NYSDOT_4616", "1204", "NYCDOT_311", "TT-77", "NO-IMAGE"), list.map { it.id })

        val exit17 = list[0]
        assertEquals("I-87 at Exit 17 (Newburgh)", exit17.name)
        assertEquals("I-87", exit17.roadway)
        assertEquals("Northbound", exit17.direction)
        assertEquals(41.4518, exit17.lat, 1e-9)
        assertEquals(-74.0547, exit17.lon, 1e-9)
        // Plain http on 511NY's own host is upgraded; Android blocks cleartext.
        assertEquals("https://511ny.org/map/Cctv/NYSDOT_4616", exit17.imageUrl)
        assertFalse(exit17.disabled)
        assertTrue(exit17.hasImage)
    }

    @Test
    fun `numeric ids, string coordinates, relative links and offline cameras`() {
        val list = FiveElevenNy.parseCameras(cameras).associateBy { it.id }

        val fdr = list.getValue("1204")
        assertEquals(GeoPoint(40.7835, -73.9431), fdr.location)
        assertEquals("https://511ny.org/map/Cctv/1204", fdr.imageUrl)
        assertNull(fdr.direction) // "Unknown"
        assertTrue(fdr.disabled)
        assertFalse(fdr.hasImage)

        val broadway = list.getValue("NYCDOT_311")
        assertTrue(broadway.disabled) // Blocked: "true"
        assertEquals("https://webcams.nyctmc.org/api/cameras/311/image", broadway.imageUrl)
        assertNull(broadway.direction)

        // The newer layout: Id, Location, Roadway, Direction and Views[].
        val ny17 = list.getValue("TT-77")
        assertEquals("NY-17 at Exit 120", ny17.name)
        assertEquals("NY-17", ny17.roadway)
        assertEquals("Westbound", ny17.direction)
        assertEquals("https://511ny.org/map/Cctv/TT-77--1", ny17.imageUrl)
        assertFalse(ny17.disabled)

        // No name: road and direction stand in; every view off: offline.
        val noImage = list.getValue("NO-IMAGE")
        assertEquals("I-90 Eastbound", noImage.name)
        assertNull(noImage.imageUrl)
        assertTrue(noImage.disabled)
    }

    @Test
    fun `parses signs into display lines`() {
        val list = FiveElevenNy.parseSigns(signs)
        assertEquals(listOf("VMS-87-17N", "NYC-TT-12", "VMS-BLANK", "5501", "NO-MESSAGES"), list.map { it.id })

        val crash = list[0]
        assertEquals("I-87 NB before Exit 17", crash.name)
        assertEquals(listOf("CRASH AT EXIT 17", "RIGHT LANE CLOSED", "EXPECT DELAYS"), crash.lines)
        assertEquals("I-87", crash.roadway)
        assertEquals("Northbound", crash.direction)
        val expected = ZonedDateTime.of(2026, 10, 2, 22, 15, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        assertEquals(expected, crash.updatedAt)
        assertFalse(crash.isBlank)

        // MULTI markup: [nl] a new line, [np] a new page (an empty line), formatting tags dropped.
        val travelTime = list[1]
        assertEquals(listOf("TRAVEL TIME TO", "GWB 12 MIN", "", "DRIVE SAFELY"), travelTime.lines)
        assertEquals("Henry Hudson Parkway", travelTime.roadway)
        assertEquals(GeoPoint(40.785, -73.985), travelTime.location)
        assertNull(travelTime.updatedAt)

        val blank = list[2]
        assertEquals(emptyList<String>(), blank.lines)
        assertTrue(blank.isBlank)

        // Numeric Id, Message/Message2 and <br/>; no name, so the road stands in.
        val amber = list[3]
        assertEquals("NY-17", amber.name)
        assertNull(amber.direction)
        assertEquals(listOf("AMBER ALERT", "CALL 911", "TUNE TO 1610 AM"), amber.lines)

        assertTrue(list[4].isBlank)
    }

    @Test
    fun `sign lines`() {
        assertEquals(emptyList<String>(), FiveElevenNy.signLines(emptyList()))
        assertEquals(listOf("A", "", "B"), FiveElevenNy.signLines(listOf("[np]A[np][np]B[np]")))
        assertEquals(listOf("LEFT LANE", "CLOSED"), FiveElevenNy.signLines(listOf("[pt30o0][cf255,255,0]LEFT LANE\r\nCLOSED[/fl]")))
        // Brackets that aren't MULTI tags stay.
        assertEquals(listOf("[EXIT 5] CLOSED"), FiveElevenNy.signLines(listOf("[EXIT 5] CLOSED")))
        assertEquals(emptyList<String>(), FiveElevenNy.signLines(listOf("no_message", " ")))
    }

    @Test
    fun `error documents are reported`() {
        try {
            FiveElevenNy.parseCameras("""{"Message":"Invalid key"}""")
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertEquals("511NY: Invalid key", e.message)
        }
        try {
            FiveElevenNy.parseSigns("{}")
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertEquals("511NY sent no sign list", e.message)
        }
        try {
            FiveElevenNy.parseSigns("<html>")
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            // MiniJson.JsonException
        }
        assertEquals(emptyList<TrafficCamera>(), FiveElevenNy.parseCameras("[]"))
    }

    @Test
    fun `the key goes in the query, and nothing else`() {
        assertEquals("https://511ny.org/api/getcameras?key=a%26b&format=json", FiveElevenNy.camerasUrl("a&b"))
        assertEquals("https://511ny.org/api/getmessagesigns?key=K&format=json", FiveElevenNy.signsUrl("K"))
    }
}
