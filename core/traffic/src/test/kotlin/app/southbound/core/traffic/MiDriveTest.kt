package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Michigan's Mi Drive ([MiDrive]). The first rows in src/test/resources/mi are real, printed by the feed probe on
 * 2026-10-11 (the crash, two work zones, two cameras, camera 1129's detail); a cleared crash, a road closed, a total
 * closure, an inactive zone and a camera with a link elsewhere stand in with the same keys.
 */
class MiDriveTest {
    private fun res(name: String) = File("src/test/resources/mi/$name").readText()

    @Test
    fun `incidents read their card, cleared ones skipped`() {
        val incidents = MiDrive.parseIncidents(res("incidents.json"))
        assertEquals(listOf("mi:1161050", "mi:1161052"), incidents.map { it.id })
        val crash = incidents[0]
        assertEquals(IncidentKind.ACCIDENT, crash.kind)
        assertEquals("M-43", crash.roadway)
        assertEquals("Eastbound", crash.direction)
        assertEquals("Right Center Lane, Right Lane blocked", crash.lanesStatus)
        assertEquals("EB M-43 (Main St) at Maple Hill Dr has the right 2 lanes blocked due to a crash Kalamazoo County", crash.description)
        assertEquals(GeoPoint(42.296116, -85.65789), crash.location)
        assertTrue(crash.updatedAt != null)
        assertEquals(TrafficSource.MI_DRIVE, crash.source)
        val closed = incidents[1]
        assertEquals(IncidentKind.CLOSURE, closed.kind)
        assertEquals("I-475", closed.roadway)
        assertEquals("Northbound", closed.direction)
        assertEquals("All lanes closed", closed.lanesStatus)
    }

    @Test
    fun `work zones are road work, a total closure a closure, inactive ones skipped`() {
        val zones = MiDrive.parseConstruction(res("construction.json"))
        assertEquals(listOf("mi:ETX-21385", "mi:ETX-21250", "mi:ETX-21000"), zones.map { it.id })
        val lane = zones[0]
        assertEquals(IncidentKind.ROADWORK, lane.kind)
        assertEquals("US-31 BR", lane.roadway)
        assertEquals("Southbound", lane.direction)
        assertEquals("Single-Lane Closure", lane.lanesStatus)
        assertEquals("SB US-31 BR: Single-Lane Closure", lane.description)
        assertEquals(GeoPoint(43.173918, -86.210415), lane.location)
        assertNull(lane.updatedAt)
        assertEquals("I-196 BL", zones[1].roadway)
        val total = zones[2]
        assertEquals(IncidentKind.CLOSURE, total.kind)
        assertEquals("M-43", total.roadway)
        assertEquals("All lanes closed", total.lanesStatus)
    }

    @Test
    fun `cameras point at their detail page for the still`() {
        val cameras = MiDrive.parseCameras(res("cameras.json"))
        assertEquals(3, cameras.size)
        val mound = cameras[0]
        assertEquals("mi:1129", mound.id)
        assertEquals("11 Mile @ Mound NB", mound.name)
        assertNull(mound.imageUrl)
        assertEquals("https://mdotjboss.state.mi.us/MiDrive/camera/getCameraInformation/1129", mound.stillVia)
        assertTrue(mound.hasImage)
        assertEquals("Northbound", mound.direction)
        assertNull(mound.roadway)
        assertEquals(42.491304, mound.lat, 0.0)
        // A link in the list is kept when on the stills' server; elsewhere it isn't.
        assertEquals("https://micamerasimages.net/thumbs/grand_cam_036.flv.jpg?item=1", cameras[1].imageUrl)
        assertNull(cameras[2].imageUrl)
        assertEquals("I-75", cameras[2].roadway)
        assertEquals("https://micamerasimages.net/thumbs/semtoc_cam_253.flv.jpg?item=1", MiDrive.stillOf(res("camera-1129.json")))
        assertNull(MiDrive.stillOf("""{"link":"http://micamerasimages.net/thumbs/x.jpg"}"""))
        assertNull(MiDrive.stillOf("""{"link":"https://elsewhere.example.com/x.jpg"}"""))
        assertNull(MiDrive.stillOf("<html>"))
    }

    @Test
    fun `fields, kinds, roads and clock times`() {
        val fields = MiDrive.fields("<div><strong>Location: </strong>EB M-43 at Maple Hill Dr</div><div><strong>Reported:</strong> 10:06 AM")
        assertEquals(mapOf("Location" to "EB M-43 at Maple Hill Dr", "Reported" to "10:06 AM"), fields)
        assertEquals(IncidentKind.CLOSURE, MiDrive.kindOf("Crash", "NB I-475 is closed", "All Lanes blocked"))
        assertEquals(IncidentKind.ACCIDENT, MiDrive.kindOf("Disabled Vehicle", "on the shoulder", null))
        assertEquals(IncidentKind.ROADWORK, MiDrive.kindOf("Construction", "paving", "Right Lane blocked"))
        assertEquals(IncidentKind.OTHER, MiDrive.kindOf("Debris", "debris in the road", null))
        assertEquals("M-43", MiDrive.roadIn("EB M-43 at Maple Hill Dr"))
        assertEquals("US-31 BR", MiDrive.roadIn("SB US-31 BR"))
        assertEquals("I-75", MiDrive.roadIn("NB I-75 @ W of Springwells"))
        assertNull(MiDrive.roadIn("44th Street @ Division Ave"))
        assertEquals("Westbound", MiDrive.directionIn("WB I-196 @ Plymouth"))
        assertNull(MiDrive.directionIn("44th Street"))
        val detroit = ZoneId.of("America/Detroit")
        val now = ZonedDateTime.of(2026, 10, 11, 10, 33, 0, 0, detroit).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 10, 11, 10, 6, 0, 0, detroit).toInstant().toEpochMilli(), MiDrive.todayAt("10:06 AM", now))
        // Later than now: yesterday's.
        assertEquals(ZonedDateTime.of(2026, 10, 10, 11, 50, 0, 0, detroit).toInstant().toEpochMilli(), MiDrive.todayAt("11:50 AM", now))
        assertNull(MiDrive.todayAt("soon", now))
    }

    @Test
    fun `the client reads the three lists and has no signs`() {
        val asked = ArrayList<String>()
        val client = MiDrive.Client { url ->
            asked += url
            when (url) {
                MiDrive.INCIDENTS_URL -> res("incidents.json")
                MiDrive.CONSTRUCTION_URL -> res("construction.json")
                MiDrive.CAMERAS_URL -> res("cameras.json")
                else -> error(url)
            }.encodeToByteArray()
        }
        assertEquals(TrafficSource.MI_DRIVE, client.source)
        assertEquals(5, client.events(0L).size)
        assertEquals(3, client.cameras().size)
        assertEquals(emptyList<MessageSign>(), client.signs())
        assertEquals(3, asked.size)
        assertTrue(asked.all { it.startsWith("https://mdotjboss.state.mi.us/MiDrive/") && it.endsWith("/AllForMap") })
        assertFalse(client.cameras().any { it.disabled })
    }

    @Test
    fun `something other than the list is refused`() {
        for (bad in listOf("<html>", "{\"features\":[]}", "nope")) {
            try {
                MiDrive.parseIncidents(bad)
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
