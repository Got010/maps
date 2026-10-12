package app.southbound.core.traffic

import app.southbound.core.network.InMemoryNetworkToggles
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.GZIPOutputStream

/**
 * 511PA and CTroads ([Ibi511]). The list rows in src/test/resources/ibi are real, printed by the feed probe on
 * 2026-10-11 (511PA: two incidents, two closures, two roadwork rows, a camera, a sign; CTroads: a camera, a sign).
 * Icon positions are real for 399927, 399960 and 122369 (the probe printed those icons); the others stand in near
 * the place their row names, since the probe printed their rows and not their icons.
 */
class Ibi511Test {
    private fun res(name: String) = File("src/test/resources/ibi/$name").readBytes()

    /** A map layer as the sites send it: `{"item1":{...},"item2":[{"itemId":..,"location":[lat,lon],...}]}`. */
    private fun icons(vararg items: Pair<String, Pair<Double, Double>>): ByteArray =
        items.joinToString(",", """{"item1":{"url":"/Generated/Content/Images/511/map_event.svg"},"item2":[""", "]}") { (id, at) ->
            """{"itemId":"$id","location":[${at.first},${at.second}],"icon":{},"title":""}"""
        }.toByteArray()

    private val empty = """{"item2":[]}""".toByteArray()

    private val paIcons = mapOf(
        "OtherRouteClosure" to icons("399927" to (41.181709 to -76.012361), "399929" to (40.284 to -76.929)),
        "OtherRouteIncident" to icons("399960" to (41.039338 to -75.843122), "402128" to (40.731 to -80.262)),
        "ActiveRoadwork" to icons("122369" to (41.381607 to -75.724396), "136415" to (39.980 to -78.972)),
        "MessageSigns" to icons("181039" to (40.330 to -76.800)),
    )
    private val paLists = mapOf(
        "Incidents" to res("pa-incidents.json"),
        "Closures" to res("pa-closures.json"),
        "traffic" to res("pa-traffic.json"),
        "Cameras" to res("pa-cameras.json"),
        "MessageSigns" to res("pa-signs.json"),
    )

    private val asked = ArrayList<String>()

    /** Answers a site's requests from [icons] and [lists] (a list's first page; later pages from [pages]). */
    private fun site(
        icons: Map<String, ByteArray>,
        lists: Map<String, ByteArray>,
        pages: (String, Int) -> ByteArray? = { _, _ -> null },
    ): (String) -> ByteArray = { url ->
        synchronized(asked) { asked += url }
        when {
            "/map/mapIcons/" in url -> icons[url.substringAfter("/map/mapIcons/")] ?: empty
            "/List/GetData/" in url -> {
                val list = url.substringAfter("/List/GetData/").substringBefore('?')
                val query = URLDecoder.decode(url.substringAfter("?query="), "UTF-8")
                val start = Regex(""""start":(\d+)""").find(query)!!.groupValues[1].toInt()
                assertTrue(query, """"length":100""" in query)
                pages(list, start) ?: if (start == 0) lists[list] ?: throw IOException("HTTP 500") else throw IOException("no page at $start")
            }
            else -> throw IOException("unexpected $url")
        }
    }

    private fun eastern(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    private fun central(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of("America/Chicago")).toInstant().toEpochMilli()

    @Test
    fun `511PA events join their icons to their rows`() {
        val events = Ibi511Client(Ibi511.PA, site(paIcons, paLists)).events(0L).associateBy { it.id }
        assertEquals(setOf("pa:399927", "pa:399929", "pa:399960", "pa:402128", "pa:122369", "pa:136415"), events.keys)
        assertTrue(events.values.all { it.source == TrafficSource.PA_511 })

        val closure = events.getValue("pa:399927")
        assertEquals(IncidentKind.CLOSURE, closure.kind)
        assertEquals("Market St", closure.roadway)
        assertEquals("Both Directions", closure.direction)
        assertEquals("All lanes closed", closure.lanesStatus)
        assertEquals(GeoPoint(41.181709, -76.012361), closure.location)
        assertEquals(eastern(2026, 10, 1, 10, 20), closure.updatedAt)
        assertTrue(closure.description.startsWith("DAMAGED ROADWAY on (3001) MARKET ST"))
        assertTrue(closure.isClosure)
        // Roadwork that shuts the road, in the closures layer, is a closure too.
        assertEquals(IncidentKind.CLOSURE, events.getValue("pa:399929").kind)
        assertEquals("Valley Rd", events.getValue("pa:399929").roadway)

        val crash = events.getValue("pa:402128")
        assertEquals(IncidentKind.ACCIDENT, crash.kind)
        assertEquals("PA-68", crash.roadway)
        assertEquals("Eastbound", crash.direction)
        // "Traffic Disruption" and "All Lanes Disrupted." name no closed lane.
        assertNull(crash.lanesStatus)
        assertFalse(crash.isClosure)

        val other = events.getValue("pa:399960")
        assertEquals(IncidentKind.OTHER, other.kind)
        assertEquals("Buffalo St", other.roadway)
        assertEquals("Westbound", other.direction)
        // "No lanes blocked." is not a lane closure.
        assertNull(other.lanesStatus)

        val roadwork = events.getValue("pa:122369")
        assertEquals(IncidentKind.ROADWORK, roadwork.kind)
        assertEquals("I-476", roadwork.roadway)
        assertEquals("Northbound", roadwork.direction)
        // "The right shoulder is closed.": roadwork, not a closure, and no lane to speak of.
        assertNull(roadwork.lanesStatus)
        assertFalse(roadwork.isClosure)
        // 511PA's severity is a number only: not shown.
        assertNull(roadwork.severity)
        assertEquals("Eastbound", events.getValue("pa:136415").direction)
    }

    /**
     * 511WI: the rows the probe printed on 2026-10-11 (a camera with a still and a video stream, a sign, a roadwork row
     * and a "Roadwork Closure" row marked isFullClosure). The closure's icon is real too (the sixth round found the Closures rows drawn as ConstructionClosures icons).
     */
    @Test
    fun `511WI reads as 511PA does, with camera video on its own servers`() {
        val icons = mapOf(
            "Construction" to icons("566011" to (43.0337705435589 to -87.969008234053), "566098" to (44.349897010949 to -88.1913438409978)),
            "ConstructionClosures" to icons("566098" to (44.349897010949 to -88.1913438409978)),
            "MessageSigns" to icons("ATMS_DMS--1" to (42.99461 to -88.03796)),
        )
        val lists = mapOf(
            "traffic" to res("wi-traffic.json"), "Closures" to res("wi-closures.json"), "Incidents" to res("wi-incidents.json"),
            "Cameras" to res("wi-cameras.json"), "MessageSigns" to res("wi-signs.json"),
        )
        val client = Ibi511Client(Ibi511.WI, site(icons, lists))
        val events = client.events(0L).associateBy { it.id }
        assertEquals(setOf("wi:566011", "wi:566098"), events.keys)
        val work = events.getValue("wi:566011")
        assertEquals(IncidentKind.ROADWORK, work.kind)
        assertEquals("I-94", work.roadway)
        assertEquals("Westbound", work.direction)
        assertEquals(GeoPoint(43.0337705435589, -87.969008234053), work.location)
        assertTrue(work.description.startsWith("Roadwork - Mainline Right Lane Closed on I-94 WB"))
        assertFalse(work.isClosure)
        assertEquals(TrafficSource.WI_511, work.source)
        // "3/5/26, 2:58 PM": Wisconsin writes its own (Central) time.
        assertEquals(central(2026, 3, 5, 14, 58), work.updatedAt)
        val closed = events.getValue("wi:566098")
        assertEquals(IncidentKind.CLOSURE, closed.kind)
        assertEquals("French Rd", closed.roadway)
        assertEquals("All lanes closed", closed.lanesStatus)
        assertTrue(closed.isClosure)

        val cameras = client.cameras()
        assertEquals(2, cameras.size)
        val cam = cameras.first()
        assertEquals("wi:1", cam.id)
        assertEquals("I-39/US 51 at County B", cam.name)
        assertEquals("https://511wi.gov/map/Cctv/937", cam.imageUrl)
        assertEquals("https://cctv1.dot.wi.gov/rtplive/CCTV-49-0011/playlist.m3u8", cam.videoUrl)
        assertEquals(GeoPoint(44.454149, -89.518702), cam.location)
        assertNull(cam.roadway?.takeIf { it == "Unknown" })
        // A stream on a host that isn't WisDOT's is not followed; the still stays.
        assertNull(cameras[1].videoUrl)
        assertEquals("https://511wi.gov/map/Cctv/1321", cameras[1].imageUrl)
        // 511PA's cameras name no stream.
        assertTrue(Ibi511Client(Ibi511.PA, site(paIcons, paLists)).cameras().all { it.videoUrl == null })

        val sign = client.signs().single()
        assertEquals("wi:ATMS_DMS--1", sign.id)
        assertEquals(listOf("BARKER RD 10 MIN", "CAPITOL DR 8 MIN", "DOWNTOWN 9 MIN"), sign.lines)
        assertEquals("I-41/894", sign.roadway)
        assertEquals("Northbound", sign.direction)
        assertEquals(GeoPoint(42.99461, -88.03796), sign.location)
    }

    @Test
    fun `a list is read again only when its layers show something new, or after a while`() {
        val icons = paIcons.toMutableMap()
        val client = Ibi511Client(Ibi511.PA, site(icons, paLists))
        fun lists() = synchronized(asked) { asked.filter { "/List/GetData/" in it }.map { it.substringAfter("/List/GetData/").substringBefore('?') } }
        client.events(0L)
        assertEquals(listOf("Closures", "Incidents", "traffic"), lists().sorted())

        // The same icons: only the icons again.
        asked.clear()
        assertEquals(6, client.events(60_000L).size)
        assertTrue(lists().isEmpty())
        assertEquals(Ibi511.PA.eventLayers.size, asked.size)

        // A new incident on the map: the incidents list again, not the others. Its row isn't in the list (yet):
        // left out, and not asked for again on the next round.
        icons["OtherRouteIncident"] = icons("399960" to (41.039338 to -75.843122), "402128" to (40.731 to -80.262), "999999" to (40.0 to -76.0))
        asked.clear()
        assertEquals(6, client.events(120_000L).size)
        assertEquals(listOf("Incidents"), lists())
        asked.clear()
        client.events(180_000L)
        assertTrue(lists().isEmpty())

        // Gone from the map: gone from the events, without reading a list.
        icons["ActiveRoadwork"] = icons("122369" to (41.381607 to -75.724396))
        asked.clear()
        assertEquals(setOf("pa:399927", "pa:399929", "pa:399960", "pa:402128", "pa:122369"), client.events(240_000L).map { it.id }.toSet())
        assertTrue(lists().isEmpty())

        // After a while every list with something on the map is read again (the incidents list counts from its last read).
        asked.clear()
        client.events(120_000L + Ibi511.LIST_MAX_AGE_MILLIS)
        assertEquals(listOf("Closures", "Incidents", "traffic"), lists().sorted())
    }

    @Test
    fun `a list is read page by page up to its total`() {
        // 150 incidents: two pages of the incidents list (the sites send 100 rows a page at most).
        fun row(i: Int) = """{"DT_RowId":"$i","id":$i,"type":"Incident - Other Route","layerName":"OtherRouteIncident",""" +
            """"roadwayName":"PA-68","description":"CRASH on PA-68 Eastbound At ZEIGLER RD.","isFullClosure":false,"direction":"E",""" +
            """"laneDescription":"","lastUpdated":"10/6/26, 8:02 AM"}"""
        fun page(from: Int, count: Int) =
            """{"draw":0,"recordsTotal":150,"recordsFiltered":150,"data":[${(from until from + count).joinToString(",") { row(it) }}]}""".toByteArray()
        val many = icons(*(1..150).map { "$it" to (40.0 + it / 1000.0 to -76.0) }.toTypedArray())
        val starts = ArrayList<Int>()
        val fetch = site(mapOf("OtherRouteIncident" to many), emptyMap()) { list, start ->
            assertEquals("Incidents", list)
            starts += start
            when (start) {
                0 -> page(1, 100)
                100 -> page(101, 50)
                else -> null
            }
        }
        val events = Ibi511Client(Ibi511.PA, fetch).events(0L)
        assertEquals(150, events.size)
        assertEquals(listOf(0, 100), starts)
        assertTrue(events.all { it.kind == IncidentKind.ACCIDENT })
    }

    @Test
    fun `cameras have their own position and a still on the site`() {
        val pa = Ibi511Client(Ibi511.PA, site(emptyMap(), paLists)).cameras().single()
        assertEquals("pa:1", pa.id)
        assertEquals("I-176 E/B @ SR 422 Interchange Berks Co.", pa.name)
        assertEquals(40.30142, pa.lat, 1e-9)
        assertEquals(-75.90045, pa.lon, 1e-9)
        // The image's own id, not the camera's (/map/Cctv/1 is a placeholder picture).
        assertEquals("https://www.511pa.com/map/Cctv/4691", pa.imageUrl)
        assertEquals("I-176", pa.roadway)
        assertNull(pa.direction)
        assertTrue(pa.hasImage)
        assertEquals(TrafficSource.PA_511, pa.source)

        val ct = Ibi511Client(Ibi511.CT, site(emptyMap(), mapOf("Cameras" to res("ct-cameras.json")))).cameras().single()
        assertEquals("ct:308", ct.id)
        assertEquals("CAM 1 Vernon I-84 WB Exit 64 - Rt. 30 & 83 (Hartford Tpke.)", ct.name)
        assertEquals("https://ctroads.org/map/Cctv/308", ct.imageUrl)
        assertEquals("I-84", ct.roadway)
        assertEquals("Westbound", ct.direction)
        assertEquals(TrafficSource.CT_ROADS, ct.source)
    }

    @Test
    fun `signs join their icons, with their pages`() {
        val pa = Ibi511Client(Ibi511.PA, site(paIcons, paLists)).signs().single()
        assertEquals("pa:181039", pa.id)
        assertEquals(listOf("NO TRUCKS", "OVER", "29 FEET", "", "AFTER", "PIKETOWN", "ROAD"), pa.lines)
        assertEquals("PA-District 8 Maintenance Yard", pa.roadway)
        assertEquals(eastern(2026, 9, 7, 0, 3), pa.updatedAt)
        assertEquals(40.330, pa.lat, 1e-9)

        // CTroads' blank sign: three lines of spaces.
        val ct = Ibi511Client(Ibi511.CT, site(mapOf("MessageSigns" to icons("46" to (41.55 to -73.06))), mapOf("MessageSigns" to res("ct-signs.json"))))
            .signs().single()
        assertEquals("ct:46", ct.id)
        assertTrue(ct.isBlank)
        assertEquals("I-84", ct.roadway)
        assertEquals("Eastbound", ct.direction)
        // A sign without an icon isn't placed; 511PA's NO_MESSAGE is a blank sign.
        val noIcon = Ibi511Client(Ibi511.CT, site(emptyMap(), mapOf("MessageSigns" to res("ct-signs.json")))).signs()
        assertTrue(noIcon.isEmpty())
        val off = Ibi511.sign(mapOf("DT_RowId" to "181021", "message" to "NO_MESSAGE", "message2" to "", "status" to "Off"), GeoPoint(40.0, -76.0), Ibi511.PA)
        assertTrue(off!!.isBlank)
    }

    @Test
    fun `gzipped answers are read`() {
        val plain = """{"item2":[{"itemId":"7","location":[41.0,-75.0]}]}"""
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain.toByteArray()) } }.toByteArray()
        assertEquals(plain, Ibi511.text(zipped))
        assertEquals(plain, Ibi511.text(plain.toByteArray()))
        assertEquals(mapOf("7" to GeoPoint(41.0, -75.0)), Ibi511.parseIcons(Ibi511.text(zipped)))
    }

    @Test
    fun `an error page is an error, not an empty layer`() {
        for (bad in listOf("<html>Pennsylvania Error | 511PA</html>", "{}", "[]")) {
            try {
                Ibi511.parseIcons(bad)
                fail("read $bad as a layer")
            } catch (e: IllegalArgumentException) {
                // expected
            }
            try {
                Ibi511.parsePage(bad)
                fail("read $bad as a page")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        // The CTroads of that evening: layers with nothing in them are fine.
        assertTrue(Ibi511.parseIcons("""{"item2":[]}""").isEmpty())
    }

    @Test
    fun `roads, directions and lanes as a driver hears them`() {
        assertEquals("Market St", Ibi511.roadway("(3001) MARKET ST / RIVER RD / MAIN ST / ALDEN MTN RD / ROBERT ST"))
        assertEquals("Cedar Crest Bl", Ibi511.roadway("(1019) CEDAR CREST BL"))
        assertEquals("I-476", Ibi511.roadway("I-476"))
        assertEquals("US-15", Ibi511.roadway("US-15"))
        assertEquals("PA Turnpike", Ibi511.roadway("PA TURNPIKE"))
        assertEquals("Old Brick Rd", Ibi511.roadway("OLD BRICK RD / MAIN ST"))
        assertEquals("PA-District 8 Maintenance Yard", Ibi511.roadway("PA-District 8 Maintenance Yard"))
        assertNull(Ibi511.roadway(" "))

        assertEquals("Northbound", Ibi511.direction("n"))
        assertEquals("Westbound", Ibi511.direction("W"))
        assertEquals("Both Directions", Ibi511.direction("BOTHDIR"))
        assertEquals("Southbound", Ibi511.direction("Southbound"))
        assertNull(Ibi511.direction("u"))
        assertNull(Ibi511.direction("Unknown"))

        assertEquals("All lanes closed", Ibi511.lanes("Closed", true, "ROADWORK on (1004) VALLEY RD. Closed."))
        assertEquals("1 of 2 lanes blocked", Ibi511.lanes("1 lane(s) of 2 blocked.", false, "CRASH on I-81 South."))
        assertEquals("Left lane closed", Ibi511.lanes("", false, "Road work on I-80 West. The left lane is closed."))
        assertNull(Ibi511.lanes("No lanes blocked.", false, "OTHER on PA-65. No lanes blocked."))
        assertNull(Ibi511.lanes("Shoulder blocked.", false, "Road work on I-476 North. The right shoulder is closed."))
        assertNull(Ibi511.lanes("Lane Restriction", false, "Road work on US-22."))
    }

    @Test
    fun `the feed asks 511PA only under its own toggle`() = runBlocking {
        val toggles = InMemoryNetworkToggles(mapOf(NetworkFeature.TRAFFIC_511PA to true))
        val gate = NetworkGate(toggles)
        val features = ArrayList<NetworkFeature>()
        val pa = site(paIcons, paLists)
        val feed = TrafficFeed(gate, { 0L }) { feature, url ->
            gate.request(feature) {
                synchronized(features) { features += feature }
                if (url.startsWith(Ibi511.PA.base)) pa(url) else throw IOException("unexpected $url")
            }
        }
        assertTrue(feed.isEnabled)
        assertTrue(feed.hasIncidents)
        val plain = feed.refresh()
        assertEquals(6, plain.incidents.size)
        assertEquals(setOf(NetworkFeature.TRAFFIC_511PA), features.toSet())
        assertTrue(plain.cameras.isEmpty() && plain.signs.isEmpty())
        assertEquals(2, plain.closures.size)
        // The map's extras: cameras and signs too.
        val shown = feed.refresh(force = true, mapExtras = true)
        assertEquals(listOf("pa:1"), shown.cameras.map { it.id })
        assertEquals(listOf("pa:181039"), shown.signs.map { it.id })
        assertTrue(shown.errors.isEmpty())
        // Off: its items go, and nothing more is asked.
        toggles.set(NetworkFeature.TRAFFIC_511PA, false)
        features.clear()
        val off = feed.refresh(force = true, mapExtras = true)
        assertTrue(off.incidents.isEmpty() && off.cameras.isEmpty() && off.signs.isEmpty())
        assertTrue(features.isEmpty())
        assertFalse(feed.hasIncidents)
    }

    @Test
    fun `a site that fails keeps its last events and says so`() = runBlocking {
        val toggles = InMemoryNetworkToggles(mapOf(NetworkFeature.TRAFFIC_CTROADS to true, NetworkFeature.TRAFFIC_511PA to true))
        val gate = NetworkGate(toggles)
        var down = false
        val pa = site(paIcons, paLists)
        val ct = site(emptyMap(), emptyMap())
        var now = 0L
        val feed = TrafficFeed(gate, { now }) { feature, url ->
            gate.request(feature) {
                when {
                    url.startsWith(Ibi511.PA.base) -> if (down) throw IOException("timeout") else pa(url)
                    url.startsWith(Ibi511.CT.base) -> ct(url)
                    else -> throw IOException("unexpected $url")
                }
            }
        }
        assertEquals(6, feed.refresh().incidents.size)
        down = true
        now = TrafficFeed.INCIDENTS_TTL_MILLIS
        val later = feed.refresh()
        assertEquals(6, later.incidents.size)
        assertEquals(listOf("511PA: timeout"), later.errors)
    }
}
