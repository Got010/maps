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
}
