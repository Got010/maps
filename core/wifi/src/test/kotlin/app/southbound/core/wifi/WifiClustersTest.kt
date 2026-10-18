package app.southbound.core.wifi

import app.southbound.core.search.NearbyBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `in view only the cells of the tiles the box touches are kept`() {
        val box = NearbyBox(south = 39.4, north = 39.6, west = -95.6, east = -95.4)
        val inside = spot(1, 39.5, -95.5)
        val far = spot(2, 45.0, -100.0)
        val cells = WifiClusters.inView(listOf(inside, far).map(::point), WifiFilter.DEFAULT, box, 9).clusters
        assertEquals(1, cells.size)
        assertEquals(WifiTiles.tileOf(39.5, -95.5, 9).x, cells.single().x)
        assertTrue(WifiClusters.inView(emptyList(), WifiFilter.DEFAULT, box, 9).clusters.isEmpty())
    }

    private fun point(s: WifiSpot, category: String = s.category) = WifiPoint(s.id, s.lat, s.lon, s.tier, category)

    @Test
    fun `fromPoints gives the cells fromSpots does`() {
        val spots = (0 until 20).map { i -> spot(i.toLong(), 35.0 + i * 0.3, -90.0 - i * 0.2, WifiTier.entries[i % 4]) }
        assertEquals(WifiClusters.fromSpots(spots, 6), WifiClusters.fromPoints(spots.map(::point), 6))
    }

    @Test
    fun `the kinds cut the points before they are counted`() {
        val box = NearbyBox(south = 39.0, north = 40.0, west = -96.0, east = -95.0)
        val points = listOf(
            WifiPoint(1, 39.5, -95.5, WifiTier.CHAIN, "cafe"),
            WifiPoint(2, 39.5, -95.5, WifiTier.TAGGED_FREE, "library"),
            WifiPoint(3, 39.5, -95.5, WifiTier.LIKELY, "pub"),
        )
        val food = WifiFilter(kinds = setOf(WifiKind.CAFES_FOOD))
        val cell = WifiClusters.inView(points, food, box, 8).clusters.single()
        assertEquals(2, cell.count)
        assertEquals(WifiTier.CHAIN, cell.tierMin)
        assertTrue(WifiClusters.inView(points, WifiFilter(kinds = setOf(WifiKind.FUEL)), box, 8).clusters.isEmpty())
    }

    @Test
    fun `a read that hit its limit is drawn but marked partial, judged before the kinds cut it`() {
        val box = NearbyBox(south = 39.0, north = 40.0, west = -96.0, east = -95.0)
        val points = (1..5).map { WifiPoint(it.toLong(), 39.5, -95.5, WifiTier.CHAIN, if (it == 1) "cafe" else "library") }
        val onlyFood = WifiFilter(kinds = setOf(WifiKind.CAFES_FOOD))
        val cut = WifiClusters.inView(points, onlyFood, box, 8, limit = 5)
        assertTrue(cut.partial)
        assertEquals(1, cut.clusters.single().count)
        assertFalse(WifiClusters.inView(points, onlyFood, box, 8, limit = 6).partial)
        assertFalse(WifiClusters.inView(emptyList(), onlyFood, box, 8, limit = 6).partial)
        // The pack's own limit is the default.
        assertFalse(WifiClusters.inView(points, onlyFood, box, 8).partial)
    }

    @Test
    fun `cells read for the cells box have the whole count of an edge cell`() {
        // The view's edge cuts a cell; the spots on both sides of the edge are in that one cell.
        val zoom = 9
        val tile = WifiTiles.tileOf(39.5, -95.5, zoom)
        val west = spot(1, 39.5, -95.5)
        val view = NearbyBox(south = 39.4, north = 39.6, west = -95.5001, east = -95.4)
        val cellBox = WifiTiles.cellsBox(view, zoom)
        val spots = listOf(west, spot(2, 39.5, cellBox.west + 0.0005), spot(3, 39.5, cellBox.west + 0.0007))
        assertTrue(spots.all { cellBox.contains(it.lat, it.lon) })
        assertEquals(3, WifiClusters.inView(spots.map(::point), WifiFilter.DEFAULT, view, zoom).clusters.filter { it.x == tile.x }.sumOf { it.count })
    }
}
