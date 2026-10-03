package app.southbound.core.routing

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Turns licence-plate readers on a route into Valhalla `exclude_polygons`,
 * for the "Avoid plate readers" route option: a small square around each
 * camera, so the router finds a way past it on another street.
 *
 * The app does this a few cameras at a time rather than for a whole corridor:
 * route, square off the cameras on the best route, route again, and repeat
 * while new ones turn up (see [MAX_PASSES]). Valhalla caps the total
 * perimeter of all exclude polygons (`max_exclude_polygons_length`, 10 km by
 * default), and closures from the traffic layer share that budget, so
 * [merge] keeps closures first and adds cameras while there is room.
 */
object CameraAvoidance {
    /** Half the side of the square around a camera, metres: its own road, not the next one over. */
    const val HALF_SIDE_METERS = 18.0

    /**
     * A camera this close to a route counts as on it, for avoiding: the same
     * reach as the count on each route card (RouteAlerts.MAX_OFFSET_M in
     * core:search), so what is counted is what the app tries to avoid.
     */
    const val NEAR_ROUTE_METERS = 40.0

    /**
     * A camera further off the route line than this gets its square on the
     * road beside it instead (see [HALF_SIDE_METERS]); otherwise the square
     * could miss the road it watches.
     */
    const val SNAP_TO_ROUTE_METERS = 12.0

    /** At most this many cameras are squared off for one trip (60 x 144 m = 8.6 km of perimeter). */
    const val MAX_CAMERAS = 60

    /** Route, square off the cameras found, route again: at most this many times after the first route. */
    const val MAX_PASSES = 3

    /** Valhalla's default limit is 10 km of total perimeter; stay a little under it. */
    const val MAX_TOTAL_PERIMETER_METERS = 9_500.0

    /**
     * A camera this close to the start, a stop or the destination is left
     * alone: excluding the road a waypoint snaps to makes routing fail.
     */
    const val WAYPOINT_CLEARANCE_METERS = 150.0

    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val DEG = Math.PI / 180

    /** A point in WGS84 degrees. */
    data class Spot(val lat: Double, val lon: Double)

    /** A closed ring of (lon, lat) pairs around [center], as Valhalla wants it. */
    fun square(center: Spot, halfSideMeters: Double = HALF_SIDE_METERS): List<Pair<Double, Double>> {
        val dLat = halfSideMeters / METERS_PER_DEGREE_LAT
        val dLon = dLat / cos(center.lat * DEG).coerceAtLeast(0.01)
        val west = center.lon - dLon
        val east = center.lon + dLon
        val south = center.lat - dLat
        val north = center.lat + dLat
        return listOf(west to south, east to south, east to north, west to north, west to south)
    }

    /**
     * Squares around [cameras], in the order given, skipping any within
     * [WAYPOINT_CLEARANCE_METERS] of a [waypoints] entry (the start, stops and
     * destination), at most [max].
     */
    fun polygons(
        cameras: List<Spot>,
        waypoints: List<Spot>,
        max: Int = MAX_CAMERAS,
        halfSideMeters: Double = HALF_SIDE_METERS,
    ): List<List<Pair<Double, Double>>> =
        cameras
            .filter { cam -> waypoints.none { distanceMeters(cam, it) < WAYPOINT_CLEARANCE_METERS } }
            .distinct()
            .take(max)
            .map { square(it, halfSideMeters) }

    /**
     * [closures] and then as many [cameras] as fit in [maxPerimeterMeters] of
     * total perimeter. Closures are never dropped here (ClosureAvoidance keeps
     * them under the limit on its own); a camera that doesn't fit is left out,
     * and the route may then pass it.
     */
    fun merge(
        closures: List<List<Pair<Double, Double>>>,
        cameras: List<List<Pair<Double, Double>>>,
        maxPerimeterMeters: Double = MAX_TOTAL_PERIMETER_METERS,
    ): List<List<Pair<Double, Double>>> {
        if (cameras.isEmpty()) return closures
        var used = closures.sumOf { perimeterMeters(it) }
        val out = closures.toMutableList()
        for (ring in cameras) {
            val length = perimeterMeters(ring)
            if (used + length > maxPerimeterMeters) break
            used += length
            out += ring
        }
        return out
    }

    /** Length of a ring of (lon, lat) pairs, metres. */
    fun perimeterMeters(ring: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 1 until ring.size) {
            val (lon1, lat1) = ring[i - 1]
            val (lon2, lat2) = ring[i]
            total += distanceMeters(Spot(lat1, lon1), Spot(lat2, lon2))
        }
        return total
    }

    fun distanceMeters(a: Spot, b: Spot): Double {
        val dLat = (b.lat - a.lat) * METERS_PER_DEGREE_LAT
        val dLon = (b.lon - a.lon) * METERS_PER_DEGREE_LAT * cos((a.lat + b.lat) / 2 * DEG)
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
