package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime

/**
 * Ohio's OHGO ([Ohgo]). The API answers nothing without a key, so src/test/resources/ohgo holds documents written to
 * its own OpenAPI definitions (feed probe, 2026-10-11): every required field, the wrapper and its 401.
 */
class OhgoTest {
    private fun res(name: String) = File("src/test/resources/ohgo/$name").readText()
    private val updated = OffsetDateTime.parse("2026-10-11T10:40:12-04:00").toInstant().toEpochMilli()

    @Test
    fun `incidents by category and road status`() {
        val incidents = Ohgo.parseIncidents(res("incidents.json"))
        assertEquals(listOf("oh:INC-1", "oh:INC-2", "oh:INC-3"), incidents.map { it.id })
        val crash = incidents[0]
        assertEquals(IncidentKind.ACCIDENT, crash.kind)
        assertEquals("I-71", crash.roadway)
        assertEquals("Northbound", crash.direction)
        assertEquals("Crash on I-71 northbound, right lane blocked at I-71 at SR-315", crash.description)
        assertEquals("Partially closed", crash.lanesStatus)
        assertEquals(GeoPoint(39.9612, -82.9988), crash.location)
        assertEquals(updated, crash.updatedAt)
        assertEquals(TrafficSource.OH_OHGO, crash.source)
        val flood = incidents[1]
        assertEquals(IncidentKind.CLOSURE, flood.kind)
        assertEquals("All lanes closed", flood.lanesStatus)
        assertEquals("Both Directions", flood.direction)
        assertEquals(IncidentKind.OTHER, incidents[2].kind)
        assertNull(incidents[2].lanesStatus)
        assertEquals("US 50", incidents[2].roadway)
    }

    @Test
    fun `construction is road work, closed is a closure, unplanned is its severity`() {
        val work = Ohgo.parseConstruction(res("construction.json"))
        assertEquals(2, work.size)
        assertEquals(IncidentKind.ROADWORK, work[0].kind)
        assertEquals("I-270", work[0].roadway)
        assertEquals("Southbound", work[0].direction)
        assertEquals("Restricted", work[0].lanesStatus)
        assertNull(work[0].severity)
        assertEquals(IncidentKind.CLOSURE, work[1].kind)
        assertEquals("OH 8", work[1].roadway)
        assertEquals("Unplanned", work[1].severity)
        assertEquals("All lanes closed", work[1].lanesStatus)
    }

    @Test
    fun `cameras keep the first view's larger snapshot on ODOT's hosts`() {
        val cameras = Ohgo.parseCameras(res("cameras.json"))
        assertEquals(3, cameras.size)
        val hudson = cameras[0]
        assertEquals("oh:CAM-1", hudson.id)
        assertEquals("I-71 at Hudson St", hudson.name)
        assertEquals("https://itscameras.dot.state.oh.us/images/columbus/cam1.jpg", hudson.imageUrl)
        assertEquals("I-71", hudson.roadway)
        assertEquals("Northbound", hudson.direction)
        assertFalse(hudson.disabled)
        val ptz = cameras[1]
        assertEquals("https://cdn.ohgo.com/cameras/cle/cam2.jpg", ptz.imageUrl)
        assertNull(ptz.direction) // two views
        assertNull(cameras[2].imageUrl) // http, and elsewhere
        assertTrue(cameras[2].disabled)
    }

    @Test
    fun `signs show their messages as pages`() {
        val signs = Ohgo.parseSigns(res("signs.json"))
        assertEquals(2, signs.size)
        assertEquals(listOf("I-270 WEST", "8 MIN", "", "I-270 EAST", "10 MIN"), signs[0].lines)
        assertEquals("I-71", signs[0].roadway)
        assertEquals("Northbound", signs[0].direction)
        assertEquals(updated, signs[0].updatedAt)
        assertTrue(signs[1].isBlank)
        assertEquals("Eastbound", signs[1].direction)
    }

    @Test
    fun `the key goes in the query and only with a key is anything asked`() {
        assertEquals("https://publicapi.ohgo.com/api/v1/cameras?page-all=true&api-key=abc%2B1", Ohgo.url(Ohgo.CAMERAS_PATH, " abc+1 "))
        val asked = ArrayList<String>()
        val client = Ohgo.Client({ url ->
            asked += url
            when {
                url.startsWith(Ohgo.INCIDENTS_PATH) -> res("incidents.json")
                url.startsWith(Ohgo.CONSTRUCTION_PATH) -> res("construction.json")
                url.startsWith(Ohgo.CAMERAS_PATH) -> res("cameras.json")
                else -> res("signs.json")
            }.encodeToByteArray()
        }) { "secret-key" }
        assertEquals(TrafficSource.OH_OHGO, client.source)
        assertEquals(5, client.events(0L).size)
        assertEquals(3, client.cameras().size)
        assertEquals(2, client.signs().size)
        assertEquals(4, asked.size)
        assertTrue(asked.all { it.startsWith("https://publicapi.ohgo.com/api/v1/") && it.endsWith("?page-all=true&api-key=secret-key") })
        val noKey = Ohgo.Client({ throw AssertionError("asked without a key") }) { null }
        try {
            noKey.events(0L)
            throw AssertionError("fetched without a key")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("OHGO key"))
        }
    }

    @Test
    fun `a refusal or something else is refused`() {
        for (bad in listOf("<html>", res("no-key.json"), "[]", "{\"features\":[]}")) {
            try {
                Ohgo.parseIncidents(bad)
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals("OH 315", Ohgo.roadIn("SR-315"))
        assertEquals("US 23", Ohgo.roadIn("US-23"))
        assertEquals("Main St", Ohgo.roadIn("Main St"))
        assertEquals("Westbound", Ohgo.direction("West"))
        assertEquals("Both Directions", Ohgo.direction("Both Directions"))
        assertNull(Ohgo.direction("PTZ"))
    }
}
