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
 * Illinois' Travel Midwest ([TravelMidwest]). The first feature of each file in src/test/resources/travelmidwest is
 * real, printed by the feed probe on 2026-10-11; the others stand in with the same keys (a full closure, a camera
 * marked `dis`, Iowa's and Indiana's items the Gateway shares, a dark sign, a sign out of touch).
 */
class TravelMidwestTest {
    private fun res(name: String) = File("src/test/resources/travelmidwest/$name").readText()

    private fun central(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of("America/Chicago")).toInstant().toEpochMilli()

    @Test
    fun `incidents are read at their point, Illinois' own only`() {
        val incidents = TravelMidwest.parseIncidents(res("il-incidents.json"))
        // Iowa's stall is left to Iowa's 511; the debris has no point.
        assertEquals(listOf("il:IL-IDOTD4-INCIDENT.2026.9.20.9.17279543", "il:IL-IDOTD2-INCIDENT.2026.10.5.9.17300387"), incidents.map { it.id })
        val crash = incidents[0]
        assertEquals(IncidentKind.ACCIDENT, crash.kind)
        assertEquals("IL 40", crash.roadway)
        assertEquals("Southbound", crash.direction)
        assertEquals("Crash: SB IL-40 at Pioneer Pkwy, Peoria, Peoria", crash.description)
        assertEquals("Right lane closed, one lane open", crash.lanesStatus)
        assertEquals(GeoPoint(40.7898459, -89.6027686), crash.location)
        assertEquals(central(2026, 9, 20, 9, 53), crash.updatedAt)
        assertEquals(TrafficSource.IL_TRAVEL_MIDWEST, crash.source)
        assertFalse(crash.isClosure)
        val shut = incidents[1]
        assertEquals(IncidentKind.CLOSURE, shut.kind)
        assertTrue(shut.isClosure)
        assertEquals("All lanes closed", shut.lanesStatus)
        assertEquals("US 20", shut.roadway)
        assertEquals("Eastbound", shut.direction)
        assertNull(shut.severity)
    }

    @Test
    fun `road work is read from the work zone's point beside its polylines`() {
        val work = TravelMidwest.parseConstruction(res("il-construction.json"))
        assertEquals(listOf("il:IL-IDOTD1-ROADWORK.2026.3.2.8.17005512", "il:IL-ISTHA-ROADWORK.2026.10.9.21.17309901"), work.map { it.id })
        val bridge = work[0]
        assertEquals(IncidentKind.ROADWORK, bridge.kind)
        assertEquals(GeoPoint(41.8614, -87.6412), bridge.location)
        assertEquals("I-90", bridge.roadway)
        assertEquals("Northbound", bridge.direction)
        assertEquals("High", bridge.severity)
        assertEquals("2 left lanes closed", bridge.lanesStatus)
        assertEquals("Bridge repair, two left lanes closed: NB I-90/I-94 (Dan Ryan Expy) from 31st St to I-55", bridge.description)
        assertEquals(central(2026, 10, 10, 6, 15), bridge.updatedAt)
        // `lanes` full shuts the ramp whatever the wording; no last update, so the span's start.
        val ramp = work[1]
        assertEquals(IncidentKind.CLOSURE, ramp.kind)
        assertEquals(central(2026, 10, 9, 21, 0), ramp.updatedAt)
        assertEquals("All lanes closed", ramp.lanesStatus)
        assertEquals("I-88", ramp.roadway)
        assertEquals("Eastbound", ramp.direction)
    }

    @Test
    fun `kinds come from the lanes first, then the words`() {
        assertEquals(IncidentKind.CLOSURE, TravelMidwest.kindOf(false, "Crash", "All lanes closed", "full"))
        assertEquals(IncidentKind.CLOSURE, TravelMidwest.kindOf(true, "Paving", "Road closed", "partial"))
        assertEquals(IncidentKind.ROADWORK, TravelMidwest.kindOf(true, "both shoulders closed", "Various lanes closed", null))
        assertEquals(IncidentKind.ACCIDENT, TravelMidwest.kindOf(false, "Stalled vehicle", "None", "none"))
        assertEquals(IncidentKind.POLICE, TravelMidwest.kindOf(false, "Police activity", null, null))
        assertEquals(IncidentKind.ROADWORK, TravelMidwest.kindOf(false, "Emergency road work", "Left lane closed", "partial"))
        assertEquals(IncidentKind.OTHER, TravelMidwest.kindOf(false, "Debris", null, null))
    }

    @Test
    fun `cameras keep their first snapshot on the snapshot server`() {
        val cameras = TravelMidwest.parseCameras(res("il-cameras.json"))
        assertEquals(listOf("il:IL-ISTHA-bkWelUZ79reGAKdLMh+MlC6jVSo=", "il:IL-TESTTSC-CAM-0421", "il:IL-ISTHA-elsewhere"), cameras.map { it.id })
        val plaza = cameras[0]
        assertEquals("I-355 at Spring Creek Plaza", plaza.name)
        assertEquals("https://cctv.travelmidwest.com/snapshots/IL-ISTHA_1_Will_SEB_I-355-CASH_4157660_-8800050_1_S.jpg", plaza.imageUrl)
        assertEquals("I-355", plaza.roadway)
        // Two views, two directions: none is the camera's.
        assertNull(plaza.direction)
        assertFalse(plaza.disabled)
        assertTrue(plaza.hasImage)
        assertEquals(41.57659912109375, plaza.lat, 0.0)
        assertEquals(-88.00050354003906, plaza.lon, 0.0)
        assertEquals(TrafficSource.IL_TRAVEL_MIDWEST, plaza.source)
        val dark = cameras[1]
        assertTrue(dark.disabled)
        assertEquals("Eastbound", dark.direction)
        assertEquals("I-290", dark.roadway)
        // Only https snapshots on cctv.travelmidwest.com are followed: none here, so no image.
        val elsewhere = cameras[2]
        assertNull(elsewhere.imageUrl)
        assertTrue(elsewhere.disabled)
    }

    @Test
    fun `signs show their lines unless dark or out of touch`() {
        val signs = TravelMidwest.parseSigns(res("il-signs.json"))
        assertEquals(3, signs.size)
        val dark = signs[0]
        assertEquals("il:IL-TESTTSC-KE-IRIVINGPK", dark.id)
        assertEquals("WB I-90 at IL-19", dark.name)
        assertEquals(emptyList<String>(), dark.lines)
        assertTrue(dark.isBlank)
        assertEquals("I-90", dark.roadway)
        assertEquals("Northwestbound", dark.direction)
        assertEquals(central(2026, 10, 11, 8, 22), dark.updatedAt)
        assertEquals(emptyList<String>(), signs[1].lines)
        val travel = signs[2]
        assertEquals(listOf("TRAVEL TIME TO", "I-90", "12 MIN"), travel.lines)
        assertEquals("I-294", travel.roadway)
        assertEquals("Northbound", travel.direction)
        assertEquals(central(2026, 10, 11, 8, 20), travel.updatedAt)
    }

    @Test
    fun `roads and directions read as a driver says them`() {
        assertEquals("IL 40", TravelMidwest.roadIn("SB IL-40 at Pioneer Pkwy"))
        assertEquals("I-90", TravelMidwest.roadIn("NB I-90/I-94 (Dan Ryan Expy)"))
        assertEquals("US 20", TravelMidwest.roadIn("EB US-20 at Falconer Rd"))
        assertNull(TravelMidwest.roadIn("Lake Shore Dr at Fullerton"))
        assertEquals("Southbound", TravelMidwest.direction("SB"))
        assertEquals("Northwestbound", TravelMidwest.direction("NWB"))
        assertEquals("Westbound", TravelMidwest.direction("W"))
        assertEquals("Both Directions", TravelMidwest.direction("Both"))
        assertNull(TravelMidwest.direction(""))
        assertNull(TravelMidwest.direction("Unknown"))
    }

    @Test
    fun `times are Central or ISO`() {
        assertEquals(central(2026, 9, 20, 9, 53), TravelMidwest.whenMillis("9/20/26 9:53 AM"))
        assertEquals(central(2026, 10, 11, 8, 22) + 8_000, TravelMidwest.whenMillis("2026-10-11T08:22:08-05:00"))
        assertNull(TravelMidwest.whenMillis(""))
        assertNull(TravelMidwest.whenMillis("yesterday"))
    }

    @Test
    fun `the client posts the site's body to each file under its toggle`() {
        val posted = ArrayList<Pair<String, String>>()
        val client = TravelMidwest.Client { url, body ->
            posted += url to body.decodeToString()
            when (url) {
                TravelMidwest.INCIDENTS_URL -> res("il-incidents.json")
                TravelMidwest.CONSTRUCTION_URL -> res("il-construction.json")
                TravelMidwest.CAMERAS_URL -> res("il-cameras.json")
                TravelMidwest.SIGNS_URL -> res("il-signs.json")
                else -> error(url)
            }.encodeToByteArray()
        }
        assertEquals(TrafficSource.IL_TRAVEL_MIDWEST, client.source)
        assertEquals(4, client.events(0L).size)
        assertEquals(3, client.cameras().size)
        assertEquals(3, client.signs().size)
        assertEquals(listOf(TravelMidwest.INCIDENTS_URL, TravelMidwest.CONSTRUCTION_URL, TravelMidwest.CAMERAS_URL, TravelMidwest.SIGNS_URL), posted.map { it.first })
        assertTrue(posted.all { it.second == "{}" })
        assertTrue(posted.all { it.first.startsWith("https://travelmidwest.com/lmiga/") })
    }

    @Test
    fun `something other than the file is refused`() {
        for (bad in listOf("<html>", "{\"data\":[]}", "[]")) {
            try {
                TravelMidwest.parseIncidents(bad)
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
