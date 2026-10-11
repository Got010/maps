package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Delaware's DelDOT ([DelDot]). The rows in src/test/resources/mdde are real, printed by the feed probe on
 * 2026-10-11: two advisories (road work, a road closure), two signs, two cameras.
 */
class DelDotTest {
    private fun res(name: String) = File("src/test/resources/mdde/$name").readText()

    @Test
    fun `advisories keep their point, road and kind`() {
        val events = DelDot.parseAdvisories(res("de-advisories.json"))
        assertEquals(2, events.size)
        val work = events[0]
        assertEquals("de:T20261011-0001", work.id)
        assertEquals(IncidentKind.ROADWORK, work.kind)
        assertEquals("Pulaski Highway", work.roadway)
        assertEquals("Westbound", work.direction)
        assertEquals(39.620238, work.location.lat, 1e-9)
        assertEquals(-75.690813, work.location.lon, 1e-9)
        assertEquals(1791676894868L, work.updatedAt)
        assertTrue(work.description.startsWith("PULASKI HIGHWAY WESTBOUND BETWEEN"))
        assertTrue(work.description.endsWith("EXPECT DELAYS. UNTIL 6:00 AM."))
        assertFalse(work.isClosure)
        assertEquals(TrafficSource.DE_DELDOT, work.source)

        val closed = events[1]
        assertEquals(IncidentKind.CLOSURE, closed.kind)
        assertTrue(closed.isClosure)
        // The address's house number is dropped.
        assertEquals("Silverside Road", closed.roadway)
        assertNull(closed.direction)
    }

    @Test
    fun `advisory codes map to kinds`() {
        assertEquals(IncidentKind.CLOSURE, DelDot.kindOf("RC", ""))
        assertEquals(IncidentKind.CLOSURE, DelDot.kindOf("EM", ""))
        assertEquals(IncidentKind.ROADWORK, DelDot.kindOf("C", ""))
        assertEquals(IncidentKind.OTHER, DelDot.kindOf("ST", "BEACH TRAFFIC HEAVY"))
        // A special travel alert's words never make it a closure.
        assertEquals(IncidentKind.OTHER, DelDot.kindOf("ST", "Road closed for the parade"))
    }

    @Test
    fun `signs break pages at the dashes`() {
        val signs = DelDot.parseSigns(res("de-signs.json"))
        assertEquals(2, signs.size)
        val s = signs[0]
        assertEquals("de:KVMS001", s.id)
        assertEquals("DE 1 @ NORTH OF THOMPSONVILLE", s.name)
        assertEquals(listOf("FASTER", "IS", "FATAL", "", "SLOWER", "IS", "SAFE"), s.lines)
        assertEquals("DE 1", s.roadway)
        assertEquals(1791681920955L, s.updatedAt)
        assertEquals(TrafficSource.DE_DELDOT, s.source)
    }

    @Test
    fun `sign lines drop blanks, tags and stray breaks`() {
        assertEquals(listOf("A", "", "B"), DelDot.signLines("---------<br/>A<br/><br/>---------<br/>---------<br/>B<br/>---------"))
        assertEquals(listOf("RIGHT LANE", "CLOSED"), DelDot.signLines("<b>RIGHT  LANE</b><BR>CLOSED"))
        assertEquals(emptyList<String>(), DelDot.signLines(""))
        assertEquals(emptyList<String>(), DelDot.signLines(null))
    }

    @Test
    fun `a turned-off sign shows nothing`() {
        val json = """{"signTypes":[{"signs":[{"permit":"X1","lat":39.0,"lon":-75.5,"enable":false,"title":"I 95 NB","message":"OLD"}]}]}"""
        val s = DelDot.parseSigns(json).single()
        assertTrue(s.isBlank)
        assertEquals("I-95", s.roadway)
        assertEquals("Northbound", s.direction)
    }

    @Test
    fun `cameras get their https stream`() {
        val cams = DelDot.parseCameras(res("de-cameras.json"))
        assertEquals(2, cams.size)
        val c = cams[0]
        assertEquals("de:KCAM001", c.id)
        assertEquals("DE 1 @ MILFORD NECK ROAD (NORTH OFF)", c.name)
        assertEquals("DE 1", c.roadway)
        assertNull(c.imageUrl)
        assertEquals("https://video.deldot.gov:443/live/KCAM001.stream/playlist.m3u8", c.videoUrl)
        assertFalse(c.disabled)
        assertTrue(c.hasImage)
    }

    @Test
    fun `an inactive or foreign camera has no stream`() {
        val json = """{"videoCameras":[
            {"id":"A","title":"A","lat":39.0,"lon":-75.5,"enabled":true,"status":"Do Not Display","urls":{"m3u8s":"https://video.deldot.gov:443/live/A.stream/playlist.m3u8"}},
            {"id":"B","title":"B","lat":39.0,"lon":-75.5,"enabled":true,"status":"Active","urls":{"m3u8s":"https://evil.example.com/live/B.m3u8"}},
            {"id":"C","title":"C","lat":39.0,"lon":-75.5,"enabled":false,"status":"Active","urls":{"m3u8s":"https://video.deldot.gov/live/C.stream/playlist.m3u8"}}]}"""
        val cams = DelDot.parseCameras(json)
        assertTrue(cams[0].disabled)
        assertNull(cams[1].videoUrl)
        assertTrue(cams[1].disabled)
        assertTrue(cams[2].disabled)
        assertFalse(cams.any { it.hasImage })
    }

    @Test
    fun `roads and directions from DelDOT's words`() {
        assertEquals("I-495", DelDot.roadIn("I 495 @ HAINES AVE"))
        assertEquals("DE 1", DelDot.roadIn("SR 1 SB @ 1/2 MILE NORTH OF BO"))
        assertEquals("US 13", DelDot.roadIn("US13 AT DE 1"))
        assertNull(DelDot.roadIn("SILVERSIDE RD BETWEEN VEALE RD AND MARSH RD"))
        assertEquals("Southbound", DelDot.directionIn("DE 1 SB @ 1/2 MILE NORTH OF BO"))
        assertEquals("Westbound", DelDot.directionIn("PULASKI HIGHWAY WESTBOUND BETWEEN"))
        assertNull(DelDot.directionIn("DE 1 @ DE 9"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an answer that isn't DelDOT's file is refused`() {
        DelDot.parseAdvisories("""{"something":"else"}""")
    }

    @Test
    fun `the client asks for the whole state's three files and nothing else`() {
        val asked = ArrayList<String>()
        val client = DelDot.Client { url ->
            asked += url
            when (url) {
                DelDot.ADVISORIES_URL -> res("de-advisories.json")
                DelDot.SIGNS_URL -> res("de-signs.json")
                else -> res("de-cameras.json")
            }.toByteArray()
        }
        assertEquals(2, client.events(0).size)
        assertEquals(2, client.signs().size)
        assertEquals(2, client.cameras().size)
        assertEquals(listOf(DelDot.ADVISORIES_URL, DelDot.SIGNS_URL, DelDot.CAMERAS_URL), asked)
        assertTrue(asked.all { it.startsWith("https://tmc.deldot.gov/json/") && '?' !in it })
    }
}
