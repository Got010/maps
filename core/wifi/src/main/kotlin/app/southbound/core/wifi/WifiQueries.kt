package app.southbound.core.wifi

import app.southbound.core.search.LatLon
import app.southbound.core.search.NearbyBox
import kotlin.math.cos

/** SQL text and arguments, apart from the driver, so the statements can be tested on the JVM. */
internal object WifiQueries {

    /** A statement and what to bind to its `?`s in order: Double, Long or String. */
    class Query(val sql: String, val args: List<Any>)

    const val APPLICATION_ID = 0x53425731L

    /** The pack's `user_version` this reader was written for; a pack numbered lower than 1 isn't one. */
    const val MIN_USER_VERSION = 1L

    const val SPOT_COLUMNS =
        "s.id, s.osm_type, s.osm_id, s.name, s.brand_wikidata, s.lat, s.lon, s.category, s.tier, s.fee, s.opening_hours, s.website"

    const val HAS_RTREE_TABLE = "SELECT 1 FROM sqlite_master WHERE name = 'wifi_rtree'"

    const val COMPILE_OPTIONS = "PRAGMA compile_options"

    /**
     * Whether the bundled SQLite was built with the R-tree module: `PRAGMA compile_options` lists `ENABLE_RTREE`
     * (with a value, `ENABLE_RTREE=1`, in some builds). The table in the pack is only used when this is true too.
     */
    fun hasRtreeModule(compileOptions: Collection<String>): Boolean =
        compileOptions.any { it.equals("ENABLE_RTREE", ignoreCase = true) || it.startsWith("ENABLE_RTREE=", ignoreCase = true) }

    /** `tier IN (1, 2)`, or null when every tier is wanted. [tiers] must not be empty. */
    private fun tierCondition(tiers: Set<WifiTier>): String? =
        if (tiers.size >= WifiTier.entries.size) null else tiers.map { it.code }.sorted().joinToString(", ", "s.tier IN (", ")")

    /** The spots inside [box], the best tiers first when [limit] cuts them. Null when no tier is wanted. */
    fun inBox(box: NearbyBox, tiers: Set<WifiTier>, limit: Int, rtree: Boolean): Query? {
        if (tiers.isEmpty() || limit <= 0) return null
        val (from, where, args) = boxClauses(box, rtree)
        val conditions = listOfNotNull(where, tierCondition(tiers))
        return Query(
            "SELECT $SPOT_COLUMNS FROM $from WHERE ${conditions.joinToString(" AND ")} ORDER BY s.tier, s.id LIMIT ${limit.coerceAtMost(MAX_ROWS)}",
            args,
        )
    }

    /**
     * The [rows] spots nearest [center] inside the square of half-side [radiusKm], nearest first by a flat-earth
     * distance (no math functions, which not every SQLite build has), for the caller to measure properly and cut
     * to the circle. Null when no tier is wanted.
     */
    fun nearest(center: LatLon, radiusKm: Double, tiers: Set<WifiTier>, rows: Int, rtree: Boolean): Query? {
        if (tiers.isEmpty() || rows <= 0) return null
        val box = NearbyBox.around(center, radiusKm)
        val (from, where, boxArgs) = boxClauses(box, rtree)
        // A degree of longitude is this much of one of latitude here, squared.
        val lonScale = cos(Math.toRadians(center.lat)).let { it * it }
        val conditions = listOfNotNull(where, tierCondition(tiers))
        return Query(
            "SELECT $SPOT_COLUMNS FROM $from WHERE ${conditions.joinToString(" AND ")}" +
                " ORDER BY (s.lat - ?) * (s.lat - ?) + (s.lon - ?) * (s.lon - ?) * ? LIMIT ${rows.coerceAtMost(MAX_ROWS)}",
            boxArgs + listOf(center.lat, center.lat, center.lon, center.lon, lonScale),
        )
    }

    fun spot(id: Long): Query = Query("SELECT $SPOT_COLUMNS FROM spot s WHERE s.id = ?", listOf(id))

    /** The cluster cells of [zoom] the [box] touches. */
    fun clusters(zoom: Int, box: NearbyBox): Query {
        val (xs, ys) = WifiTiles.range(box, zoom)
        return Query(
            "SELECT zoom, x, y, count, lat, lon, tier_min FROM cluster" +
                " WHERE zoom = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? LIMIT $MAX_CLUSTERS",
            listOf(zoom.toLong(), xs.first.toLong(), xs.last.toLong(), ys.first.toLong(), ys.last.toLong()),
        )
    }

    private data class Clauses(val from: String, val where: String, val args: List<Any>)

    /**
     * The FROM and WHERE for a box. With the R-tree, its index finds the candidates and the exact comparisons
     * follow (the R-tree stores 32-bit floats and rounds outwards); without it, the (lat, lon) index does.
     */
    private fun boxClauses(box: NearbyBox, rtree: Boolean): Clauses {
        val exact = "s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ?"
        val exactArgs = listOf(box.south, box.north, box.west, box.east)
        return if (rtree) {
            Clauses(
                "spot s JOIN wifi_rtree r ON r.id = s.id",
                "r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ? AND $exact",
                listOf(box.south, box.north, box.west, box.east) + exactArgs,
            )
        } else {
            Clauses("spot s", exact, exactArgs)
        }
    }

    /** No more than this many rows from one query, whatever the caller asks. */
    const val MAX_ROWS = 20_000

    /** Cells of one zoom on a screen: a handful; this only bounds a bad box. */
    const val MAX_CLUSTERS = 2_000
}
