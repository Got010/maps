package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 511 Virginia ([Virginia511]). src/test/resources/va is real, printed by the feed probe on 2026-10-13 and 14: the
 * first features of each event layer (incidents, major incidents with two at one point grouped under `items`, work
 * zones, weather closures, a parade the next evening, an empty security layer), six message signs and four cameras.
 */
class Virginia511Test {
    private fun res(name: String) = File("src/test/resources/va/$name").readText()

    /** 2026-10-14 01:56:40 UTC, as the probe ran. */
    private val now = 1_791_943_000_000L

    @Test
    fun `incidents are crashes and breakdowns, with VDOT's own words`() {
        val incidents = Virginia511.parseEvents("incidents", res("incidents.json"), now)
        assertEquals(6, incidents.size)
        val crash = incidents[0]
        assertEquals("va:INSW11743364548-10132026", crash.id)
        assertEquals(IncidentKind.ACCIDENT, crash.kind)
        assertEquals("Rt. 801", crash.roadway)
        assertEquals("Northbound", crash.direction)
        assertEquals("Minor", crash.severity)
        assertEquals("Lane closures", crash.lanesStatus)
        assertEquals(GeoPoint(37.232108, -79.611112), crash.location)
        assertTrue(crash.description.startsWith("On Rt. 801 North in Bedford County"))
        assertEquals(TrafficSource.VA_511, crash.source)
        val shoulder = incidents[4]
        assertEquals("I-95", shoulder.roadway)
        assertEquals("The north right shoulder is closed", shoulder.lanesStatus)
        assertFalse(incidents.any { it.isClosure })
    }

    @Test
    fun `major incidents, every lane both ways is a closure, and grouped ones are each read`() {
        val major = Virginia511.parseEvents("hpIncidents", res("hpIncidents.json"), now)
        assertEquals(6, major.size)
        val closed = major.first { it.id == "va:INCE11743474177-10132026" }
        assertEquals(IncidentKind.CLOSURE, closed.kind)
        assertEquals("US-17", closed.roadway)
        assertEquals("Both Directions", closed.direction)
        assertEquals("All lanes closed", closed.lanesStatus)
        assertEquals("Major", closed.severity)
        assertEquals(IncidentKind.OTHER, major.first { it.id == "va:INNW11743433929-10132026" }.kind)
        // Two crashes at one point come as one feature with `items`.
        val grouped = major.filter { it.location == GeoPoint(36.7864, -76.229248) }
        assertEquals(listOf("va:INEA11743487648-10132026", "va:INEA11743478398-10132026"), grouped.map { it.id })
        assertTrue(grouped.all { it.kind == IncidentKind.ACCIDENT && it.roadway == "I-64" && it.direction == "Westbound" })
        assertEquals("The west inside right lane and right shoulder are closed", grouped[1].lanesStatus)
    }

    @Test
    fun `work zones are road work unless through lanes both ways are shut`() {
        val zones = Virginia511.parseEvents("acon", res("acon.json"), now)
        assertEquals(5, zones.size)
        val bridge = zones[0]
        assertEquals(IncidentKind.CLOSURE, bridge.kind)
        assertEquals("Rt. 669", bridge.roadway)
        assertEquals("Both Directions", bridge.direction)
        assertEquals(1_782_747_652_000L, bridge.updatedAt)
        val lane = zones[1]
        assertEquals(IncidentKind.ROADWORK, lane.kind)
        assertEquals("ALT US-58", lane.roadway)
        assertEquals("Eastbound", lane.direction)
        assertEquals("The east left shoulder and left lane are closed", lane.lanesStatus)
        assertNull(lane.severity)
        // Before the first zone's start or after its end, it isn't there.
        assertFalse(Virginia511.parseEvents("acon", res("acon.json"), 1_763_380_000_000L).any { it.id == bridge.id })
        assertFalse(Virginia511.parseEvents("acon", res("acon.json"), 1_796_904_000_000L).any { it.id == bridge.id })
    }

    @Test
    fun `weather closures shut the road, which way unknown unless the words say`() {
        val weather = Virginia511.parseEvents("weatherClosures", res("weatherClosures.json"), now)
        assertEquals(5, weather.size)
        assertTrue(weather.all { it.kind == IncidentKind.CLOSURE && it.isClosure })
        // "Rt. 629N" is VDOT's inventory direction, not which way is shut.
        assertEquals("Rt. 629", weather[0].roadway)
        assertNull(weather[0].direction)
    }

    @Test
    fun `a parade tomorrow isn't shown tonight, and is a closure southbound when it comes`() {
        assertTrue(Virginia511.parseEvents("sEvents", res("sEvents.json"), now).isEmpty())
        val parade = Virginia511.parseEvents("sEvents", res("sEvents.json"), 1_792_016_000_000L).single()
        assertEquals(IncidentKind.CLOSURE, parade.kind)
        assertEquals("VA-42", parade.roadway)
        assertEquals("Southbound", parade.direction)
        assertTrue(Virginia511.parseEvents("sActivity", res("sActivity.json"), now).isEmpty())
    }

    @Test
    fun `kinds, closed directions, roads`() {
        assertEquals(setOf("Northbound", "Southbound"), Virginia511.closedDirections("All north lanes are closed. All south lanes are closed."))
        assertEquals(setOf("Southbound"), Virginia511.closedDirections("The north left lane is closed. All south lanes are closed."))
        assertEquals(setOf("Eastbound"), Virginia511.closedDirections("The east through lanes are closed."))
        assertTrue(Virginia511.closedDirections("The north right lane and right shoulder are closed.").isEmpty())
        assertEquals(IncidentKind.CLOSURE, Virginia511.kindOf("incidents", "Vehicle Accident", "All lanes are closed."))
        assertEquals(IncidentKind.ACCIDENT, Virginia511.kindOf("incidents", "Disabled Vehicle", "The south right shoulder is closed."))
        assertEquals(IncidentKind.ACCIDENT, Virginia511.kindOf("hpIncidents", "Vehicle Fire", ""))
        assertEquals(IncidentKind.ROADWORK, Virginia511.kindOf("acon", null, "The north left lane is closed."))
        assertEquals(IncidentKind.EVENT, Virginia511.kindOf("sEvents", null, "Delays due to a festival."))
        assertEquals(IncidentKind.POLICE, Virginia511.kindOf("sActivity", "Police Activity", ""))
        assertEquals(IncidentKind.OTHER, Virginia511.kindOf("otherWeather", "Fog", "Reduced visibility."))
        assertEquals(IncidentKind.OTHER, Virginia511.kindOf("weatherClosures", "Flooding", "Lane closures exist."))
        assertEquals("I-81", Virginia511.roadOf("I-81N"))
        assertEquals("Rt. 801", Virginia511.roadOf("Rt. 801N (Bedford County)"))
        assertEquals("RT 50", Virginia511.roadOf("RT 50"))
        assertEquals("US-1BUS", Virginia511.roadOf("US-1BUS"))
        assertEquals("Southbound", Virginia511.directionOfRoute("I-95S"))
        assertNull(Virginia511.directionOfRoute("US-1BUS"))
        assertNull(Virginia511.lanesIn("Motorists can expect delays due to congestion."))
    }

    @Test
    fun `signs are one text each, named by road and mile`() {
        val signs = Virginia511.parseSigns(res("dms.json"))
        assertEquals(6, signs.size)
        assertEquals("va:OpenTMS-Sign-402220", signs[0].id)
        assertEquals("I-81 Northbound", signs[0].name)
        assertEquals(listOf("ROAD WORK 7 MILES AHEAD SHOULDERS CLOSED"), signs[0].lines)
        assertEquals("I-81 Northbound, mile 8.6", signs[1].name)
        assertEquals("I-81", signs[1].roadway)
        assertEquals("Northbound", signs[1].direction)
        assertEquals(TrafficSource.VA_511, signs[1].source)
        val blank = Virginia511.parseSigns(
            """{"features":[{"id":"S1","geometry":{"type":"Point","coordinates":[-77.4,38.8]},"properties":{"text":"","route":"I-66E","dir":"east","mrm":52}}]}""",
        ).single()
        assertTrue(blank.isBlank)
        assertEquals("I-66 Eastbound, mile 52", blank.name)
    }

    @Test
    fun `cameras keep VDOT's still, and its stream only without one`() {
        val cameras = Virginia511.parseCameras(res("cameras.json"))
        assertEquals(4, cameras.size)
        val first = cameras[0]
        assertEquals("va:3958", first.id)
        assertEquals("University Drive and Sager Avenue", first.name)
        assertEquals("https://snapshot.vdotcameras.com/thumbs/0i6a7bfbs60yq2lbgivq0b8xk3scij3c.flv.png", first.imageUrl)
        assertNull(first.videoUrl)
        assertNull(first.roadway)
        assertEquals(38.84519, first.lat, 1e-9)
        assertEquals(-77.3055, first.lon, 1e-9)
        assertFalse(first.disabled)
        assertEquals("RT 50", cameras[1].roadway)
        assertEquals("VA-123", cameras[3].roadway)
        val others = Virginia511.parseCameras(
            """{"features":[
              {"id":"1","geometry":{"type":"Point","coordinates":[-77.0,38.0]},"properties":{"description":"No still","active":true,
               "image_url":"https://elsewhere.example/thumbs/a.png","https_url":"https://media-sfs3.vdotcameras.com/rtplive/a/playlist.m3u8"}},
              {"id":"2","geometry":{"type":"Point","coordinates":[-77.0,38.0]},"properties":{"description":"Off","active":false,
               "image_url":"https://snapshot.vdotcameras.com/thumbs/b.flv.png"}},
              {"id":"3","geometry":{"type":"Point","coordinates":[-77.0,38.0]},"properties":{"description":"Nothing",
               "https_url":"https://media.example/rtplive/c/playlist.m3u8"}}
            ]}""",
        )
        assertNull(others[0].imageUrl)
        assertEquals("https://media-sfs3.vdotcameras.com/rtplive/a/playlist.m3u8", others[0].videoUrl)
        assertTrue(others[0].hasImage)
        assertTrue(others[1].disabled)
        assertTrue(others[2].disabled)
        assertNull(others[2].videoUrl)
    }

    @Test
    fun `the client reads every event layer, the signs and the cameras`() {
        val asked = mutableListOf<String>()
        val files = mapOf(
            "incidents" to "incidents.json", "hpIncidents" to "hpIncidents.json", "acon" to "acon.json",
            "weatherClosures" to "weatherClosures.json", "otherWeather" to "sActivity.json", "sEvents" to "sEvents.json",
            "sActivity" to "sActivity.json",
        )
        val client = Virginia511.Client { url ->
            asked += url
            when (url) {
                Virginia511.SIGNS_URL -> res("dms.json")
                Virginia511.CAMERAS_URL -> res("cameras.json")
                else -> res(files.getValue(url.substringAfterLast('/')))
            }.encodeToByteArray()
        }
        assertEquals(TrafficSource.VA_511, client.source)
        assertEquals(6 + 6 + 5 + 5, client.events(now).size)
        assertEquals(6, client.signs().size)
        assertEquals(4, client.cameras().size)
        assertEquals(Virginia511.EVENT_LAYERS.map(Virginia511::layerUrl) + Virginia511.SIGNS_URL + Virginia511.CAMERAS_URL, asked)
        assertTrue(asked.all { it.startsWith("https://511.vdot.virginia.gov/services/511/map/layers/map/") && '?' !in it })
    }

    @Test
    fun `something else is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Virginia511.parseEvents("incidents", "<html>Service Unavailable</html>", now) }
        assertThrows(IllegalArgumentException::class.java) { Virginia511.parseSigns("""{"type":"FeatureCollection"}""") }
        assertTrue(Virginia511.parseCameras("""{"type":"FeatureCollection","features":[]}""").isEmpty())
    }
}
