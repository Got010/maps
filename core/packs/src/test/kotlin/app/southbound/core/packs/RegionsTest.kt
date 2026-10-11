package app.southbound.core.packs

import app.southbound.core.network.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegionsTest {

    /** pipeline/regions.json is what the pack builders read; the app's copy must say the same. */
    @Test
    fun `matches the pipeline's regions`() {
        val root = MiniJson.parse(pipelineDir().resolve("regions.json").readText()) as Map<*, *>
        val regions = root["regions"] as Map<*, *>
        assertEquals(regions.keys.toList(), Regions.all.map { it.id })
        for (region in Regions.all) {
            val expected = regions[region.id] as Map<*, *>
            assertEquals(region.id, expected["name"], region.name)
            assertEquals(region.id, expected["basemap"], region.basemap)
            assertEquals(region.id, expected["planned"] ?: false, region.planned)
            val bounds = (expected["bounds"] as List<*>).map { (it as Number).toDouble() }
            assertEquals(region.id, bounds, listOf(region.bounds.minLon, region.bounds.minLat, region.bounds.maxLon, region.bounds.maxLat))
            val area = (expected["area"] as List<*>?).orEmpty().map { p -> (p as List<*>).map { (it as Number).toDouble() } }
            assertEquals(region.id, area, region.area.map { listOf(it.lon, it.lat) })
        }
    }

    @Test
    fun `statewide is not a basemap region`() {
        assertTrue(Regions.basemaps.none { it.id == Regions.STATEWIDE })
        assertTrue(Regions.basemaps.none { it.id == "all" })
        assertEquals(11, Regions.basemaps.size)
        assertEquals("New York and Northern New Jersey", Regions.nameOf("nys"))
        assertEquals("custom", Regions.nameOf("custom"))
    }

    /** The four states beyond New York, offered since their packs are in southbound-v2.json; the Midwest not yet. */
    @Test
    fun `the other states are offered, grouped by state`() {
        assertEquals(listOf("ohn", "ohs", "in", "iln", "ils", "mis", "miu", "wi", "mn", "ia", "mo", "nd", "sd", "ne", "ks"), Regions.all.filter { it.planned }.map { it.id })
        assertEquals(
            listOf("New York", "New Jersey", "Connecticut", "Pennsylvania", "Maryland, Delaware and DC"),
            Regions.byState.map { it.first },
        )
        // Every region under one heading, in the picker's order.
        assertEquals(Regions.basemaps, Regions.byState.flatMap { it.second })
        assertEquals("pae", Regions.basemapAt(39.9526, -75.1636)?.id) // Philadelphia
        assertEquals("paw", Regions.basemapAt(40.4406, -79.9959)?.id) // Pittsburgh
        assertEquals("ct", Regions.basemapAt(41.0534, -73.5387)?.id) // Stamford, inside Long Island's box
        assertEquals("md", Regions.basemapAt(38.9072, -77.0369)?.id) // Washington, DC
        assertEquals("md", Regions.basemapAt(39.7447, -75.5466)?.id) // Wilmington, DE
        assertEquals("njs", Regions.basemapAt(39.3643, -74.4229)?.id) // Atlantic City
    }

    /** Planned regions are built, not offered: nothing is installed or asked for there until their packs ship. */
    @Test
    fun `the midwest is planned, not offered`() {
        assertNull(Regions.basemapAt(41.8781, -87.6298)) // Chicago
        assertTrue(Regions.byState.none { it.first == "Illinois" })
        fun only(id: String, lat: Double, lon: Double) {
            val region = Regions.byId(id)!!
            assertTrue("$id should hold $lat,$lon", region.planned && region.contains(lat, lon))
            val others = Regions.all.filter { it.planned && it.id != id && it.contains(lat, lon) }.map { it.id }
            assertEquals("$lat,$lon is $id's alone", emptyList<String>(), others)
        }
        only("iln", 41.8781, -87.6298) // Chicago
        only("iln", 42.2711, -89.0940) // Rockford
        only("ils", 40.6936, -89.5890) // Peoria
        only("ils", 38.6245, -90.1507) // East St. Louis
        only("mo", 38.6386, -90.2847) // St. Louis, Forest Park (the outlines run about a kilometre from the river)
        only("ia", 41.5868, -93.6250) // Des Moines
        // State lines, not boxes: Toledo is Ohio's though Michigan's box reaches it; Gary, Indiana is not Illinois'.
        only("ohn", 41.6528, -83.5379) // Toledo
        only("ohn", 41.4993, -81.6944) // Cleveland
        only("ohs", 39.9612, -82.9988) // Columbus
        only("ohs", 39.1031, -84.5120) // Cincinnati
        only("in", 41.5934, -87.3464) // Gary
        // Michigan: the south, the north and the Upper Peninsula; Wisconsin's side of the Menominee.
        only("mis", 42.3314, -83.0458) // Detroit
        only("mis", 42.9634, -85.6681) // Grand Rapids
        only("miu", 44.7631, -85.6206) // Traverse City
        only("miu", 46.4953, -84.3453) // Sault Ste. Marie
        only("wi", 45.1083, -87.6143) // Marinette
        only("mn", 44.9778, -93.2650) // Minneapolis
        only("ks", 39.0473, -95.6752) // Topeka
    }

    @Test
    fun `northern new jersey reaches the delaware`() {
        assertEquals("njn", Regions.basemapAt(40.6937, -75.1902)?.id) // Phillipsburg
        assertEquals("njn", Regions.basemapAt(41.0582, -74.7527)?.id) // Newton
        assertEquals("pae", Regions.basemapAt(40.6884, -75.2207)?.id) // Easton, PA, across the river
    }

    @Test
    fun `points land in a region`() {
        assertEquals("nyc", Regions.basemapAt(40.7484, -73.9857)?.id)
        assertEquals("western", Regions.basemapAt(42.8864, -78.8784)?.id)
        assertEquals("capital", Regions.basemapAt(42.6526, -73.7562)?.id)
        assertEquals("md", Regions.basemapAt(39.0, -77.0)?.id) // Silver Spring
        assertNull(Regions.basemapAt(37.5, -78.0)) // Virginia
    }

    /** New York City's box covers the New Jersey side of the Hudson; the njn area takes those points back. */
    @Test
    fun `new jersey points go to njn, not to the box that overlaps them`() {
        assertEquals("njn", Regions.basemapAt(40.7357, -74.1724)?.id) // Newark
        assertEquals("njn", Regions.basemapAt(40.7178, -74.0431)?.id) // Jersey City
        assertEquals("njn", Regions.basemapAt(40.7439, -74.0324)?.id) // Hoboken
        assertEquals("njn", Regions.basemapAt(40.8859, -74.0435)?.id) // Hackensack
        assertEquals("njn", Regions.basemapAt(40.9168, -74.1718)?.id) // Paterson
        assertEquals("njn", Regions.basemapAt(40.7968, -74.4815)?.id) // Morristown
        assertEquals("njn", Regions.basemapAt(40.6640, -74.2107)?.id) // Elizabeth
        assertEquals("njn", Regions.basemapAt(40.5068, -74.2654)?.id) // Perth Amboy
        assertEquals("njn", Regions.basemapAt(40.9793, -74.1165)?.id) // Ridgewood, inside Hudson Valley's box
        assertEquals(listOf("njn"), Regions.basemapsAt(40.7357, -74.1724).map { it.id })
    }

    @Test
    fun `new york points near the state line stay in their own regions`() {
        assertEquals("nyc", Regions.basemapAt(40.6437, -74.0765)?.id) // Staten Island, St. George
        assertEquals("nyc", Regions.basemapAt(40.5057, -74.2444)?.id) // Staten Island, Tottenville
        assertEquals("nyc", Regions.basemapAt(40.5540, -74.1510)?.id) // Staten Island, Great Kills
        assertEquals("nyc", Regions.basemapAt(40.7470, -74.0080)?.id) // Manhattan's Hudson shore
        assertEquals("nyc", Regions.basemapAt(40.8500, -73.9390)?.id) // Washington Heights
        assertEquals("hudson", Regions.basemapAt(40.9312, -73.8988)?.id) // Yonkers
        assertEquals("hudson", Regions.basemapAt(41.0906, -73.9179)?.id) // Nyack
        assertEquals("hudson", Regions.basemapAt(41.4459, -74.4229)?.id) // Middletown, NY
        assertEquals("hudson", Regions.basemapAt(41.375, -74.69)?.id) // Port Jervis, NY
    }

    @Test
    fun `bounds of several regions is their union`() {
        val b = Regions.boundsOf(listOf("nyc", "li"))!!
        assertEquals(Bounds(-74.2591, 40.4774, -71.75, 41.25), b)
        assertNull(Regions.boundsOf(listOf("mars")))
    }

    private fun pipelineDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = dir.resolve("pipeline")
            if (candidate.resolve("regions.json").isFile) return candidate
            dir = dir.parentFile
        }
        error("pipeline/ not found above ${System.getProperty("user.dir")}")
    }
}
