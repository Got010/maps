package app.southbound.core.road

import app.southbound.core.search.LatLon
import app.southbound.core.search.NearbyBox
import kotlin.math.cos

/** SQL text and arguments, apart from the driver, so the statements can be tested on the JVM. */
internal object RoadQueries {

    /** A statement and what to bind to its `?`s in order: Double, Long or String. */
    class Query(val sql: String, val args: List<Any>)

    const val APPLICATION_ID = 0x53425231L

    /** The pack's `user_version` this reader was written for; a pack numbered lower than 1 isn't one. */
    const val MIN_USER_VERSION = 1L

    const val SPOT_COLUMNS = "s.id, s.lat, s.lon, s.category, s.name, s.brand, s.extra"

    const val HAS_RTREE_TABLE = "SELECT 1 FROM sqlite_master WHERE name = 'road_rtree'"

    const val COMPILE_OPTIONS = "PRAGMA compile_options"

    /**
     * Whether the bundled SQLite was built with the R-tree module: `PRAGMA compile_options` lists `ENABLE_RTREE`
     * (with a value, `ENABLE_RTREE=1`, in some builds). The table in the pack is only used when this is true too.
     */
    fun hasRtreeModule(compileOptions: Collection<String>): Boolean =
        compileOptions.any { it.equals("ENABLE_RTREE", ignoreCase = true) || it.startsWith("ENABLE_RTREE=", ignoreCase = true) }

    /**
     * `s.category IN ('camp', 'fuel')` in the table's order, or null when every category is wanted. The codes are the
     * enum's own constants, never text from the user or the pack. [categories] must not be empty.
     */
    fun categoryCondition(categories: Set<RoadCategory>, column: String = "s.category"): String? =
        if (categories.size >= RoadCategory.entries.size) {
            null
        } else {
            RoadCategory.entries.filter { it in categories }.joinToString(", ", "$column IN (", ")") { "'${it.code}'" }
        }

    /**
     * The spots of [categories] inside [box], the rarer kinds first (the table's order: campsites before toilets) when
     * [limit] cuts them, so a crowded city view keeps its campsites. Null when nothing can match.
     */
    fun inBox(box: NearbyBox, categories: Set<RoadCategory>, limit: Int, rtree: Boolean): Query? {
        if (categories.isEmpty() || limit <= 0) return null
        val (from, where, args) = boxClauses(box, rtree)
        val conditions = listOfNotNull(where, categoryCondition(categories)).joinToString(" AND ")
        return Query(
            "SELECT $SPOT_COLUMNS FROM $from WHERE $conditions ORDER BY $PRIORITY, s.id LIMIT ${limit.coerceAtMost(MAX_SCAN_ROWS)}",
            args,
        )
    }

    /** `CASE s.category WHEN 'camp' THEN 0 WHEN 'rv' THEN 1 ... END`: the table's order as a sort key. */
    private val PRIORITY: String =
        RoadCategory.entries.withIndex().joinToString(" ", "CASE s.category ", " ELSE ${RoadCategory.entries.size} END") { (i, c) ->
            "WHEN '${c.code}' THEN $i"
        }

    /**
     * The [rows] spots nearest [center] inside the square of half-side [radiusKm], nearest first by a flat-earth
     * distance (no math functions, which not every SQLite build has), for the caller to measure properly and cut
     * to the circle. Null when no category is wanted.
     */
    fun nearest(center: LatLon, radiusKm: Double, categories: Set<RoadCategory>, rows: Int, rtree: Boolean): Query? {
        if (categories.isEmpty() || rows <= 0) return null
        val box = NearbyBox.around(center, radiusKm)
        val (from, where, boxArgs) = boxClauses(box, rtree)
        // A degree of longitude is this much of one of latitude here, squared.
        val lonScale = cos(Math.toRadians(center.lat)).let { it * it }
        val conditions = listOfNotNull(where, categoryCondition(categories))
        return Query(
            "SELECT $SPOT_COLUMNS FROM $from WHERE ${conditions.joinToString(" AND ")}" +
                " ORDER BY (s.lat - ?) * (s.lat - ?) + (s.lon - ?) * (s.lon - ?) * ? LIMIT ${rows.coerceAtMost(MAX_ROWS)}",
            boxArgs + listOf(center.lat, center.lat, center.lon, center.lon, lonScale),
        )
    }

    fun spot(id: Long): Query = Query("SELECT $SPOT_COLUMNS FROM spot s WHERE s.id = ?", listOf(id))

    /**
     * The rows at exactly ([lat], [lon]): the other categories of the one place the pack filed under several (the
     * `spot_lat_lon` index finds them). The caller keeps the ones with the same name and brand.
     */
    fun sameSpot(lat: Double, lon: Double): Query =
        Query("SELECT $SPOT_COLUMNS FROM spot s WHERE s.lat = ? AND s.lon = ? ORDER BY s.id LIMIT $MAX_SAME_SPOT", listOf(lat, lon))

    /** Columns of a cell: [zoom, x, y, category, count, lat, lon], in this order. */
    const val CLUSTER_COLUMNS = "zoom, x, y, category, count, lat, lon"

    /** The cluster cells of [zoom] the [box] touches, for [categories] (null when no category is wanted). */
    fun clusters(zoom: Int, box: NearbyBox, categories: Set<RoadCategory>): Query? {
        if (categories.isEmpty()) return null
        val (xs, ys) = RoadTiles.range(box, zoom)
        val condition = categoryCondition(categories, "category")?.let { " AND $it" } ?: ""
        return Query(
            "SELECT $CLUSTER_COLUMNS FROM cluster" +
                " WHERE zoom = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ?$condition LIMIT $MAX_CLUSTERS",
            listOf(zoom.toLong(), xs.first.toLong(), xs.last.toLong(), ys.first.toLong(), ys.last.toLong()),
        )
    }

    private data class Clauses(val from: String, val where: String, val args: List<Any>)

    /**
     * The FROM and WHERE for a box. With the R-tree, its index finds the candidates: the boxes that overlap the query
     * box (the overlap form, not containment: SQLite rounds a stored 32-bit float box outwards by up to about 1.5e-5
     * degrees, which containment would lose at the box's edges), the query box widened by [RTREE_TOLERANCE] besides.
     * The exact comparisons follow on the spot's own doubles. CROSS JOIN makes SQLite scan the R-tree first and look
     * the spots up by id, as the Wi-Fi pack's queries do (measured there: 139 ms against 380 ms on a 300,000-row
     * pack). Without the R-tree, the (lat, lon) index does it.
     */
    private fun boxClauses(box: NearbyBox, rtree: Boolean): Clauses {
        val exact = "s.lat BETWEEN ? AND ? AND s.lon BETWEEN ? AND ?"
        val exactArgs = listOf(box.south, box.north, box.west, box.east)
        return if (rtree) {
            Clauses(
                "road_rtree r CROSS JOIN spot s ON s.id = r.id",
                "r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ? AND $exact",
                listOf(box.south - RTREE_TOLERANCE, box.north + RTREE_TOLERANCE, box.west - RTREE_TOLERANCE, box.east + RTREE_TOLERANCE) + exactArgs,
            )
        } else {
            Clauses("spot s", exact, exactArgs)
        }
    }

    /** Degrees the R-tree's box is widened by: its 32-bit floats are off by about 1e-6 at US longitudes. */
    const val RTREE_TOLERANCE = 1e-5

    /** No more than this many rows from one nearest query, whatever the caller asks. */
    const val MAX_ROWS = 20_000

    /** No more than this many rows from one box query. */
    const val MAX_SCAN_ROWS = 20_000

    /** A place is in at most a few categories (fuel, truck, rest); this only bounds a bad pack. */
    const val MAX_SAME_SPOT = 16

    /** Cells of one zoom on a screen: a few per category; this only bounds a bad box. */
    const val MAX_CLUSTERS = 20_000
}
