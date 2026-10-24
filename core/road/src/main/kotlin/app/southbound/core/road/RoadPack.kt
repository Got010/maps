package app.southbound.core.road

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import app.southbound.core.search.LatLon
import app.southbound.core.search.NearbyBox
import java.io.File

/**
 * Reads the Road pack (road-us.sqlite) on the device: spots in a box for the map, the nearest spots for the list, the
 * spots beside a route, the precomputed cluster cells for the map's low zooms. Nothing here touches the network, and
 * nothing is logged: the position and the route stay in the arguments.
 *
 * Uses the same bundled SQLite as the search and Wi-Fi packs. When that build has the R-tree module and the pack has
 * its `road_rtree` table, boxes are found through it; otherwise through the (lat, lon) indexes. A read that fails
 * answers with nothing (the map is fine without the layer), never throws.
 */
class RoadPack private constructor(private val connection: SQLiteConnection) : AutoCloseable {

    // What the pack and the driver have is read once, as it opens, on the opening thread (see WifiPack).
    private val rtreeAvailable: Boolean = run {
        val options = try {
            connection.prepare(RoadQueries.COMPILE_OPTIONS).use { stmt -> buildList { while (stmt.step()) add(stmt.getText(0)) } }
        } catch (e: Exception) {
            emptyList()
        }
        RoadQueries.hasRtreeModule(options) &&
            try {
                connection.prepare(RoadQueries.HAS_RTREE_TABLE).use { it.step() }
            } catch (e: Exception) {
                false
            }
    }

    @Volatile
    private var rtreeFailed = false

    /** True when boxes are found through the R-tree: the module is compiled in and the pack has the table. */
    val usesRtree: Boolean
        get() = rtreeAvailable && !rtreeFailed

    /** The zooms the pack's `cluster` table has (3 to 11), read once as it opens; null when the table is empty or can't be read. */
    val clusterZooms: IntRange? = try {
        connection.prepare("SELECT MIN(zoom), MAX(zoom) FROM cluster").use { stmt ->
            if (stmt.step()) {
                RoadTiles.zoomsOf(if (stmt.isNull(0)) null else stmt.getLong(0), if (stmt.isNull(1)) null else stmt.getLong(1))
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** The cluster zoom to ask for at a map zoom of [mapZoom], or null when the pack has no cells. */
    fun clusterZoomFor(mapZoom: Double): Int? = clusterZooms?.let { RoadTiles.clusterZoom(mapZoom, it) }

    /** The date of the OpenStreetMap extract the pack was built from (YYYY-MM-DD), when it says. */
    val extractDate: String? = try {
        connection.prepare("SELECT value FROM meta WHERE key = 'extract_date'").use { if (it.step() && !it.isNull(0)) it.getText(0) else null }
    } catch (e: Exception) {
        null
    }

    /**
     * The spots of [categories] inside [box] (each place once, at most [limit]; the rarer kinds first when the box
     * holds more), for the map from the zoom where single spots are drawn. [limit] is capped at
     * [RoadQueries.MAX_SCAN_ROWS].
     */
    fun inBox(box: NearbyBox, categories: Set<RoadCategory>, limit: Int): List<RoadSpot> {
        val rows = read({ rtree -> RoadQueries.inBox(box, categories, limit + limit / DUPLICATE_SLACK_DIVISOR, rtree) }) { it.readSpot() }
        return RoadPlaces.distinct(rows).take(limit.coerceAtLeast(0))
    }

    /**
     * The spots of [categories] within [radiusKm] of [center], nearest first, each place once, at most [limit].
     */
    fun nearest(center: LatLon, radiusKm: Double, categories: Set<RoadCategory>, limit: Int): List<RoadResult> {
        val rows = RoadResults.candidateRows(limit)
        val found = read({ rtree -> RoadQueries.nearest(center, radiusKm, categories, rows, rtree) }) { it.readSpot() }
        return RoadResults.select(found, center, radiusKm * 1000, limit)
    }

    /**
     * The spots of [categories] within [corridorKm] of the route [polyline] (the route's points in order), in the
     * order the driver meets them, at most [limit]; only those at least [aheadMeters] along the route, when the driver
     * is part-way along it (the default, 0, lists from the start). Each place once. The route is not sent anywhere:
     * the pack is read in boxes round its pieces and the distances are worked out here ([RoadAlong]). [isActive] is
     * asked between pieces so a cancelled caller stops the reading.
     */
    fun alongRoute(
        polyline: List<LatLon>,
        corridorKm: Double,
        categories: Set<RoadCategory>,
        limit: Int,
        aheadMeters: Double = 0.0,
        isActive: () -> Boolean = { true },
    ): List<RoadAlongResult> {
        if (categories.isEmpty()) return emptyList()
        return RoadAlong.search(RoadAlong.Route(polyline), corridorKm * 1000, aheadMeters, limit, fetch = { box ->
            read({ rtree -> RoadQueries.inBox(box, categories, RoadAlong.CHUNK_ROWS, rtree) }) { it.readSpot() }
        }, isActive = isActive)
    }

    /**
     * The cluster cells of [zoom] (one of [clusterZooms], see [clusterZoomFor]) the [box] touches, summed over
     * [categories]: each cell is the count of spots in the tile and their mean position.
     */
    fun clusters(zoom: Int, box: NearbyBox, categories: Set<RoadCategory>): List<RoadCluster> {
        if (clusterZooms?.contains(zoom) != true) return emptyList()
        val query = RoadQueries.clusters(zoom, box, categories) ?: return emptyList()
        return try {
            synchronized(connection) {
                connection.prepare(query.sql).use { stmt ->
                    stmt.bindAll(query.args)
                    val rows = buildList {
                        while (stmt.step()) {
                            val category = RoadCategory.of(stmt.getText(3)) ?: continue
                            add(RoadClusters.Row(stmt.getInt(0), stmt.getInt(1), stmt.getInt(2), category, stmt.getInt(4), stmt.getDouble(5), stmt.getDouble(6)))
                        }
                    }
                    RoadClusters.merge(rows, categories)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The spot with this pack id, e.g. to open a tapped pin; null when it isn't there. The place's other rows (its
     * pumps and its truck stop) are folded in, so the sheet has the facts of both.
     */
    fun spot(id: Long): RoadSpot? {
        val query = RoadQueries.spot(id)
        return try {
            synchronized(connection) {
                val one = connection.prepare(query.sql).use { stmt ->
                    stmt.bindAll(query.args)
                    if (stmt.step()) stmt.readSpot() else null
                } ?: return null
                val same = RoadQueries.sameSpot(one.lat, one.lon)
                val rows = connection.prepare(same.sql).use { stmt ->
                    stmt.bindAll(same.args)
                    buildList { while (stmt.step()) stmt.readSpot()?.let { add(it) } }
                }.filter { it.id == one.id || PlaceKey(it) == PlaceKey(one) }
                RoadPlaces.fold(rows.ifEmpty { listOf(one) })
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        synchronized(connection) { connection.close() }
    }

    /** Runs the spot query [build] makes (given whether to use the R-tree), falling back to the B-tree if the R-tree read fails. */
    private fun <T : Any> read(build: (rtree: Boolean) -> RoadQueries.Query?, row: (SQLiteStatement) -> T?): List<T> {
        val rtree = usesRtree
        return try {
            rows(build(rtree) ?: return emptyList(), row)
        } catch (e: Exception) {
            if (!rtree) return emptyList()
            // The module or the table is not what it was said to be: this pack is read without it from now on.
            rtreeFailed = true
            try {
                rows(build(false) ?: return emptyList(), row)
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    private fun <T : Any> rows(q: RoadQueries.Query, row: (SQLiteStatement) -> T?): List<T> = synchronized(connection) {
        connection.prepare(q.sql).use { stmt ->
            stmt.bindAll(q.args)
            buildList { while (stmt.step()) row(stmt)?.let { add(it) } }
        }
    }

    companion object {
        /** The most spots one box read returns. */
        const val SCAN_LIMIT = RoadQueries.MAX_SCAN_ROWS

        /** A tenth more rows than asked for are read, since a place filed twice folds into one. */
        private const val DUPLICATE_SLACK_DIVISOR = 10

        /**
         * Opens [file] read-only. Throws if it isn't a Road pack; the file is never changed or deleted here (a pack
         * that can't be opened stays on the phone, and Settings says so).
         */
        fun open(file: File): RoadPack {
            val connection = BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY)
            try {
                val appId = connection.prepare("PRAGMA application_id").use { if (it.step()) it.getLong(0) else 0L }
                require(appId == RoadQueries.APPLICATION_ID) { "${file.name} is not a Road pack" }
                val version = connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0L }
                require(version >= RoadQueries.MIN_USER_VERSION) { "${file.name} is not a Road pack" }
                return RoadPack(connection)
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }

        private fun SQLiteStatement.bindAll(args: List<Any>) {
            args.forEachIndexed { i, arg ->
                when (arg) {
                    is String -> bindText(i + 1, arg)
                    is Double -> bindDouble(i + 1, arg)
                    is Long -> bindLong(i + 1, arg)
                    else -> error("unexpected argument")
                }
            }
        }

        private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

        /** A row of [RoadQueries.SPOT_COLUMNS]; null for a category this app doesn't know (a newer pack's), never guessed. */
        private fun SQLiteStatement.readSpot(): RoadSpot? {
            val category = RoadCategory.of(getText(3)) ?: return null
            return RoadSpot(
                id = getLong(0),
                lat = getDouble(1),
                lon = getDouble(2),
                category = category,
                name = textOrNull(4),
                brand = textOrNull(5),
                extra = RoadExtra.parse(textOrNull(6)),
            )
        }
    }
}
