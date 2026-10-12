package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Maryland's CHART ([MarylandChart]). The rows in src/test/resources/mdde are real, printed by the feed probe on
 * 2026-10-11: three events (two signal action events, a debris incident with its lanes), five signs, two cameras.
 */
class MarylandChartTest {
    private fun res(name: String) = File("src/test/resources/mdde/$name").readText()

    @Test
    fun `events keep their point, road, direction and kind`() {
        val events = MarylandChart.parseEvents(res("md-events.json"))
        assertEquals(3, events.size)
        val debris = events.single { it.id == "md:490028f6c9e000ca006ad32ec4235c0a" }
        assertEquals(IncidentKind.OTHER, debris.kind)
        assertEquals("MD 298", debris.roadway)
        assertEquals("Southbound", debris.direction)
        assertEquals(39.23765, debris.location.lat, 1e-9)
        assertEquals(-76.15689, debris.location.lon, 1e-9)
        assertEquals(1791681224000L, debris.updatedAt)
        assertEquals(TrafficSource.MD_CHART, debris.source)
        assertEquals("MD 298 Southbound", debris.title)
        val signal = events.single { it.id == "md:6b00a85da53f00ca006ad32ec4235c0a" }
        assertEquals("MD 201", signal.roadway)
        assertEquals("Northbound", signal.direction)
        assertEquals(IncidentKind.OTHER, signal.kind)
        assertNull(signal.lanesStatus)
    }

    @Test
    fun `a one-lane road shut one way with traffic let through the other lane isn't a closure`() {
        val debris = MarylandChart.parseEvents(res("md-events.json")).single { it.id.startsWith("md:490028") }
        assertEquals("Lane closed, traffic shares the other direction's lane", debris.lanesStatus)
        assertFalse(debris.isClosure)
    }

    @Test
    fun `lanes count the traffic lanes in the event's own direction`() {
        fun lane(dir: String, status: String, type: String = "Traffic Lane", flow: String = "primary") =
            mapOf("laneDirection" to dir, "laneStatus" to status, "laneType" to type, "laneTrafficFlowDirection" to flow)
        val three = listOf(
            lane("north", "closed"), lane("north", "open"), lane("north", "open"), lane("north", "closed", "Shoulder"),
            lane("south", "closed"), lane("south", "closed"),
        )
        assertEquals("1 of 3 lanes closed", MarylandChart.lanes(three, "North"))
        assertEquals("All lanes closed", MarylandChart.lanes(three, "South"))
        assertNull(MarylandChart.lanes(listOf(lane("north", "open"), lane("north", "closed", "Shoulder")), "North"))
        assertNull(MarylandChart.lanes(emptyList<Any>(), "North"))
        // No direction: every traffic lane counts.
        assertEquals("3 of 5 lanes closed", MarylandChart.lanes(three, null))
    }

    @Test
    fun `an event is a closure when its lanes are all shut, not by its closed flag`() {
        val lane = """{"laneType":"Traffic Lane","laneDirection":"north","laneStatus":"closed","laneTrafficFlowDirection":"primary"}"""
        val json = """{"data":[
            {"id":"x1","lat":39.0,"lon":-76.5,"closed":true,"incidentType":"Property Damage",
             "description":"Incident @ I-695 INNER LOOP AT EXIT 4","direction":"North","lanes":[]},
            {"id":"x2","lat":39.1,"lon":-76.6,"closed":false,"incidentType":"Property Damage",
             "description":"Incident @ I-695 INNER LOOP AT EXIT 5","direction":"North","lanes":[$lane,$lane]}
            ],"success":true}"""
        val (flagged, shut) = MarylandChart.parseEvents(json)
        assertEquals(IncidentKind.ACCIDENT, flagged.kind)
        assertNull(flagged.lanesStatus)
        assertFalse(flagged.isClosure)
        assertEquals(IncidentKind.ACCIDENT, shut.kind)
        assertEquals("All lanes closed", shut.lanesStatus)
        assertEquals("I-695", shut.roadway)
        assertTrue(shut.isClosure)
    }

    @Test
    fun `incident types map to kinds`() {
        assertEquals(IncidentKind.ACCIDENT, MarylandChart.kindOf("Property Damage", "Incident @ I-95"))
        assertEquals(IncidentKind.ACCIDENT, MarylandChart.kindOf("Personal Injury", ""))
        assertEquals(IncidentKind.ACCIDENT, MarylandChart.kindOf("Disabled In Roadway", ""))
        assertEquals(IncidentKind.POLICE, MarylandChart.kindOf("Police Activity", ""))
        assertEquals(IncidentKind.ROADWORK, MarylandChart.kindOf("Roadwork", ""))
        assertEquals(IncidentKind.EVENT, MarylandChart.kindOf("Special Event", ""))
        assertEquals(IncidentKind.OTHER, MarylandChart.kindOf("Debris In Roadway", "Incident @ MD 298"))
        // Wording alone never makes a closure: only `closed` does.
        assertEquals(IncidentKind.OTHER, MarylandChart.kindOf("Other", "Road closed at MD 2"))
    }

    @Test
    fun `signs read MULTI into lines and pages`() {
        val signs = MarylandChart.parseSigns(res("md-signs.json"))
        assertEquals(5, signs.size)
        val tunnel = signs.single { it.id == "md:b100036c009500a7004af02c76235daa" }
        assertEquals(listOf("HAZMATS", "PROHIBITED", "IN TUNNELS", "", "I-695 5 MILES", "5 MINUTES"), tunnel.lines)
        assertEquals("I-97", tunnel.roadway)
        assertEquals("Northbound", tunnel.direction)
        assertEquals("I-97 North past Exit 12 Crain Highway", tunnel.name)
        assertEquals(1791575537874L, tunnel.updatedAt)
        assertTrue(signs.single { it.id == "md:d00007f2001000190062d32cc4235c0a" }.isBlank)
        val i95 = signs.single { it.id == "md:f5000493003b00600050fa2cc4235c0a" }
        assertEquals("I-95", i95.roadway)
        assertEquals("Southbound", i95.direction)
    }

    @Test
    fun `cameras get their stream, never a still`() {
        val cams = MarylandChart.parseCameras(res("md-cameras.json"))
        assertEquals(2, cams.size)
        val c = cams.first()
        assertEquals("md:7a00a1dc01250075004d823633235daa", c.id)
        assertEquals("I-270 & Old Hundred Rd (MD 109)", c.name)
        assertEquals("I-270", c.roadway)
        assertNull(c.imageUrl)
        assertEquals("https://strmr5.sha.maryland.gov/rtplive/7a00a1dc01250075004d823633235daa/playlist.m3u8", c.videoUrl)
        assertTrue(c.hasImage)
        // An all-digit name is a sign number: the description names it instead.
        assertEquals("I-70 East of I-270", cams[1].name)
        assertEquals("I-70", cams[1].roadway)
    }

    @Test
    fun `a failed or foreign-hosted camera has no stream`() {
        val json = """{"data":[
            {"id":"a","lat":39.0,"lon":-76.0,"name":"A","opStatus":"COMM_FAILURE","cctvIp":"strmr5.sha.maryland.gov"},
            {"id":"b","lat":39.0,"lon":-76.0,"name":"B","opStatus":"OK","cctvIp":"evil.example.com"}],"success":true}"""
        val cams = MarylandChart.parseCameras(json)
        assertTrue(cams[0].disabled)
        assertFalse(cams[0].hasImage)
        assertNull(cams[1].videoUrl)
        assertTrue(cams[1].disabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an answer that isn't the export is refused`() {
        MarylandChart.parseEvents("<html>maintenance</html>")
    }

    @Test
    fun `the client asks for the whole state's three exports and nothing else`() {
        val asked = ArrayList<String>()
        val client = MarylandChart.Client { url ->
            asked += url
            when (url) {
                MarylandChart.EVENTS_URL -> res("md-events.json")
                MarylandChart.SIGNS_URL -> res("md-signs.json")
                else -> res("md-cameras.json")
            }.toByteArray()
        }
        assertEquals(3, client.events(0).size)
        assertEquals(5, client.signs().size)
        assertEquals(2, client.cameras().size)
        assertEquals(listOf(MarylandChart.EVENTS_URL, MarylandChart.SIGNS_URL, MarylandChart.CAMERAS_URL), asked)
        assertTrue(asked.all { it.startsWith("https://chartexp1.sha.maryland.gov/") && '?' !in it })
    }
}
