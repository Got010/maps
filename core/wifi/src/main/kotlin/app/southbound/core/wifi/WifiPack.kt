package app.southbound.core.wifi

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import app.southbound.core.search.LatLon
import app.southbound.core.search.NearbyBox
import java.io.File
import java.time.LocalDateTime

/**
 * Reads the free Wi-Fi pack (wifi-us.sqlite) on the device: spots in a box for the map, the nearest spots for the
 * list, the precomputed cluster cells for the map's low zooms. Nothing here touches the network, and nothing is
 * logged.
 *
 * Uses the same bundled SQLite as the search pack. When that build has the R-tree module and the pack has its
 * `wifi_rtree` table, boxes are found through it; otherwise through the (lat, lon) indexes, as the search pack's
 * landmark lookups are. A read that fails answers with nothing (the map is fine without the layer), never throws.
 */
class WifiPack private constructor(private val connection: SQLiteConnection) : AutoCloseable {

    // What the pack and the driver have is read once, as it opens, on the opening thread (see SearchIndex).
    private val rtreeAvailable: Boolean = run {
        val options = try {
            connection.prepare(WifiQueries.COMPILE_OPTIONS).use { stmt -> buildList { while (stmt.step()) add(stmt.getText(0)) } }
        } catch (e: Exception) {
            emptyList()
        }
        WifiQueries.hasRtreeModule(options) &&
            try {
                connection.prepare(WifiQueries.HAS_RTREE_TABLE).use { it.step() }
            } catch (e: Exception) {
                false
            }
    }

    @Volatile
    private var rtreeFailed = false

    /** True when boxes are found through the R-tree: the module is compiled in and the pack has the table. */
    val usesRtree: Boolean
        get() = rtreeAvailable && !rtreeFailed

    /**
     * The zooms the pack's `cluster` table has (3 to 9 in the first builds, 3 to 11 after), read once as it opens;
     * null when the table is empty or can't be read, and then nothing is drawn below the zoom of single spots.
     */
    val clusterZooms: IntRange? = try {
        connection.prepare("SELECT MIN(zoom), MAX(zoom) FROM cluster").use { stmt ->
            if (stmt.step()) {
                WifiTiles.zoomsOf(if (stmt.isNull(0)) null else stmt.getLong(0), if (stmt.isNull(1)) null else stmt.getLong(1))
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** The cluster zoom to ask for at a map zoom of [mapZoom], or null when the pack has no cells. */
    fun clusterZoomFor(mapZoom: Double): Int? = clusterZooms?.let { WifiTiles.clusterZoom(mapZoom, it) }

    /** The date of the OpenStreetMap extract the pack was built from (YYYY-MM-DD), when it says. */
    val extractDate: String? = try {
        connection.prepare("SELECT value FROM meta WHERE key = 'extract_date'").use { if (it.step() && !it.isNull(0)) it.getText(0) else null }
    } catch (e: Exception) {
        null
    }

    /**
     * The spots inside [box] of one of [tiers] (at most [limit], the better tiers first when the box holds more),
     * for the map from the zoom where single spots are drawn.
     */
    fun inBox(box: NearbyBox, tiers: Set<WifiTier>, limit: Int): List<WifiSpot> =
        read { rtree -> WifiQueries.inBox(box, tiers, limit, rtree) }

    /**
     * The spots of one of [tiers] within [radiusKm] of [center], nearest first, at most [limit]. With [openNow]
     * only those open at [now]; a spot whose hours aren't known stays when [hoursUnknownCounts] (the default: most
     * spots carry no hours, and dropping them would empty the list; pass false to keep only spots known to be open).
     */
    fun nearest(
        center: LatLon,
        radiusKm: Double,
        tiers: Set<WifiTier>,
        openNow: Boolean,
        now: LocalDateTime,
        limit: Int,
        hoursUnknownCounts: Boolean = true,
    ): List<WifiResult> {
        val rows = WifiResults.candidateRows(limit, openNow)
        val found = read { rtree -> WifiQueries.nearest(center, radiusKm, tiers, rows, rtree) }
        return WifiResults.select(found, center, radiusKm * 1000, tiers, openNow, now, hoursUnknownCounts, limit)
    }

    /** The cluster cells of [zoom] (one of [clusterZooms], see [clusterZoomFor]) the [box] touches. */
    fun clusters(zoom: Int, box: NearbyBox): List<WifiCluster> {
        if (clusterZooms?.contains(zoom) != true) return emptyList()
        val query = WifiQueries.clusters(zoom, box)
        return try {
            synchronized(connection) {
                connection.prepare(query.sql).use { stmt ->
                    stmt.bindAll(query.args)
                    buildList {
                        while (stmt.step()) {
                            val tier = WifiTier.ofClusterMin(stmt.getInt(6))
                            add(WifiCluster(stmt.getInt(0), stmt.getInt(1), stmt.getInt(2), stmt.getInt(3), stmt.getDouble(4), stmt.getDouble(5), tier))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The spot with this pack id, e.g. to open a tapped pin; null when it isn't there. */
    fun spot(id: Long): WifiSpot? {
        val query = WifiQueries.spot(id)
        return try {
            synchronized(connection) {
                connection.prepare(query.sql).use { stmt ->
                    stmt.bindAll(query.args)
                    if (stmt.step()) stmt.readSpot() else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        synchronized(connection) { connection.close() }
    }

    /** Runs the spot query [build] makes (given whether to use the R-tree), falling back to the B-tree if the R-tree read fails. */
    private fun read(build: (rtree: Boolean) -> WifiQueries.Query?): List<WifiSpot> {
        val rtree = usesRtree
        return try {
            rows(build(rtree) ?: return emptyList())
        } catch (e: Exception) {
            if (!rtree) return emptyList()
            // The module or the table is not what it was said to be: this pack is read without it from now on.
            rtreeFailed = true
            try {
                rows(build(false) ?: return emptyList())
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    private fun rows(q: WifiQueries.Query): List<WifiSpot> = synchronized(connection) {
        connection.prepare(q.sql).use { stmt ->
            stmt.bindAll(q.args)
            buildList { while (stmt.step()) stmt.readSpot()?.let { add(it) } }
        }
    }

    companion object {
        /**
         * Opens [file] read-only. Throws if it isn't a Wi-Fi pack; the file is never changed or deleted here (a pack
         * that can't be opened stays on the phone, and Settings says so).
         */
        fun open(file: File): WifiPack {
            val connection = BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY)
            try {
                val appId = connection.prepare("PRAGMA application_id").use { if (it.step()) it.getLong(0) else 0L }
                require(appId == WifiQueries.APPLICATION_ID) { "${file.name} is not a Wi-Fi pack" }
                val version = connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0L }
                require(version >= WifiQueries.MIN_USER_VERSION) { "${file.name} is not a Wi-Fi pack" }
                return WifiPack(connection)
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

        /** A row of [WifiQueries.SPOT_COLUMNS]; null for a tier this app doesn't know (a newer pack's), never shown as confirmed. */
        private fun SQLiteStatement.readSpot(): WifiSpot? {
            val tier = WifiTier.of(getInt(8)) ?: return null
            return WifiSpot(
                id = getLong(0),
                osmType = getText(1),
                osmId = getLong(2),
                name = textOrNull(3),
                brandWikidata = textOrNull(4),
                lat = getDouble(5),
                lon = getDouble(6),
                category = getText(7),
                tier = tier,
                fee = WifiFee.of(textOrNull(9)),
                openingHours = textOrNull(10),
                website = textOrNull(11),
            )
        }
    }
}
