package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * North Dakota's travel map ([NorthDakota]). The rows in src/test/resources/nd are real, printed by the feed probe on
 * 2026-10-11 (two alerts, three work zones, two cameras); a road-closed alert, an inactive work zone, a closed work
 * zone and a camera elsewhere stand in beside them with the same keys.
 */
class NorthDakotaTest {
    private fun res(name: String) = File("src/test/resources/nd/$name").readText()

    @Test
    fun `alerts are read at their centre`() {
        // As on 27 April 2026, when all three were in effect.
        val alerts = NorthDakota.parseAlerts(res("alerts.json"), now = 1_777_286_230_440L)
        assertEquals(listOf("nd:alert-39884", "nd:alert-40290", "nd:alert-40563"), alerts.map { it.id })
        val pavement = alerts[0]
        assertEquals(IncidentKind.OTHER, pavement.kind)
        assertEquals("ND 89", pavement.roadway)
        assertEquals("Eastbound", pavement.direction)
        assertEquals("Warning: Broken Pavement   Reduce Speed and Use Caution", pavement.description)
        assertEquals(GeoPoint(48.894209, -102.568024), pavement.location)
        assertEquals("Reduced speeds", pavement.lanesStatus)
        assertNull(pavement.severity)
        assertEquals(1777286230440L, pavement.updatedAt)
        assertEquals(TrafficSource.ND_DOT, pavement.source)
        assertEquals("Expect Delays", alerts[1].severity)
        assertEquals("ND 14", alerts[1].roadway)
        val closed = alerts[2]
        assertEquals(IncidentKind.CLOSURE, closed.kind)
        assertTrue(closed.isClosure)
        assertEquals("I-29", closed.roadway)
        assertEquals("Southbound", closed.direction)
    }

    @Test
    fun `alerts past their end are left out`() {
        // The sample was fetched on 8 October 2026: the first two had ended in June and July, the third ran to 14:45.
        val fetched = NorthDakota.parseAlerts(res("alerts.json"), now = 1_791_417_600_000L)
        assertEquals(listOf("nd:alert-40563"), fetched.map { it.id })
        assertEquals(emptyList<Incident>(), NorthDakota.parseAlerts(res("alerts.json"), now = 1_791_800_000_000L))
    }

    @Test
    fun `work zones are road work unless the road is closed, inactive ones dropped`() {
        val points = NorthDakota.parseWorkZones(res("workzones-points.json"))
        assertEquals(listOf("nd:MA-100601"), points.map { it.id })
        val diversion = points[0]
        assertEquals(IncidentKind.ROADWORK, diversion.kind)
        assertEquals("I-94", diversion.roadway)
        assertEquals("Eastbound", diversion.direction)
        assertEquals("Reduced to one lane", diversion.lanesStatus)
        assertEquals("Construction: I-94 from RP 341 to RP 343: I-94 construction for FM Diversion work at RP 341 to RP 343.", diversion.description)
        assertEquals(GeoPoint(46.877477, -96.98385), diversion.location)
        assertNull(diversion.severity)
        assertEquals(1791374501000L, diversion.updatedAt)
        val lines = NorthDakota.parseWorkZones(res("workzones-lines.json"))
        assertEquals(listOf("nd:CN-22484", "nd:CN-22616"), lines.map { it.id })
        assertEquals("US 52", lines[0].roadway)
        assertEquals("Expect Delays", lines[0].severity)
        assertEquals(GeoPoint(48.894293, -102.304534), lines[0].location)
        assertEquals(IncidentKind.CLOSURE, lines[1].kind)
        assertEquals("All lanes closed", lines[1].lanesStatus)
        assertEquals("ND 1", lines[1].roadway)
    }

    @Test
    fun `cameras keep their first still on the DOT's server`() {
        val cameras = NorthDakota.parseCameras(res("cameras.json"))
        assertEquals(listOf("nd:1", "nd:6", "nd:9"), cameras.map { it.id })
        val ray = cameras[0]
        assertEquals("Ray - West (US 2 MP 51.3)", ray.name)
        assertEquals("https://www.dot.nd.gov/travel-info/cameras/US2RP51Raywest.jpg", ray.imageUrl)
        assertEquals("US 2", ray.roadway)
        assertEquals(48.341772933829745, ray.lat, 0.0)
        assertEquals(-103.2168782961616, ray.lon, 0.0)
        assertFalse(ray.disabled)
        assertEquals(TrafficSource.ND_DOT, ray.source)
        // A plain http first link is skipped for the https one beside it.
        assertEquals("https://www.dot.nd.gov/travel-info/cameras/capitalnw00001.jpg", cameras[1].imageUrl)
        assertNull(cameras[1].roadway)
        assertNull(cameras[2].imageUrl)
        assertTrue(cameras[2].disabled)
    }

    @Test
    fun `kinds, roads and directions`() {
        assertEquals(IncidentKind.CLOSURE, NorthDakota.kindOf("Road Closed", null))
        assertEquals(IncidentKind.CLOSURE, NorthDakota.kindOf("Warning", "Road closed due to flooding"))
        assertEquals(IncidentKind.ACCIDENT, NorthDakota.kindOf("Crash", "two vehicles"))
        assertEquals(IncidentKind.ROADWORK, NorthDakota.kindOf("Warning", "Utility work in the work zone"))
        assertEquals(IncidentKind.OTHER, NorthDakota.kindOf("Warning", "mowing operation watch for flashing lights"))
        assertEquals("I-94", NorthDakota.roadIn("I 94"))
        assertEquals("ND 1804", NorthDakota.roadIn("ND 1804"))
        assertEquals("US 2", NorthDakota.roadIn("Ray - West (US 2 MP 51.3)"))
        assertNull(NorthDakota.roadIn("Capital - West"))
        assertEquals("Northbound", NorthDakota.direction("N"))
        assertEquals("Both Directions", NorthDakota.direction("B"))
        assertNull(NorthDakota.direction(""))
    }

    @Test
    fun `the client reads the four layers under the toggle and has no signs`() {
        val asked = ArrayList<String>()
        val client = NorthDakota.Client { url ->
            asked += url
            when (url) {
                NorthDakota.ALERTS_URL -> res("alerts.json")
                NorthDakota.WORK_ZONE_POINTS_URL -> res("workzones-points.json")
                NorthDakota.WORK_ZONE_LINES_URL -> res("workzones-lines.json")
                NorthDakota.CAMERAS_URL -> res("cameras.json")
                else -> error(url)
            }.encodeToByteArray()
        }
        assertEquals(TrafficSource.ND_DOT, client.source)
        assertEquals(6, client.events(0L).size)
        assertEquals(3, client.cameras().size)
        assertEquals(emptyList<MessageSign>(), client.signs())
        assertEquals(4, asked.size)
        assertTrue(asked.all { it.startsWith("https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer/") && "where=1%3D1" in it })
    }

    @Test
    fun `an error or something else is refused`() {
        for (bad in listOf("<html>", """{"error":{"code":400,"message":"Invalid"}}""", "[]")) {
            try {
                NorthDakota.parseAlerts(bad)
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
