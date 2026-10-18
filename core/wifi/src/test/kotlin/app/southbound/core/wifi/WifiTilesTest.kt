package app.southbound.core.wifi

import app.southbound.core.search.NearbyBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected cells are what pipeline/wifi/build_wifi.py's `tile_of` gives for the same points. */
class WifiTilesTest {

    private val points = listOf(
        35.0 to -97.0, 44.5 to -110.25, 25.8 to -80.2, 47.6 to -122.3, 39.5 to -95.5,
        89.0 to 0.0, -89.0 to 0.0, 0.0 to 180.0, 0.0 to -180.0, 38.9 to -77.0,
    )

    private fun tiles(zoom: Int) = points.map { (lat, lon) -> WifiTiles.tileOf(lat, lon, zoom).let { it.x to it.y } }

    @Test
    fun `tiles match the pipeline's maths at every cluster zoom`() {
        assertEquals(
            listOf(1 to 3, 1 to 2, 2 to 3, 1 to 2, 1 to 3, 4 to 0, 4 to 7, 7 to 4, 0 to 4, 2 to 3),
            tiles(3),
        )
        assertEquals(
            listOf(14 to 25, 12 to 23, 17 to 27, 10 to 22, 15 to 24, 32 to 0, 32 to 63, 63 to 32, 0 to 32, 18 to 24),
            tiles(6),
        )
        assertEquals(
            listOf(118 to 202, 99 to 185, 141 to 217, 82 to 178, 120 to 194, 256 to 0, 256 to 511, 511 to 256, 0 to 256, 146 to 195),
            tiles(9),
        )
    }

    @Test
    fun `poles clamp to Web Mercator's edge and the antimeridian to the last column`() {
        val north = WifiTiles.tileOf(89.0, 0.0, 9)
        assertEquals(0, north.y)
        assertEquals(511, WifiTiles.tileOf(-89.0, 0.0, 9).y)
        assertEquals(511, WifiTiles.tileOf(0.0, 180.0, 9).x)
        assertEquals(0, WifiTiles.tileOf(0.0, -180.0, 9).x)
    }

    @Test
    fun `the cluster zoom follows the map two zooms finer, within the pack's 3 to 9`() {
        assertEquals(3, WifiTiles.clusterZoom(0.0))
        assertEquals(3, WifiTiles.clusterZoom(1.9))
        assertEquals(5, WifiTiles.clusterZoom(3.0))
        assertEquals(5, WifiTiles.clusterZoom(3.99))
        assertEquals(9, WifiTiles.clusterZoom(7.0))
        assertEquals(9, WifiTiles.clusterZoom(9.9))
    }

    @Test
    fun `the cluster zoom stops at the pack's own last zoom`() {
        val to9 = 3..9
        val to11 = 3..11
        // A 3 to 9 pack behaves as it always did.
        assertEquals(9, WifiTiles.clusterZoom(7.0, to9))
        assertEquals(9, WifiTiles.clusterZoom(9.99, to9))
        // A 3 to 11 pack goes on two zooms finer than the map up to map zoom 9.99.
        assertEquals(9, WifiTiles.clusterZoom(7.5, to11))
        assertEquals(10, WifiTiles.clusterZoom(8.2, to11))
        assertEquals(11, WifiTiles.clusterZoom(9.0, to11))
        assertEquals(11, WifiTiles.clusterZoom(9.99, to11))
        // Low zooms still start at the pack's first.
        assertEquals(3, WifiTiles.clusterZoom(0.5, to11))
        assertEquals(5, WifiTiles.clusterZoom(0.5, 5..11))
        // The default is the first builds' range.
        assertEquals(WifiTiles.clusterZoom(9.99, to9), WifiTiles.clusterZoom(9.99))
    }

    @Test
    fun `the pack's cluster zooms come from its table, and an empty one has none`() {
        assertEquals(3..9, WifiTiles.zoomsOf(3, 9))
        assertEquals(3..11, WifiTiles.zoomsOf(3, 11))
        // MIN and MAX of an empty table are NULL: nothing to draw below the zoom of single spots.
        assertEquals(null, WifiTiles.zoomsOf(null, null))
        assertEquals(null, WifiTiles.zoomsOf(5, 2))
        assertEquals(0..24, WifiTiles.zoomsOf(-3, 99))
    }

    @Test
    fun `a box's tile range runs from its north-west corner to its south-east one`() {
        // A box around the zoom-9 tile (120, 194): the tile of its corners and everything between.
        val box = NearbyBox(south = 38.0, north = 40.0, west = -96.5, east = -94.5)
        val (xs, ys) = WifiTiles.range(box, 9)
        assertEquals(WifiTiles.tileOf(40.0, -96.5, 9).x, xs.first)
        assertEquals(WifiTiles.tileOf(38.0, -94.5, 9).x, xs.last)
        assertEquals(WifiTiles.tileOf(40.0, -96.5, 9).y, ys.first)
        assertEquals(WifiTiles.tileOf(38.0, -94.5, 9).y, ys.last)
        // The tile holding the middle of the box is inside the range.
        val middle = WifiTiles.tileOf(39.0, -95.5, 9)
        assertTrue(middle.x in xs && middle.y in ys)
        // North is the smaller y.
        assertTrue(ys.first <= ys.last)
    }

    @Test
    fun `the cells box covers every tile the box touches, whole`() {
        val box = NearbyBox(south = 39.2, north = 39.7, west = -95.9, east = -95.2)
        for (zoom in 3..11) {
            val cells = WifiTiles.cellsBox(box, zoom)
            assertTrue(cells.south < box.south && cells.north > box.north && cells.west < box.west && cells.east > box.east)
            // Its corners, just inside, are in the first and last tile of the box's range, and just outside are not.
            val (xs, ys) = WifiTiles.range(box, zoom)
            val e = 1e-5
            assertEquals(WifiTiles.Tile(xs.first, ys.first), WifiTiles.tileOf(cells.north - e, cells.west + e, zoom))
            assertEquals(WifiTiles.Tile(xs.last, ys.last), WifiTiles.tileOf(cells.south + e, cells.east - e, zoom))
            assertEquals(xs.first - 1, WifiTiles.tileOf(cells.north - e, cells.west - e, zoom).x)
            assertEquals(ys.last + 1, WifiTiles.tileOf(cells.south - e, cells.east + e, zoom).y)
        }
    }

    @Test
    fun `the cells box at the edge of the map stays on the map`() {
        val cells = WifiTiles.cellsBox(NearbyBox(south = 80.0, north = 89.0, west = -10.0, east = 10.0), 5)
        assertTrue(cells.north < 85.06)
    }
}
