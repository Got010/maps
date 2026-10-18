package app.southbound.core.wifi

import app.southbound.core.search.LatLon
import app.southbound.core.search.NearbyBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQueriesTest {

    private val box = NearbyBox(south = 39.0, north = 40.0, west = -96.0, east = -95.0)
    private val all = WifiTier.entries.toSet()

    @Test
    fun `the R-tree is used only when the build lists it`() {
        assertTrue(WifiQueries.hasRtreeModule(listOf("ENABLE_FTS5", "ENABLE_RTREE", "THREADSAFE=2")))
        assertTrue(WifiQueries.hasRtreeModule(listOf("enable_rtree")))
        assertTrue(WifiQueries.hasRtreeModule(listOf("ENABLE_RTREE=1")))
        assertFalse(WifiQueries.hasRtreeModule(listOf("ENABLE_FTS5", "ENABLE_JSON1", "THREADSAFE=2")))
        assertFalse(WifiQueries.hasRtreeModule(emptyList()))
        // Not a prefix match for other options.
        assertFalse(WifiQueries.hasRtreeModule(listOf("ENABLE_RTREEX", "OMIT_RTREE")))
    }

    @Test
    fun `a box without the R-tree compares lat and lon, best tier first`() {
        val q = WifiQueries.inBox(box, all, 2000, rtree = false)!!
        assertEquals(
            "SELECT ${WifiQueries.SPOT_COLUMNS} FROM spot s WHERE s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ?" +
                " ORDER BY s.tier, s.id LIMIT 2000",
            q.sql,
        )
        assertEquals(listOf(39.0, 40.0, -96.0, -95.0), q.args)
        assertEquals(q.sql.count { it == '?' }, q.args.size)
    }

    @Test
    fun `a box with the R-tree joins it and still compares exactly`() {
        val q = WifiQueries.inBox(box, setOf(WifiTier.TAGGED_FREE, WifiTier.TAGGED_UNSTATED), 500, rtree = true)!!
        assertEquals(
            "SELECT ${WifiQueries.SPOT_COLUMNS} FROM wifi_rtree r CROSS JOIN spot s ON s.id = r.id" +
                " WHERE r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ?" +
                " AND s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ? AND s.tier IN (1, 2)" +
                " ORDER BY s.tier, s.id LIMIT 500",
            q.sql,
        )
        val t = WifiQueries.RTREE_TOLERANCE
        // The R-tree is asked with a box widened a little; the exact comparisons use the box itself.
        assertEquals(listOf(39.0 - t, 40.0 + t, -96.0 - t, -95.0 + t, 39.0, 40.0, -96.0, -95.0), q.args)
        assertEquals(q.sql.count { it == '?' }, q.args.size)
    }

    @Test
    fun `no tier or no room asks for nothing`() {
        assertNull(WifiQueries.inBox(box, emptySet(), 100, rtree = false))
        assertNull(WifiQueries.inBox(box, all, 0, rtree = false))
        assertNull(WifiQueries.nearest(LatLon(39.5, -95.5), 48.28, emptySet(), 80, rtree = false))
        assertNull(WifiQueries.nearest(LatLon(39.5, -95.5), 48.28, all, 0, rtree = false))
    }

    @Test
    fun `a limit is capped`() {
        val q = WifiQueries.inBox(box, all, 10_000_000, rtree = false)!!
        assertTrue(q.sql.endsWith("LIMIT ${WifiQueries.MAX_ROWS}"))
    }

    @Test
    fun `the tier condition lists the codes in order and is left out for all four`() {
        val some = WifiQueries.inBox(box, setOf(WifiTier.LIKELY, WifiTier.CHAIN), 10, rtree = false)!!
        assertTrue(some.sql, some.sql.contains("s.tier IN (3, 4)"))
        assertFalse(WifiQueries.inBox(box, all, 10, rtree = false)!!.sql.contains("s.tier IN"))
    }

    @Test
    fun `the nearest query orders by flat-earth distance with the longitude scaled`() {
        val center = LatLon(60.0, -100.0)
        val q = WifiQueries.nearest(center, 48.28, setOf(WifiTier.CHAIN), 80, rtree = false)!!
        assertTrue(q.sql, q.sql.contains("WHERE s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ? AND s.tier IN (3)"))
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
        val q = WifiQueries.nearest(LatLon(39.5, -95.5), 10.0, all, 40, rtree = true)!!
        assertTrue(q.sql.contains("FROM wifi_rtree r CROSS JOIN spot s ON s.id = r.id WHERE r.maxlat >= ?"))
        assertEquals(q.sql.count { it == '?' }, q.args.size)
        assertEquals(13, q.args.size)
    }

    @Test
    fun `the cluster query selects the cells of one zoom in the box's tile range`() {
        val q = WifiQueries.clusters(9, box)
        assertEquals(
            "SELECT zoom, x, y, count, lat, lon, tier_min FROM cluster" +
                " WHERE zoom = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? LIMIT ${WifiQueries.MAX_CLUSTERS}",
            q.sql,
        )
        val (xs, ys) = WifiTiles.range(box, 9)
        assertEquals(listOf(9L, xs.first.toLong(), xs.last.toLong(), ys.first.toLong(), ys.last.toLong()), q.args)
        assertEquals(q.sql.count { it == '?' }, q.args.size)
    }

    @Test
    fun `the spot query binds the id`() {
        val q = WifiQueries.spot(42)
        assertEquals("SELECT ${WifiQueries.SPOT_COLUMNS} FROM spot s WHERE s.id = ?", q.sql)
        assertEquals(listOf<Any>(42L), q.args)
    }

    @Test
    fun `the application id is SBW1`() {
        assertEquals(0x53425731L, WifiQueries.APPLICATION_ID)
        assertEquals("SBW1", String(byteArrayOf(0x53, 0x42, 0x57, 0x31)))
    }
}
