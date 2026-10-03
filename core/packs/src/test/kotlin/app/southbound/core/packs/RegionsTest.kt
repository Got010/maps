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
            val bounds = (expected["bounds"] as List<*>).map { (it as Number).toDouble() }
            assertEquals(region.id, bounds, listOf(region.bounds.minLon, region.bounds.minLat, region.bounds.maxLon, region.bounds.maxLat))
        }
    }

    @Test
    fun `statewide is not a basemap region`() {
        assertTrue(Regions.basemaps.none { it.id == Regions.STATEWIDE })
        assertEquals(5, Regions.basemaps.size)
        assertEquals("All of New York State", Regions.nameOf("nys"))
        assertEquals("custom", Regions.nameOf("custom"))
    }

    @Test
    fun `points land in a region`() {
        assertEquals("nyc", Regions.basemapAt(40.7484, -73.9857)?.id)
        assertEquals("western", Regions.basemapAt(42.8864, -78.8784)?.id)
        assertEquals("capital", Regions.basemapAt(42.6526, -73.7562)?.id)
        assertNull(Regions.basemapAt(39.0, -77.0))
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
