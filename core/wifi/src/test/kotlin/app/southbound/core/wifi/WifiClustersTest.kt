package app.southbound.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiClustersTest {

    private fun spot(id: Long, lat: Double, lon: Double, tier: WifiTier = WifiTier.CHAIN) =
        WifiSpot(id, "n", id, "Place $id", null, lat, lon, "cafe", tier, WifiFee.UNKNOWN, null, null)

    @Test
    fun `no spots, no cells`() {
        assertTrue(WifiClusters.fromSpots(emptyList(), 7).isEmpty())
    }

    @Test
    fun `one cell holds its spots at their mean position with the best tier`() {
        val spots = listOf(
            spot(1, 39.50, -95.50, WifiTier.LIKELY),
            spot(2, 39.52, -95.48, WifiTier.TAGGED_UNSTATED),
            spot(3, 39.54, -95.46, WifiTier.CHAIN),
        )
        val tile = WifiTiles.tileOf(39.52, -95.48, 6)
        // All three are in one cell at zoom 6.
        assertTrue(spots.all { WifiTiles.tileOf(it.lat, it.lon, 6) == tile })
        val cells = WifiClusters.fromSpots(spots, 6)
        assertEquals(1, cells.size)
        val c = cells.single()
        assertEquals(6, c.zoom)
        assertEquals(tile.x, c.x)
        assertEquals(tile.y, c.y)
        assertEquals(3, c.count)
        assertEquals(39.52, c.lat, 1e-9)
        assertEquals(-95.48, c.lon, 1e-9)
        assertEquals(WifiTier.TAGGED_UNSTATED, c.tierMin)
    }

    @Test
    fun `spots in different cells make different cells, by the pack's tile maths`() {
        val a = spot(1, 39.5, -95.5, WifiTier.TAGGED_FREE)
        val b = spot(2, 47.6, -122.3, WifiTier.LIKELY)
        val cells = WifiClusters.fromSpots(listOf(b, a, spot(3, 47.61, -122.31, WifiTier.CHAIN)), 9)
        assertEquals(2, cells.size)
        // The tiles from the pipeline's maths (see WifiTilesTest): (120, 194) and (82, 178).
        assertEquals(listOf(82 to 178, 120 to 194), cells.map { it.x to it.y })
        assertEquals(listOf(2, 1), cells.map { it.count })
        assertEquals(listOf(WifiTier.CHAIN, WifiTier.TAGGED_FREE), cells.map { it.tierMin })
        val seattle = cells[0]
        assertEquals(47.605, seattle.lat, 1e-9)
        assertEquals(-122.305, seattle.lon, 1e-9)
    }

    @Test
    fun `finer zooms split what coarser zooms join, and the counts add up`() {
        val spots = (0 until 50).map { i -> spot(i.toLong(), 39.0 + i * 0.05, -95.0 - i * 0.07) }
        for (zoom in 3..11) {
            val cells = WifiClusters.fromSpots(spots, zoom)
            assertEquals(spots.size, cells.sumOf { it.count })
            assertTrue(cells.all { it.zoom == zoom })
        }
        assertTrue(WifiClusters.fromSpots(spots, 11).size > WifiClusters.fromSpots(spots, 3).size)
    }

    @Test
    fun `the order does not depend on the order of the spots`() {
        val spots = (0 until 30).map { i -> spot(i.toLong(), 30.0 + i * 0.4, -100.0 + i * 0.5, WifiTier.entries[i % 4]) }
        assertEquals(WifiClusters.fromSpots(spots, 6), WifiClusters.fromSpots(spots.reversed(), 6))
    }
}
