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
        assertEquals(6, Regions.basemaps.size)
        assertEquals("New York and Northern New Jersey", Regions.nameOf("nys"))
        assertEquals("custom", Regions.nameOf("custom"))
    }

    /** The states beyond New York are built but not offered until their packs are in a release. */
    @Test
    fun `planned regions stay out of the picker`() {
        assertEquals(listOf("njs", "ct", "pae", "paw", "md"), Regions.all.filter { it.planned }.map { it.id })
        assertTrue(Regions.basemaps.none { it.planned })
        assertNull(Regions.basemapAt(39.9526, -75.1636)) // Philadelphia
        assertEquals("Connecticut", Regions.nameOf("ct"))
    }

    @Test
    fun `northern new jersey reaches the delaware`() {
        assertEquals("njn", Regions.basemapAt(40.6937, -75.1902)?.id) // Phillipsburg
        assertEquals("njn", Regions.basemapAt(41.0582, -74.7527)?.id) // Newton
        assertNull(Regions.basemapAt(40.6884, -75.2207)) // Easton, PA, across the river
    }

    @Test
    fun `points land in a region`() {
        assertEquals("nyc", Regions.basemapAt(40.7484, -73.9857)?.id)
        assertEquals("western", Regions.basemapAt(42.8864, -78.8784)?.id)
        assertEquals("capital", Regions.basemapAt(42.6526, -73.7562)?.id)
        assertNull(Regions.basemapAt(39.0, -77.0))
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
