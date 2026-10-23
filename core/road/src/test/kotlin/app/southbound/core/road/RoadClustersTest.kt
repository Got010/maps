package app.southbound.core.road

import app.southbound.core.search.NearbyBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadClustersTest {

    private fun row(x: Int, y: Int, category: RoadCategory, count: Int, lat: Double, lon: Double, zoom: Int = 9) =
        RoadClusters.Row(zoom, x, y, category, count, lat, lon)

    @Test
    fun `rows of one tile are summed at the count-weighted mean`() {
        val cells = RoadClusters.merge(
            listOf(
                row(120, 194, RoadCategory.FUEL, 3, 39.50, -95.50),
                row(120, 194, RoadCategory.WATER, 1, 39.54, -95.54),
            ),
            RoadCategory.DEFAULT_ON,
        )
        assertEquals(1, cells.size)
        val c = cells.single()
        assertEquals(RoadCluster(9, 120, 194, 4, 39.51, -95.51), c)
    }

    @Test
    fun `only the shown categories count, and a cell with none is not drawn`() {
        val rows = listOf(
            row(1, 1, RoadCategory.FUEL, 5, 39.5, -95.5),
            row(1, 1, RoadCategory.LIBRARY, 7, 39.5, -95.5),
            row(2, 1, RoadCategory.LIBRARY, 2, 39.6, -95.6),
        )
        val cells = RoadClusters.merge(rows, RoadCategory.DEFAULT_ON)
        assertEquals(listOf(5), cells.map { it.count })
        assertEquals(listOf(7 + 5, 2), RoadClusters.merge(rows, RoadCategory.entries.toSet()).map { it.count })
        assertEquals(emptyList<RoadCluster>(), RoadClusters.merge(rows, emptySet()))
    }

    @Test
    fun `cells come out by column then row whatever the order of the rows`() {
        val rows = listOf(
            row(5, 2, RoadCategory.CAMP, 1, 1.0, 1.0),
            row(3, 9, RoadCategory.CAMP, 1, 1.0, 1.0),
            row(3, 4, RoadCategory.CAMP, 1, 1.0, 1.0),
        )
        val all = RoadCategory.entries.toSet()
        assertEquals(listOf(3 to 4, 3 to 9, 5 to 2), RoadClusters.merge(rows, all).map { it.x to it.y })
        assertEquals(RoadClusters.merge(rows, all), RoadClusters.merge(rows.reversed(), all))
    }

    @Test
    fun `an empty row and a row of another zoom do not blur a cell`() {
        val cells = RoadClusters.merge(
            listOf(
                row(1, 1, RoadCategory.FUEL, 0, 80.0, 80.0),
                row(1, 1, RoadCategory.FUEL, 2, 39.5, -95.5, zoom = 8),
                row(1, 1, RoadCategory.FUEL, 2, 39.7, -95.7, zoom = 9),
            ),
            RoadCategory.DEFAULT_ON,
        )
        assertEquals(listOf(8, 9), cells.map { it.zoom })
        assertEquals(listOf(39.5, 39.7), cells.map { it.lat })
    }

    @Test
    fun `the positions are rounded to six decimals like the pack's`() {
        val c = RoadClusters.merge(
            listOf(row(1, 1, RoadCategory.FUEL, 1, 39.1234567, -95.1234567), row(1, 1, RoadCategory.FUEL, 2, 39.1234568, -95.1234568)),
            RoadCategory.DEFAULT_ON,
        ).single()
        assertEquals(39.123457, c.lat, 0.0)
        assertEquals(-95.123457, c.lon, 0.0)
    }

    @Test
    fun `tiles agree with the pipeline's tile_of`() {
        // pipeline/road/build_road.py tile_of(39.5, -95.5, z), worked out with the Python for these zooms.
        assertEquals(RoadTiles.Tile(1, 3), RoadTiles.tileOf(39.5, -95.5, 3))
        assertEquals(RoadTiles.Tile(120, 194), RoadTiles.tileOf(39.5, -95.5, 9))
        assertEquals(RoadTiles.Tile(480, 779), RoadTiles.tileOf(39.5, -95.5, 11))
        // The poles and the date line clamp to the grid.
        assertEquals(RoadTiles.Tile(0, 0), RoadTiles.tileOf(89.0, -181.0, 4))
        assertEquals(RoadTiles.Tile(15, 15), RoadTiles.tileOf(-89.0, 181.0, 4))
    }

    @Test
    fun `the zoom to ask for is two finer than the map within the pack's zooms`() {
        assertEquals(11, RoadTiles.clusterZoom(9.4, 3..11))
        assertEquals(11, RoadTiles.clusterZoom(10.0, 3..11))
        assertEquals(5, RoadTiles.clusterZoom(3.0, 3..11))
        assertEquals(3, RoadTiles.clusterZoom(0.5, 3..11))
        assertEquals(9, RoadTiles.clusterZoom(9.0, 3..9))
        assertEquals(3..11, RoadTiles.zoomsOf(3, 11))
        assertNull(RoadTiles.zoomsOf(null, null))
        assertNull(RoadTiles.zoomsOf(11, 3))
        assertEquals(3..24, RoadTiles.zoomsOf(3, 99))
    }

    @Test
    fun `a box's tile range spans its corners`() {
        val (xs, ys) = RoadTiles.range(NearbyBox(south = 39.0, north = 40.0, west = -96.0, east = -95.0), 9)
        assertTrue(xs.first <= xs.last && ys.first <= ys.last)
        val nw = RoadTiles.tileOf(40.0, -96.0, 9)
        val se = RoadTiles.tileOf(39.0, -95.0, 9)
        assertEquals(nw.x..se.x, xs)
        assertEquals(nw.y..se.y, ys)
    }
}
