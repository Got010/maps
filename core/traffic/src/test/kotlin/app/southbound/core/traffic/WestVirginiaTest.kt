package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * West Virginia's WV511 ([WestVirginia]). src/test/resources/wv is real, printed by the feed probe on 2026-10-14:
 * the five incidents that night (two roads shut by a washout and a slide), five roadwork placemarks, five signs
 * showing nothing ("No Message"; none showed a message that night) and the start of the camera script, cut after its
 * sixth camera and closed. The sign with a message is written here in the same markup.
 */
class WestVirginiaTest {
    private fun res(name: String) = File("src/test/resources/wv/$name").readText()

    @Test
    fun `incidents, the road-closure icon or a shut road is a closure`() {
        val incidents = WestVirginia.parseIncidents(res("incidents.kml"))
        assertEquals(5, incidents.size)
        val washout = incidents[0]
        assertEquals("wv:4714cab243b9b0b37a0e695ce8117937", washout.id)
        assertEquals(IncidentKind.CLOSURE, washout.kind)
        assertEquals("CR-22/8", washout.roadway)
        assertEquals("Both Directions", washout.direction)
        assertEquals("All lanes closed", washout.lanesStatus)
        assertEquals(
            "Road Washed Out on CR-22/8 (Middle Ridge Rd) in Corley. The northbound road is closed. The southbound road is closed.",
            washout.description,
        )
        assertEquals(GeoPoint(38.72158, -80.57719), washout.location)
        assertEquals(ZonedDateTime.of(2026, 10, 11, 15, 14, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli(), washout.updatedAt)
        assertEquals(TrafficSource.WV_511, washout.source)
        assertEquals(IncidentKind.CLOSURE, incidents[1].kind)
        val open = incidents[2]
        assertEquals(IncidentKind.OTHER, open.kind)
        assertEquals("Road Washed Out on CR-26/1 (1525 Dog Run Road).", open.description)
        assertNull(open.direction)
        val crash = incidents[4]
        assertEquals(IncidentKind.ACCIDENT, crash.kind)
        assertEquals("US-60", crash.roadway)
        assertEquals("Eastbound", crash.direction)
    }

    @Test
    fun `roadwork is road work, its road and direction from the route`() {
        val work = WestVirginia.parseRoadwork(res("roadwork.kml"))
        assertEquals(5, work.size)
        val first = work[0]
        assertEquals(IncidentKind.ROADWORK, first.kind)
        assertEquals("US-119", first.roadway)
        assertEquals("Southbound", first.direction)
        assertEquals("Lane closures", first.lanesStatus)
        assertEquals(
            "New Roadway Construction on US-119S from Mile Marker 14.75 to Mile Marker 15.75. " +
                "Occurring 11:59 AM to 12:00 PM from 01/10/2025 until 01/01/2027. Lane closures.",
            first.description,
        )
        assertNull(first.updatedAt)
        assertEquals("I-77", work[2].roadway)
        assertEquals("Northbound", work[2].direction)
        assertNull(work[2].lanesStatus)
        assertEquals("Westbound", work[3].direction)
        assertFalse(work.any { it.isClosure })
    }

    @Test
    fun `kinds and closed directions`() {
        assertEquals(IncidentKind.CLOSURE, WestVirginia.kindOf("Rock Slide", "icon_feed_s1", "Rock Slide on CR-34.", roadwork = false))
        assertEquals(IncidentKind.CLOSURE, WestVirginia.kindOf("Bridge Repairs", "icon_feed", "Bridge Repairs on WV-2. Road closed. Detour posted.", roadwork = true))
        assertEquals(IncidentKind.ROADWORK, WestVirginia.kindOf("Paving", "icon_feed", "Paving on I-79N. Lane closures.", roadwork = true))
        assertEquals(IncidentKind.ACCIDENT, WestVirginia.kindOf("Vehicle Crash", "icon_feed_s3", "Vehicle Crash on US-60E.", roadwork = false))
        assertEquals(IncidentKind.OTHER, WestVirginia.kindOf("Tree in Roadway", "icon_feed_s3", "Tree in Roadway on CR-58/2.", roadwork = false))
        assertEquals(setOf("Northbound"), WestVirginia.closedDirections("Crash on I-79. The northbound road is closed."))
        assertEquals("CR-22/8", WestVirginia.routeIn("Road Washed Out on CR-22/8 (Middle Ridge Rd) in Corley."))
        assertNull(WestVirginia.routeIn("Tree down near the school."))
        assertEquals("I-64", WestVirginia.roadOf("I-64W"))
        assertEquals("CR-22/8", WestVirginia.roadOf("CR-22/8"))
    }

    @Test
    fun `signs, no message is blank, a message is its pages and lines`() {
        val blank = WestVirginia.parseSigns(res("signs-blank.kml"))
        assertEquals(5, blank.size)
        assertTrue(blank.all { it.isBlank })
        assertEquals("wv:OpenTMS-Sign18381420", blank[0].id)
        assertEquals("I-81 SB @ MM 9", blank[0].name)
        assertEquals("I-81", blank[0].roadway)
        assertEquals("Southbound", blank[0].direction)
        val showing = WestVirginia.parseSigns(
            """<?xml version="1.0" encoding="utf-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document>
            <Placemark id="OpenTMS-Sign19105576"> <name><![CDATA[[OHI]I-70 WB @ MM 7]]></name>
            <description><![CDATA[<p class="dms-message-text">CRASH AHEAD<br/>RIGHT LANE CLOSED</p><p class="dms-message-text">EXPECT<br />DELAYS &amp; USE CAUTION</p>]]></description>
            <styleUrl>#icon_dms_1</styleUrl> <Point> <coordinates>-80.63,40.07,0</coordinates> </Point> </Placemark></Document></kml>""",
        ).single()
        assertEquals(listOf("CRASH AHEAD", "RIGHT LANE CLOSED", "", "EXPECT", "DELAYS & USE CAUTION"), showing.lines)
        assertEquals("I-70 WB @ MM 7", showing.name)
        assertEquals("Westbound", showing.direction)
        assertEquals(40.07, showing.lat, 1e-9)
        assertEquals(TrafficSource.WV_511, showing.source)
    }

    @Test
    fun `cameras point at their player page for the stream`() {
        val cameras = WestVirginia.parseCameras(res("cameras.js"))
        assertEquals(6, cameras.size)
        val first = cameras[0]
        assertEquals("wv:CAM117", first.id)
        assertEquals("I-81 @ 0.5", first.name)
        assertEquals("I-81", first.roadway)
        assertNull(first.imageUrl)
        assertNull(first.videoUrl)
        assertEquals("https://www.wv511.org/flowplayeri.aspx?CAMID=CAM117", first.videoVia)
        assertEquals(39.302863, first.lat, 1e-9)
        assertEquals(-78.078892, first.lon, 1e-9)
        assertFalse(first.disabled)
        assertTrue(first.hasImage)
        assertEquals("I-81 @ 13 King Street", cameras[1].name)
    }

    @Test
    fun `a camera without a stream, or with an odd id, is not followed`() {
        val script = """var camera_data = { "count": 2, "cams": [
            {"md5":"CAM900","title":"I-64","description":"<div id=\"camDescription\">[KAN]I-64 @ 58</div>","start_lat":"38.35","start_lng":"-81.63"},
            {"md5":"../x?y","title":"I-79","description":"<!--STREAMING:1-->","start_lat":"39.0","start_lng":"-80.0"}
        ]};
        var icon_cams = {};"""
        val cameras = WestVirginia.parseCameras(script)
        assertEquals(listOf("wv:CAM900"), cameras.map { it.id })
        assertEquals("I-64 @ 58", cameras[0].name)
        assertTrue(cameras[0].disabled)
        assertNull(cameras[0].videoVia)
        assertFalse(cameras[0].hasImage)
    }

    @Test
    fun `the player page's stream, on WVDOH's video servers only`() {
        // As /flowplayeri.aspx?CAMID=CAM049 wrote it on 2026-10-14 (the feed probe's ninth and tenth rounds).
        val page = """<script type="text/javascript" src="/js/hls/1.7.3/hls.min.js?fingerprint=20260911164838"></script>
            retryTimer = setTimeout(() => {
                hls.loadSource('https://vtc2.roadsummary.com/rtplive/CAM049/playlist.m3u8'); // reload URL to avoid cached bad responses
            ${'$'}video.src = 'https://vtc2.roadsummary.com/rtplive/CAM049/playlist.m3u8';"""
        assertEquals("https://vtc2.roadsummary.com/rtplive/CAM049/playlist.m3u8", WestVirginia.streamOf(page))
        assertNull(WestVirginia.streamOf("hls.loadSource('https://cdn.example.com/rtplive/CAM049/playlist.m3u8');"))
        assertNull(WestVirginia.streamOf("hls.loadSource('https://vtc1.roadsummary.com/rtplive/CAM049/playlist.m3u8?viewer=1');"))
        assertNull(WestVirginia.streamOf("<html>Camera unavailable</html>"))
    }

    @Test
    fun `the client reads incidents, roadwork, signs and cameras`() {
        val asked = mutableListOf<String>()
        val client = WestVirginia.Client { url ->
            asked += url
            when (url) {
                WestVirginia.INCIDENTS_URL -> res("incidents.kml")
                WestVirginia.ROADWORK_URL -> res("roadwork.kml")
                WestVirginia.SIGNS_URL -> res("signs-blank.kml")
                WestVirginia.CAMERAS_URL -> res("cameras.js")
                else -> error(url)
            }.encodeToByteArray()
        }
        assertEquals(TrafficSource.WV_511, client.source)
        assertEquals(10, client.events(0).size)
        assertEquals(5, client.signs().size)
        assertEquals(6, client.cameras().size)
        assertEquals(listOf(WestVirginia.INCIDENTS_URL, WestVirginia.ROADWORK_URL, WestVirginia.SIGNS_URL, WestVirginia.CAMERAS_URL), asked)
        assertTrue(asked.all { it.startsWith("https://www.wv511.org/wsvc/gmap.asmx/") })
    }

    @Test
    fun `something else is refused`() {
        assertThrows(IllegalArgumentException::class.java) { WestVirginia.parseIncidents("<!DOCTYPE html><html>Runtime Error</html>") }
        assertThrows(IllegalArgumentException::class.java) { WestVirginia.parseCameras("var styles = [];") }
        assertThrows(IllegalArgumentException::class.java) { WestVirginia.parseCameras("""var camera_data = { "count": 1, "cams": [""") }
        assertTrue(WestVirginia.parseSigns("""<kml><Document></Document></kml>""").isEmpty())
    }
}
