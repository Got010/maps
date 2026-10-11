package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * South Dakota's SD511 ([SouthDakota]). The features in src/test/resources/sd are real, printed by the feed probe on
 * 2026-10-11 (two road work, one restriction, the enforced road closure, a camera site, a weather station), plus a
 * camera site whose still is elsewhere.
 */
class SouthDakotaTest {
    private fun res(name: String) = File("src/test/resources/sd/$name").readText()

    @Test
    fun `road work is read from its string coordinates, a closed road as a closure`() {
        val work = SouthDakota.parseEvents(res("road-work.json"))
        assertEquals(listOf("sd:12906802", "sd:12906846"), work.map { it.id })
        val bridge = work[0]
        assertEquals(IncidentKind.ROADWORK, bridge.kind)
        assertEquals("US 281", bridge.roadway)
        assertEquals("Both Directions", bridge.direction)
        assertEquals(GeoPoint(43.8211360187481, -98.4448677913239), bridge.location)
        assertEquals("Reduced to one lane", bridge.lanesStatus)
        assertEquals(1783350960_000L, bridge.updatedAt)
        assertTrue(bridge.description.startsWith("Road work: bridge repair: US-281 from 6.50 miles north"))
        assertTrue(bridge.description.contains("width limit 12 ft until further notice.; Bridge deck overlay"))
        assertFalse(bridge.description.contains("<br>"))
        assertEquals(TrafficSource.SD_511, bridge.source)
        val grading = work[1]
        assertEquals(IncidentKind.CLOSURE, grading.kind)
        assertEquals("SD 25", grading.roadway)
        assertEquals("All lanes closed", grading.lanesStatus)
        assertTrue(grading.isClosure)
    }

    @Test
    fun `restrictions are road work, the enforced closure a closure, nothing from an empty file`() {
        val restriction = SouthDakota.parseEvents(res("restrictions.json")).single()
        assertEquals(IncidentKind.ROADWORK, restriction.kind)
        assertEquals("SD 53", restriction.roadway)
        assertEquals("Reduced to one lane", restriction.lanesStatus)
        val closure = SouthDakota.parseEvents(res("obstructions.json")).single()
        assertEquals("sd:12907924", closure.id)
        assertEquals(IncidentKind.CLOSURE, closure.kind)
        assertEquals("SD 11", closure.roadway)
        assertEquals(GeoPoint(43.68327835, -96.57126065), closure.location)
        assertEquals(emptyList<Incident>(), SouthDakota.parseEvents(res("empty.json")))
    }

    @Test
    fun `camera sites and weather stations keep their first still on the CDN`() {
        val cameras = SouthDakota.parseCameras(res("cameras.json"))
        assertEquals(listOf("sd:CSDATY", "sd:CSDXXX"), cameras.map { it.id })
        val watertown = cameras[0]
        assertEquals("Watertown North", watertown.name)
        assertEquals("https://sd.cdn.iteris-atis.com/camera_images/CSDATY/1/latest.jpg", watertown.imageUrl)
        assertEquals("I-29", watertown.roadway)
        assertEquals(44.95, watertown.lat, 0.0)
        assertEquals(-97.06, watertown.lon, 0.0)
        assertFalse(watertown.disabled)
        assertNull(cameras[1].imageUrl)
        assertTrue(cameras[1].disabled)
        val agar = SouthDakota.parseCameras(res("rwis.json")).single()
        assertEquals("sd:CSDAGR", agar.id)
        assertEquals("Agar", agar.name)
        assertEquals("US 83", agar.roadway)
        assertEquals("https://sd.cdn.iteris-atis.com/camera_images/CSDAGR/1/latest.jpg", agar.imageUrl)
    }

    @Test
    fun `kinds, lanes, roads and reports`() {
        assertEquals(IncidentKind.CLOSURE, SouthDakota.kindOf("Incident/event: ENFORCED ROAD CLOSURE", "..."))
        assertEquals(IncidentKind.ROADWORK, SouthDakota.kindOf("Road work: grading", "paving"))
        assertEquals(IncidentKind.ROADWORK, SouthDakota.kindOf("Restrictions: bridge repair", "width limit 12 ft"))
        assertEquals(IncidentKind.ACCIDENT, SouthDakota.kindOf("Incident/event: crash", "two vehicles"))
        assertEquals(IncidentKind.OTHER, SouthDakota.kindOf("Incident/event: debris", "debris on the road"))
        assertEquals(IncidentKind.EVENT, SouthDakota.kindOf("Scheduled event: parade", "Main St"))
        assertEquals("Reduced to one lane", SouthDakota.lanesIn("lanes reduced to 1, no passing", IncidentKind.ROADWORK))
        assertNull(SouthDakota.lanesIn("width limit 12 ft", IncidentKind.ROADWORK))
        assertEquals("US 281", SouthDakota.roadIn("US-281"))
        assertEquals("I-29", SouthDakota.roadIn("I-29"))
        assertNull(SouthDakota.roadIn("Main Street"))
        assertEquals("one; two", SouthDakota.plain("one<br>two<br>"))
        assertEquals("Both Directions", SouthDakota.direction("Both Directions"))
        assertEquals("Northbound", SouthDakota.direction("NB"))
    }

    @Test
    fun `the client reads every event file once and both camera files, no signs`() {
        val asked = ArrayList<String>()
        val client = SouthDakota.Client { url ->
            asked += url
            when (url) {
                SouthDakota.ROAD_WORK_URL -> res("road-work.json")
                SouthDakota.RESTRICTIONS_URL -> res("restrictions.json")
                SouthDakota.OBSTRUCTIONS_URL -> res("obstructions.json")
                SouthDakota.CAMERAS_URL -> res("cameras.json")
                SouthDakota.WEATHER_CAMERAS_URL -> res("rwis.json")
                else -> res("empty.json")
            }.encodeToByteArray()
        }
        assertEquals(TrafficSource.SD_511, client.source)
        assertEquals(4, client.events(0L).size)
        assertEquals(3, client.cameras().size)
        assertEquals(emptyList<MessageSign>(), client.signs())
        assertEquals(7, asked.size)
        assertTrue(asked.all { it.startsWith("https://sd.cdn.iteris-atis.com/geojson/icons/metadata/") })
    }

    @Test
    fun `something other than a FeatureCollection is refused`() {
        for (bad in listOf("<html>", "{\"data\":[]}", "[]")) {
            try {
                SouthDakota.parseEvents(bad)
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
