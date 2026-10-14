package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kentucky's KYTC ([Kentucky]). src/test/resources/ky is real, printed by the feed probe on 2026-10-14: twelve rows
 * of the incident reports layer (rest areas, work zones, blocked roads, three hazards that had ended), six closures
 * (three in effect that night, one long over) and six of Kentucky's cameras with two of Indiana's that ride along in
 * the layer. Rows written here stand in for the active window's edge and a closure that is also reported.
 */
class KentuckyTest {
    private fun res(name: String) = File("src/test/resources/ky/$name").readText()

    /** 2026-10-14 01:56:40 UTC, a minute after the probe's newest capture. */
    private val now = 1_791_943_000_000L

    @Test
    fun `closures in effect come first, at their line's middle`() {
        val events = Kentucky.parseEvents(res("incidents.json"), res("closures.json"), now)
        val closures = events.takeWhile { it.kind == IncidentKind.CLOSURE && it.updatedAt == null }
        assertEquals(listOf("ky:TOC-24686", "ky:TOC-25222", "ky:TOC-25194"), closures.map { it.id })
        val bridge = closures[0]
        assertEquals("US 62", bridge.roadway)
        assertEquals("Both Directions", bridge.direction)
        assertEquals("All lanes closed", bridge.lanesStatus)
        assertTrue(bridge.isClosure)
        assertEquals(TrafficSource.KY_KYTC, bridge.source)
        // The Simon Kenton bridge's line runs from (38.6458, -83.7624) to (38.6523, -83.7583).
        assertEquals(38.649, bridge.location.lat, 0.001)
        assertEquals(-83.760, bridge.location.lon, 0.001)
        // A local street keeps its own name, not the detour's route the description names.
        assertEquals("Bristow Rd", closures[1].roadway)
        // KY 80's closure ended in February 2025.
        assertFalse(events.any { it.id == "ky:TOC-15889" })
    }

    @Test
    fun `reports are read with their kind, road and direction`() {
        val reports = Kentucky.parseEvents(res("incidents.json"), res("closures.json"), now).filter { it.updatedAt != null }
        // Three of TRIMARC's hazards ended hours before the newest capture.
        assertEquals(9, reports.size)
        assertFalse(reports.any { it.id.startsWith("ky:TRIMARC-") })
        val restArea = reports.first { it.id == "ky:TOC-24706" }
        // A rest area that's shut is not a road that's shut.
        assertEquals(IncidentKind.OTHER, restArea.kind)
        assertEquals("I-71", restArea.roadway)
        assertEquals("Northbound", restArea.direction)
        assertEquals(GeoPoint(38.33762, -85.516174), restArea.location)
        assertEquals(1_791_942_908_000L, restArea.updatedAt)
        val workZone = reports.first { it.id == "ky:TOC-10766" }
        assertEquals(IncidentKind.ROADWORK, workZone.kind)
        assertEquals("S Jefferson St", workZone.roadway)
        assertNull(workZone.lanesStatus)
        val both = reports.first { it.id == "ky:TOC-10754" }
        assertEquals("KY 56", both.roadway)
        assertEquals("Both Directions", both.direction)
        // Filed as a work zone, but its words shut the bridge.
        val singing = reports.first { it.id == "ky:TOC-12787" }
        assertEquals(IncidentKind.CLOSURE, singing.kind)
        assertEquals("All lanes closed", singing.lanesStatus)
        assertEquals(IncidentKind.CLOSURE, reports.first { it.id == "ky:TOC-15508" }.kind)
    }

    @Test
    fun `only reports near the newest capture are in effect, and a closure stands for its report`() {
        val incidents = """{"features":[
            {"attributes":{"EventId":"TOC-1","Lat":38.2,"Long":-85.7,"Description":"Crash on I-65 northbound.","KYTC_Type":"Crash","EndDate":1791942841000}},
            {"attributes":{"EventId":"TOC-2","Lat":38.2,"Long":-85.7,"Description":"Debris.","EndDate":1791941100000}},
            {"attributes":{"EventId":"TOC-3","Lat":38.2,"Long":-85.7,"Description":"Crash cleared.","KYTC_Type":"Crash","EndDate":1791930000000}},
            {"attributes":{"EventId":"TOC-24686","Lat":38.6,"Long":-83.7,"Description":"Bridge closed.","EndDate":1791942841000}}
        ]}"""
        val reports = Kentucky.parseIncidents(incidents)
        assertEquals(listOf("ky:TOC-1", "ky:TOC-2", "ky:TOC-24686"), reports.map { it.id })
        assertEquals(IncidentKind.ACCIDENT, reports[0].kind)
        assertEquals("Northbound", reports[0].direction)
        val events = Kentucky.parseEvents(incidents, res("closures.json"), now)
        assertEquals(1, events.count { it.id == "ky:TOC-24686" })
        assertTrue(events.first { it.id == "ky:TOC-24686" }.description.startsWith("Boith directions blocked"))
    }

    @Test
    fun `cameras are Kentucky's own, with TRIMARC stills`() {
        val cameras = Kentucky.parseCameras(res("cameras.json"))
        assertEquals(6, cameras.size)
        val first = cameras[0]
        assertEquals("ky:CCTV05161", first.id)
        assertEquals("I-71 at Old Brownsboro Rd", first.name)
        assertEquals("I-71", first.roadway)
        assertEquals("Northbound", first.direction)
        assertEquals("https://www.trimarc.org/images/milestone/CCTV_05_71_0103.jpg", first.imageUrl)
        assertEquals(38.320179, first.lat, 1e-9)
        assertFalse(first.disabled)
        assertTrue(first.hasImage)
        assertEquals(TrafficSource.KY_KYTC, first.source)
        // "North-South" names no one direction.
        assertNull(cameras[2].direction)
    }

    @Test
    fun `stills only on TRIMARC's server, over https`() {
        assertEquals(
            "https://www.trimarc.org/images/snapshots/IND_CCTV005.jpg",
            Kentucky.stillOf("http://www.trimarc.org/images/snapshots/IND_CCTV005.jpg"),
        )
        assertNull(Kentucky.stillOf("http://pws.trafficwise.org/pullover/172_65_56_11.jpg"))
        assertNull(Kentucky.stillOf("https://www.trimarc.org/images/milestone/CCTV.jpg?viewer=1"))
        assertNull(Kentucky.stillOf("https://www.trimarc.org/cameras/CCTV.html"))
        val offline = Kentucky.parseCameras(
            """{"features":[{"attributes":{"name":"CCTV1","status":"Offline","state":"Kentucky","latitude":38.0,"longitude":-85.0,""" +
                """"snapshot":"https://www.trimarc.org/images/milestone/CCTV_1.jpg"}}]}""",
        )
        assertTrue(offline.single().disabled)
        assertFalse(offline.single().hasImage)
    }

    @Test
    fun `kinds, roads and directions`() {
        assertEquals(IncidentKind.CLOSURE, Kentucky.kindOf("Work Zone", null, "KY 80 is blocked until further notice to all traffic"))
        assertEquals(IncidentKind.CLOSURE, Kentucky.kindOf(null, null, "Roadway blocked due to emergency repairs."))
        assertEquals(IncidentKind.ROADWORK, Kentucky.kindOf("Work Zone", "Construction / Road Work", "Lane blocked for mobile maintenance."))
        assertEquals(IncidentKind.ACCIDENT, Kentucky.kindOf("Crash", null, "Crash on I-65."))
        assertEquals(IncidentKind.OTHER, Kentucky.kindOf("Hazard", "Other", "Debris in the right lane."))
        // A blocked lane or shoulder is not a closure.
        assertEquals(IncidentKind.ROADWORK, Kentucky.kindOf("Work Zone", null, "Road blocked lane ahead"))
        assertEquals("I-64", Kentucky.roadIn("I 64"))
        assertEquals("KY 56", Kentucky.roadIn("KY-56"))
        assertEquals("US 60", Kentucky.roadIn("US 60-X/Bridge Street"))
        assertNull(Kentucky.roadIn("BRISTOW RD"))
        assertEquals("Both Directions", Kentucky.directionIn("In both directions, the bridge is closed"))
        assertEquals("Southbound", Kentucky.directionIn("Southbound Rest Area temporarily closed."))
    }

    @Test
    fun `the client reads the three layers under the toggle and has no signs`() {
        val asked = mutableListOf<String>()
        val client = Kentucky.Client { url ->
            asked += url
            when (url) {
                Kentucky.INCIDENTS_URL -> res("incidents.json")
                Kentucky.CLOSURES_URL -> res("closures.json")
                Kentucky.CAMERAS_URL -> res("cameras.json")
                else -> error(url)
            }.encodeToByteArray()
        }
        assertEquals(TrafficSource.KY_KYTC, client.source)
        assertEquals(12, client.events(now).size)
        assertEquals(6, client.cameras().size)
        assertTrue(client.signs().isEmpty())
        assertEquals(listOf(Kentucky.INCIDENTS_URL, Kentucky.CLOSURES_URL, Kentucky.CAMERAS_URL), asked)
        // Nothing about the user: the whole layer, every time.
        assertTrue(asked.all { "where=1%3D1" in it && it.startsWith("https://services2.arcgis.com/") })
    }

    @Test
    fun `an error or something else is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Kentucky.parseIncidents("<html>busy</html>") }
        assertThrows(IllegalArgumentException::class.java) { Kentucky.parseCameras("""{"error":{"code":400,"message":"Invalid query"}}""") }
        assertThrows(IllegalArgumentException::class.java) { Kentucky.parseClosures("""{"type":"FeatureCollection"}""") }
        assertTrue(Kentucky.parseIncidents("""{"features":[]}""").isEmpty())
    }
}
