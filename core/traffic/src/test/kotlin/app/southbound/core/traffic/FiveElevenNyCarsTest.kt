package app.southbound.core.traffic

import app.southbound.core.traffic.FiveElevenNyCars.EventLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The fixtures are real features from 511NY's collections, fetched 2026-10-07 by the feed probe
 * workflow (scripts/feed-probe.py), three per layer, with the map-drawing "representation" and icon
 * blocks left out.
 */
class FiveElevenNyCarsTest {

    private fun read(name: String) = File("src/test/resources/511ny-cars-$name.json").readText()

    @Test
    fun `incidents - kind from the wording, road and direction from the title`() {
        val events = FiveElevenNyCars.parseEvents(read("incidents"), EventLayer.INCIDENTS)
        assertEquals(listOf("NITTEC-519541", "NITTEC-519540", "TRANSCOM-ORI267964109"), events.map { it.id })

        val disabled = events[0]
        assertEquals(IncidentKind.ACCIDENT, disabled.kind) // "Disabled Vehicle"
        assertEquals("I-190", disabled.roadway)
        assertEquals("Southbound", disabled.direction)
        assertEquals("I-190 Southbound", disabled.title)
        assertEquals(GeoPoint(42.89206953846927, -78.89145979830533), disabled.location)
        assertEquals("Routine", disabled.severity)
        assertEquals(1791383503686L, disabled.updatedAt)
        assertEquals(
            "I-190 (Niagara Thruway) southbound: Disabled vehicle. between Exit 8 and 951L (Buffalo). " +
                "Look out for a disabled vehicle. Until today at about 11:01AM EDT.",
            disabled.description,
        )
        assertFalse(disabled.isClosure)

        val reported = events[1]
        assertEquals(IncidentKind.OTHER, reported.kind) // "Traffic Incident Reported" says no more
        assertEquals("NY 33", reported.roadway)
        assertNull(reported.direction)

        assertEquals("Urgent", events[2].severity)
        assertEquals("Southbound", events[2].direction)
    }

    @Test
    fun `closures - every one a closure, lines start where the closure starts`() {
        val events = FiveElevenNyCars.parseEvents(read("closures"), EventLayer.CLOSURES)
        assertEquals(3, events.size)
        assertTrue(events.all { it.kind == IncidentKind.CLOSURE && it.isClosure })
        assertEquals("PERRY ST Eastbound", events[0].title)
        assertEquals("All lanes closed", events[0].lanesStatus) // "The road is closed"
        // A LineString: the first point.
        assertEquals(GeoPoint(44.29962228094024, -74.10295039787349), events[1].location)
        assertEquals("Both Directions", events[2].direction)
    }

    @Test
    fun `construction - roadwork unless it shuts the road`() {
        val events = FiveElevenNyCars.parseEvents(read("construction"), EventLayer.CONSTRUCTION)
        assertEquals(listOf(IncidentKind.ROADWORK, IncidentKind.ROADWORK, IncidentKind.ROADWORK), events.map { it.kind })
        assertEquals("Genesee Road/Sibley Road", events[2].roadway)
        assertEquals("Southbound", events[2].direction)

        val closed = """{"features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[-73.9,40.7]},
            "properties":{"id":"X-1","title":"NY 9A northbound: Road closed.","tooltip":"NY 9A northbound: Road closed.",
            "description":"<p> The road is closed because of roadwork.</p>","route":"NY 9A","active":true,"updated":1}}]}"""
        assertEquals(IncidentKind.CLOSURE, FiveElevenNyCars.parseEvents(closed, EventLayer.CONSTRUCTION).single().kind)
    }

    @Test
    fun `inactive events and features without a position are skipped`() {
        val json = """{"features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[-73.9,40.7]},"properties":{"id":"A","title":"x","active":false}},
            {"type":"Feature","geometry":null,"properties":{"id":"B","title":"x","active":true}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"id":"C","title":"x"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[-73.9,40.7]},"properties":{"id":"D","title":"Police activity","active":true}}
        ]}"""
        val events = FiveElevenNyCars.parseEvents(json, EventLayer.INCIDENTS)
        assertEquals(listOf("D"), events.map { it.id })
        assertEquals(IncidentKind.POLICE, events.single().kind)
    }

    @Test
    fun `the old API's HTML or an empty object is an error, an empty list is not`() {
        for (bad in listOf("<!doctype html>", """{"healthy":true}""")) {
            try {
                FiveElevenNyCars.parseEvents(bad, EventLayer.INCIDENTS)
                fail("expected an error for $bad")
            } catch (e: IllegalArgumentException) {
                // MiniJson.JsonException or "511NY sent no event list"
            }
        }
        assertTrue(FiveElevenNyCars.parseEvents("""{"features":[]}""", EventLayer.CLOSURES).isEmpty())
    }

    @Test
    fun `signs - pages become lines with a blank between them`() {
        val signs = FiveElevenNyCars.parseSigns(read("signs"))
        assertEquals(3, signs.size)
        val bqe = signs[0]
        assertEquals("nyRegion11Signs*505", bqe.id)
        assertEquals("EB at 49th St (via/RFK) (CCTV-BQE-41 / P-3)", bqe.name)
        assertEquals(listOf("IF CRASH", "NO INJURIES", "MINOR DAMAGE", "", "MOVE VEHICLES", "OFF TRAVEL LANES", "TO EXCHANGE INFO"), bqe.lines)
        assertEquals("I-278", bqe.roadway)
        assertEquals("Eastbound", bqe.direction)
        assertEquals(GeoPoint(40.76557, -73.90341), bqe.location)
        assertEquals(1791359822000L, bqe.updatedAt)
        assertEquals(listOf("MOVING ROADWORK", "TO EXIT 39", "IN THE RIGHT LN"), signs[2].lines)
        assertEquals("Westbound", signs[2].direction)
    }

    @Test
    fun `cameras - the still is the view's preview image`() {
        val cameras = FiveElevenNyCars.parseCameras(read("cameras"))
        assertEquals(listOf("32768", "32769", "32770"), cameras.map { it.id })
        val first = cameras[0]
        assertEquals("SSP at NY135 (Crown Castle) - 1", first.name)
        assertEquals("https://public.carsprogram.org/cameras/NYSDOT/R10_384.flv.png", first.imageUrl)
        assertNull(first.roadway) // "Unknown"
        assertTrue(first.hasImage)
        assertEquals("NY 135", cameras[1].roadway)

        val broken = """{"features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[-73.9,40.7]},
            "properties":{"id":7,"name":"Cam","public":true,"views":[{"url":"https://x/playlist.m3u8",
            "videoPreviewUrl":"https://x/7.png","broken":true}]}}]}"""
        val off = FiveElevenNyCars.parseCameras(broken).single()
        assertTrue(off.disabled)
        assertFalse(off.hasImage)
        val private = broken.replace("\"public\":true", "\"public\":false")
        assertTrue(FiveElevenNyCars.parseCameras(private).isEmpty())
    }

    @Test
    fun `hash documents`() {
        assertEquals("abc", FiveElevenNyCars.parseHash("""{"hash":"abc"}"""))
        assertNull(FiveElevenNyCars.parseHash("""{"healthy":true}"""))
        assertNull(FiveElevenNyCars.parseHash("<html>"))
    }
}
