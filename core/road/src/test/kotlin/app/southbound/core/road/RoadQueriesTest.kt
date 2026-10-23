package app.southbound.core.road

import app.southbound.core.search.LatLon
import app.southbound.core.search.NearbyBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadQueriesTest {

    private val box = NearbyBox(south = 39.0, north = 40.0, west = -96.0, east = -95.0)
    private val camp = setOf(RoadCategory.CAMP, RoadCategory.FUEL)
    private val all = RoadCategory.entries.toSet()

    @Test
    fun `the R-tree is used only when the build lists it`() {
        assertTrue(RoadQueries.hasRtreeModule(listOf("ENABLE_FTS5", "ENABLE_RTREE", "THREADSAFE=2")))
        assertTrue(RoadQueries.hasRtreeModule(listOf("enable_rtree")))
        assertTrue(RoadQueries.hasRtreeModule(listOf("ENABLE_RTREE=1")))
        assertFalse(RoadQueries.hasRtreeModule(listOf("ENABLE_FTS5", "ENABLE_JSON1", "THREADSAFE=2")))
        assertFalse(RoadQueries.hasRtreeModule(emptyList()))
        assertFalse(RoadQueries.hasRtreeModule(listOf("ENABLE_RTREEX", "OMIT_RTREE")))
    }

    @Test
    fun `the pack's identity is SBR1 version 1 and its R-tree table is road_rtree`() {
        assertEquals(0x53425231L, RoadQueries.APPLICATION_ID)
        assertEquals("SBR1", String(byteArrayOf(0x53, 0x42, 0x52, 0x31)))
        assertEquals(1L, RoadQueries.MIN_USER_VERSION)
        assertTrue(RoadQueries.HAS_RTREE_TABLE.contains("'road_rtree'"))
        assertEquals("s.id, s.lat, s.lon, s.category, s.name, s.brand, s.extra", RoadQueries.SPOT_COLUMNS)
    }

    @Test
    fun `the category condition lists the codes in the table's order and is left out for all sixteen`() {
        assertEquals("s.category IN ('camp', 'fuel')", RoadQueries.categoryCondition(setOf(RoadCategory.FUEL, RoadCategory.CAMP)))
        assertNull(RoadQueries.categoryCondition(all))
        assertEquals("category IN ('park')", RoadQueries.categoryCondition(setOf(RoadCategory.PARK), "category"))
    }

    @Test
    fun `a box without the R-tree compares lat and lon, the rarer kinds first`() {
        val q = RoadQueries.inBox(box, camp, 2000, rtree = false)!!
        assertTrue(q.sql, q.sql.startsWith("SELECT ${RoadQueries.SPOT_COLUMNS} FROM spot s WHERE s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ? AND s.category IN ('camp', 'fuel') ORDER BY CASE s.category WHEN 'camp' THEN 0 WHEN 'rv' THEN 1"))
        assertTrue(q.sql, q.sql.endsWith("WHEN 'park' THEN 15 ELSE 16 END, s.id LIMIT 2000"))
        assertEquals(listOf(39.0, 40.0, -96.0, -95.0), q.args)
        assertEquals(q.sql.count { it == '?' }, q.args.size)
    }

    @Test
    fun `a box with the R-tree joins it and still compares exactly`() {
        val q = RoadQueries.inBox(box, all, 500, rtree = true)!!
        assertTrue(
            q.sql,
            q.sql.startsWith(
                "SELECT ${RoadQueries.SPOT_COLUMNS} FROM road_rtree r CROSS JOIN spot s ON s.id = r.id" +
                    " WHERE r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ?" +
                    " AND s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ? ORDER BY CASE",
            ),
        )
        // All sixteen: no category condition at all.
        assertFalse(q.sql.contains("s.category IN"))
        val t = RoadQueries.RTREE_TOLERANCE
        // The R-tree is asked with a box widened a little; the exact comparisons use the box itself.
        assertEquals(listOf(39.0 - t, 40.0 + t, -96.0 - t, -95.0 + t, 39.0, 40.0, -96.0, -95.0), q.args)
        assertEquals(q.sql.count { it == '?' }, q.args.size)
    }

    @Test
    fun `no category or no room asks for nothing`() {
        assertNull(RoadQueries.inBox(box, emptySet(), 100, rtree = false))
        assertNull(RoadQueries.inBox(box, all, 0, rtree = false))
        assertNull(RoadQueries.nearest(LatLon(39.5, -95.5), 48.28, emptySet(), 80, rtree = false))
        assertNull(RoadQueries.nearest(LatLon(39.5, -95.5), 48.28, all, 0, rtree = false))
        assertNull(RoadQueries.clusters(9, box, emptySet()))
    }

    @Test
    fun `a limit is capped`() {
        val q = RoadQueries.inBox(box, all, 10_000_000, rtree = false)!!
        assertTrue(q.sql.endsWith("LIMIT ${RoadQueries.MAX_SCAN_ROWS}"))
        val n = RoadQueries.nearest(LatLon(39.5, -95.5), 10.0, all, 10_000_000, rtree = false)!!
        assertTrue(n.sql.endsWith("LIMIT ${RoadQueries.MAX_ROWS}"))
    }

    @Test
    fun `the nearest query orders by flat-earth distance with the longitude scaled`() {
        val center = LatLon(60.0, -100.0)
        val q = RoadQueries.nearest(center, 48.28, setOf(RoadCategory.WATER), 80, rtree = false)!!
        assertTrue(q.sql, q.sql.contains("WHERE s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ? AND s.category IN ('water')"))
        assertTrue(q.sql, q.sql.endsWith(" ORDER BY (s.lat - ?) * (s.lat - ?) + (s.lon - ?) * (s.lon - ?) * ? LIMIT 80"))
        assertEquals(q.sql.count { it == '?' }, q.args.size)
        val around = NearbyBox.around(center, 48.28)
        assertEquals(listOf(around.south, around.north, around.west, around.east), q.args.take(4))
        // cos(60 degrees) squared is a quarter.
        assertEquals(listOf(60.0, 60.0, -100.0, -100.0), q.args.subList(4, 8))
        assertEquals(0.25, q.args[8] as Double, 1e-9)
    }

    @Test
    fun `the nearest query with the R-tree binds the box twice, then the centre`() {
        val q = RoadQueries.nearest(LatLon(39.5, -95.5), 10.0, all, 40, rtree = true)!!
        assertTrue(q.sql.contains("FROM road_rtree r CROSS JOIN spot s ON s.id = r.id WHERE r.maxlat >= ?"))
        assertEquals(q.sql.count { it == '?' }, q.args.size)
        assertEquals(13, q.args.size)
    }

    @Test
    fun `the cluster query selects one zoom's cells in the box's tile range for the categories`() {
        val q = RoadQueries.clusters(9, box, camp)!!
        assertEquals(
            "SELECT zoom, x, y, category, count, lat, lon FROM cluster" +
                " WHERE zoom = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND category IN ('camp', 'fuel') LIMIT ${RoadQueries.MAX_CLUSTERS}",
            q.sql,
        )
        val (xs, ys) = RoadTiles.range(box, 9)
        assertEquals(listOf(9L, xs.first.toLong(), xs.last.toLong(), ys.first.toLong(), ys.last.toLong()), q.args)
        assertEquals(q.sql.count { it == '?' }, q.args.size)
        assertFalse(RoadQueries.clusters(9, box, all)!!.sql.contains("category IN"))
    }

    @Test
    fun `the spot query binds the id and the same-spot query the exact position`() {
        val q = RoadQueries.spot(42)
        assertEquals("SELECT ${RoadQueries.SPOT_COLUMNS} FROM spot s WHERE s.id = ?", q.sql)
        assertEquals(listOf<Any>(42L), q.args)
        val same = RoadQueries.sameSpot(39.5, -95.5)
        assertEquals(
            "SELECT ${RoadQueries.SPOT_COLUMNS} FROM spot s WHERE s.lat = ? AND s.lon = ? ORDER BY s.id LIMIT ${RoadQueries.MAX_SAME_SPOT}",
            same.sql,
        )
        assertEquals(listOf<Any>(39.5, -95.5), same.args)
    }

    @Test
    fun `no statement splices anything but the enum's codes`() {
        // The category literals are the only text built into SQL; every position and id is a bound argument.
        val sql = RoadQueries.inBox(box, all - RoadCategory.PARK, 10, rtree = true)!!.sql +
            RoadQueries.nearest(LatLon(1.0, 2.0), 5.0, camp, 5, rtree = false)!!.sql + RoadQueries.clusters(5, box, camp)!!.sql
        assertFalse(sql.contains(Regex("[0-9]\\.[0-9]")))
        assertFalse(sql.contains("-95"))
    }
}
