package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The CARS states' lists ([CarsStates]). The rows in src/test/resources/cars are real, printed by the feed probe on
 * 2026-10-11 (a camera or two and a sign or two from each state); Indiana's hidden and still-less cameras, Iowa's
 * failed sign's stale message and the Kansas City Scout camera stand in with the same keys.
 */
class CarsStatesTest {
    private fun res(name: String) = File("src/test/resources/cars/$name").readText()

    @Test
    fun `Indiana's cameras take the video preview as the still, hidden ones go`() {
        val cameras = CarsStates.parseCameras(res("in-cameras.json"), CarsStates.IN)
        assertEquals(listOf("in:24595", "in:24596", "in:24598"), cameras.map { it.id })
        val bridge = cameras[0]
        assertEquals("1-265-028-7-_-_-cam-2 I-265/28.7 LEWIS AND CLARK BRIDGE", bridge.name)
        assertEquals("https://public.carsprogram.org/cameras/IN/INDOT_349_8c7BT6z1OURbqtb6.flv.png", bridge.imageUrl)
        assertEquals("I-265", bridge.roadway)
        assertEquals(38.34532071283214, bridge.lat, 0.0)
        assertEquals(-85.64627517182726, bridge.lon, 0.0)
        assertFalse(bridge.disabled)
        assertEquals(TrafficSource.IN_511, bridge.source)
        assertTrue(cameras[1].disabled) // active false
        assertEquals("I-74", cameras[1].roadway)
        assertNull(cameras[2].imageUrl) // a stream and no preview
        assertTrue(cameras[2].disabled)
        assertEquals("US 31", cameras[2].roadway)
    }

    @Test
    fun `the other states' stills are their own servers' JPEGs`() {
        val mn = CarsStates.parseCameras(res("mn-cameras.json"), CarsStates.MN)
        assertEquals("https://public.carsprogram.org/cameras/MN/C30559", mn[0].imageUrl)
        assertEquals("MN 36", mn[0].roadway)
        assertEquals("https://public.carsprogram.org/cameras/MN/C30614", mn[1].imageUrl)
        assertEquals("mn:512006", mn[1].id)
        val ia = CarsStates.parseCameras(res("ia-cameras.json"), CarsStates.IA).single()
        assertEquals("https://atmsqf.iowadot.gov/snapshots/public/RWIS/RWIS_33-00.jpg", ia.imageUrl)
        assertEquals("US 18", ia.roadway)
        assertEquals("ia:59634573", ia.id)
        val ne = CarsStates.parseCameras(res("ne-cameras.json"), CarsStates.NE).single()
        assertEquals("https://dot511.nebraska.gov/images/vid-001077037-04.jpg", ne.imageUrl)
        assertEquals("ne:512", ne.id)
        val ks = CarsStates.parseCameras(res("ks-cameras.json"), CarsStates.KS)
        assertEquals("https://kscam.carsprogram.org/KDOT_573004_IMAGE001.JPG", ks[0].imageUrl)
        assertEquals("KS 39", ks[0].roadway)
        assertEquals("https://kdot-sfs2.us-east-1.skyvdn.com/rtplive/KDOT_435_metcalf/snapshot.jpg", ks[1].imageUrl)
        assertEquals("I-435", ks[1].roadway)
        // Indiana's hosts don't serve Kansas: nothing kept.
        assertTrue(CarsStates.parseCameras(res("ks-cameras.json"), CarsStates.IN).all { it.imageUrl == null && it.disabled })
    }

    @Test
    fun `signs show their pages' lines, blank or failed ones nothing, picture links dropped`() {
        val sign = CarsStates.parseSigns(res("in-signs.json"), CarsStates.IN).single()
        assertEquals("in:irisxsigns*83", sign.id)
        assertEquals("I-265 EB Mile 32.7", sign.name)
        assertEquals(listOf("BRIDGE / TUNNEL", "WILL BE CLOSED", "SUNDAY NIGHT 7PM-6AM"), sign.lines)
        assertEquals("I-265", sign.roadway)
        assertNull(sign.direction) // POSITIVE_DIRECTION says nothing a driver would
        assertEquals(1791727322000L, sign.updatedAt)
        assertEquals(TrafficSource.IN_511, sign.source)
        val picture = CarsStates.parseSigns(res("mn-signs.json"), CarsStates.MN).single()
        assertEquals(emptyList<String>(), picture.lines)
        assertEquals("Westbound", picture.direction)
        assertEquals("MN 62", picture.roadway)
        val ia = CarsStates.parseSigns(res("ia-signs.json"), CarsStates.IA)
        assertEquals(2, ia.size)
        assertTrue(ia.all { it.isBlank })
        assertEquals("Northbound", ia[1].direction)
        val kansas = CarsStates.parseSigns(res("ks-signs.json"), CarsStates.KS).single()
        assertEquals(listOf("I-70 CLOSED", "IN 35 MI", "", "I-70 CLOSED", "THRU TRAFFIC", "USE I-470"), kansas.lines)
        assertEquals("Eastbound", kansas.direction)
        assertEquals("ks:kansassigns*179", kansas.id)
    }

    @Test
    fun `sites and roads`() {
        assertEquals(5, CarsStates.SITES.size)
        assertEquals(CarsStates.SITES.size, CarsStates.SITES.map { it.source }.toSet().size)
        assertEquals("https://intg.carsprogram.org/cameras_v1/api/cameras", CarsStates.IN.camerasUrl)
        assertEquals("https://kstg.carsprogram.org/signs_v1/api/signs", CarsStates.KS.signsUrl)
        assertEquals("https://511mn.org/api/graphql", CarsStates.MN.graphqlUrl)
        assertEquals("I-74", CarsStates.roadOf("IN I-74"))
        assertEquals("US 18", CarsStates.roadOf("US 18"))
        assertEquals("Cleveland Ave", CarsStates.roadOf("Cleveland Ave"))
        assertNull(CarsStates.roadOf(" "))
    }

    @Test
    fun `something other than the list is refused`() {
        for (bad in listOf("<html>", "{\"features\":[]}", "nope")) {
            try {
                CarsStates.parseCameras(bad, CarsStates.IN)
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}

class CarsStatesEventsTest {
    private fun res(name: String) = File("src/test/resources/cars/$name").readText()

    @Test
    fun `events are read from the map query, once each, clusters and planned work skipped`() {
        val events = CarsStates.parseEvents(res("in-events.json"), CarsStates.IN)
        assertEquals(listOf("in:CARSx-469755", "in:CARSy-33", "in:MSPCAD-148630", "in:CARSx-43373"), events.map { it.id })
        val ramp = events[0]
        // Under the closure icon, but only the exit ramp is closed: I-80 stays open, so road work (its words), not a
        // closure that would put an exclusion square across the interstate at the gore.
        assertEquals(IncidentKind.ROADWORK, ramp.kind)
        assertEquals("I-80", ramp.roadway)
        assertEquals("Eastbound", ramp.direction)
        assertEquals("I-80 eastbound: Exit ramp closed, because of roadwork.", ramp.description)
        assertEquals("Exit ramp closed", ramp.lanesStatus)
        assertEquals("Critical", ramp.severity)
        assertEquals(GeoPoint(41.56734, -87.33501), ramp.location)
        assertNull(ramp.updatedAt)
        assertEquals(TrafficSource.IN_511, ramp.source)
        val utility = events[1]
        assertEquals(IncidentKind.ROADWORK, utility.kind)
        assertEquals("US 20", utility.roadway)
        assertEquals("Both Directions", utility.direction)
        assertEquals("US 20 in both directions (Mile Point 27.23 - 28.2): Utility work, the right lane is closed.", utility.description)
        assertNull(utility.lanesStatus) // the first clause says what the work is, not the lanes
        assertNull(utility.severity)
        assertEquals(GeoPoint(41.61221, -87.09746), utility.location)
        val crash = events[2]
        assertEquals(IncidentKind.ACCIDENT, crash.kind)
        assertEquals("US 218", crash.roadway)
        assertNull(crash.direction)
        val bridge = events[3]
        assertEquals(IncidentKind.CLOSURE, bridge.kind)
        assertEquals("US 75", bridge.roadway)
        assertEquals("Both Directions", bridge.direction)
        assertFalse(bridge.description.contains("<strong>"))
    }

    @Test
    fun `Iowa's events, asked by its own layer names`() {
        // The wording and icons 511IA's two layers answered with (scripts/feed-probe.py, 2026-10-11); ids and places stand in.
        val events = CarsStates.parseEvents(res("ia-events.json"), CarsStates.IA)
        assertEquals(listOf("ia:CARSx-1001", "ia:CARSx-1002", "ia:CARSx-1003", "ia:CARSx-1005"), events.map { it.id })
        assertEquals(listOf(IncidentKind.CLOSURE, IncidentKind.ROADWORK, IncidentKind.ROADWORK, IncidentKind.ACCIDENT), events.map { it.kind })
        assertEquals("US 75", events[0].roadway)
        assertEquals("All lanes closed", events[0].lanesStatus)
        assertFalse(events[0].description.contains("<strong>"))
        assertEquals("Entrance ramp closed", events[2].lanesStatus)
        assertEquals("Left shoulder blocked", events[3].lanesStatus)
        assertEquals("Westbound", events[3].direction)
        assertTrue(events.all { it.source == TrafficSource.IA_511 })
    }

    @Test
    fun `kinds, lanes and directions from the icon and the words`() {
        assertEquals(IncidentKind.CLOSURE, CarsStates.kindOf("/images/tg_closure_urgent.svg", "Road closed."))
        assertEquals(IncidentKind.ACCIDENT, CarsStates.kindOf("/images/tg_crash_routine.svg", "Crash."))
        assertEquals(IncidentKind.ROADWORK, CarsStates.kindOf("/images/tg_warning_routine.svg", "Construction work, single lane traffic."))
        assertEquals(IncidentKind.CLOSURE, CarsStates.kindOf("/images/tg_warning_routine.svg", "Bridge closed, construction work."))
        assertEquals(IncidentKind.ACCIDENT, CarsStates.kindOf("/images/tg_warning_routine.svg", "Stalled vehicle."))
        assertEquals(IncidentKind.OTHER, CarsStates.kindOf("/images/tg_warning_routine.svg", "Look out for flaggers."))
        assertEquals(IncidentKind.OTHER, CarsStates.kindOf("/images/tg_ramp_impact_routine.svg", "Entrance ramp closed."))
        assertEquals(IncidentKind.CLOSURE, CarsStates.kindOf("/images/tg_warning_urgent.svg", "Railroad crossing closed from County Road 13 to 22nd Avenue."))
        assertEquals(IncidentKind.ROADWORK, CarsStates.kindOf("/images/tg_roadwork_routine.svg", "Entrance ramp closed, due to night time construction work."))
        assertEquals(IncidentKind.CLOSURE, CarsStates.kindOf("/images/tg_roadwork_routine.svg", "Road closed, due to road construction work."))
        assertEquals("Entrance ramp closed", CarsStates.lanesIn("Entrance ramp closed."))
        assertEquals("Exit ramp closed", CarsStates.lanesIn("Exit ramp closed, because of roadwork."))
        assertEquals("Right lane blocked", CarsStates.lanesIn("Right lane blocked. Crash."))
        assertNull(CarsStates.lanesIn("Crash."))
        assertEquals("Westbound", CarsStates.directionIn("I-94 westbound"))
        assertEquals("Both Directions", CarsStates.directionIn("US 75 NB/SB"))
        assertEquals("Both Directions", CarsStates.directionIn("MN 65 in both directions"))
        assertNull(CarsStates.directionIn("US 218"))
        assertEquals("MN 5", CarsStates.roadIn("Entrance ramp from MN 5 / W 7th St"))
        assertNull(CarsStates.roadIn("Lake Street"))
    }

    @Test
    fun `the query is the site's own, over the whole state, the same bytes every time`() {
        val body = CarsStates.eventsQuery(CarsStates.MN).decodeToString()
        assertEquals(body, CarsStates.eventsQuery(CarsStates.MN).decodeToString())
        val root = app.southbound.core.network.MiniJson.parse(body) as Map<*, *>
        assertTrue((root["query"] as String).contains("mapFeaturesQuery(input: \$input)"))
        val input = (root["variables"] as Map<*, *>)["input"] as Map<*, *>
        assertEquals(listOf("incidents", "closures", "construction", "roadReports"), input["layerSlugs"])
        assertEquals(CarsStates.ZOOM.toLong(), (input["zoom"] as Number).toLong())
        assertEquals(49.5, (input["north"] as Number).toDouble(), 0.0)
        assertEquals(-97.3, (input["west"] as Number).toDouble(), 0.0)
        assertEquals(listOf("dashboard"), input["nonClusterableUris"])
        // Iowa renamed its layers; asked for the others' names it answers with no events.
        for (site in CarsStates.SITES) {
            val slugs = ((app.southbound.core.network.MiniJson.parse(CarsStates.eventsQuery(site).decodeToString()) as Map<*, *>)["variables"] as Map<*, *>)
                .let { (it["input"] as Map<*, *>)["layerSlugs"] }
            val want = if (site === CarsStates.IA) listOf("iowaAppIncident", "iowaAppRoadwork") else listOf("incidents", "closures", "construction", "roadReports")
            assertEquals(site.source.name, want, slugs)
        }
        for (site in CarsStates.SITES) {
            val b = site.bounds
            assertTrue(site.source.name, b.west < b.east && b.south < b.north && b.west in -105.0..-84.0 && b.north in 36.0..50.0)
        }
    }

    @Test
    fun `the client posts the query under the state's toggle and reads its lists`() {
        val asked = ArrayList<String>()
        val client = CarsStates.Client(
            CarsStates.IN,
            { url -> asked += url; (if (url == CarsStates.IN.camerasUrl) res("in-cameras.json") else res("in-signs.json")).encodeToByteArray() },
        ) { url, body ->
            asked += url
            assertEquals(CarsStates.eventsQuery(CarsStates.IN).decodeToString(), body.decodeToString())
            res("in-events.json").encodeToByteArray()
        }
        assertEquals(TrafficSource.IN_511, client.source)
        assertEquals(4, client.events(0L).size)
        assertEquals(3, client.cameras().size)
        assertEquals(1, client.signs().size)
        assertEquals(listOf("https://511in.org/api/graphql", "https://intg.carsprogram.org/cameras_v1/api/cameras", "https://intg.carsprogram.org/signs_v1/api/signs"), asked)
    }

    @Test
    fun `a refusal or something else is refused`() {
        for (bad in listOf("<!DOCTYPE html><html>", """{"errors":[{"message":"Server error."}]}""", """{"data":{"mapFeaturesQuery":{"mapFeatures":[],"error":{"message":"no","type":"x"}}}}""", "[]")) {
            try {
                CarsStates.parseEvents(bad, CarsStates.IN)
                throw AssertionError("accepted $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(emptyList<Incident>(), CarsStates.parseEvents("""{"data":{"mapFeaturesQuery":{"mapFeatures":[]}}}""", CarsStates.IN))
    }
}
