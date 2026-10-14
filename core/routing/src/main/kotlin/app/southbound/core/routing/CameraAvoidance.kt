package app.southbound.core.routing

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The "Avoid plate readers" and "Avoid DOT cameras" route options: the roads past the cameras a route meets become
 * Valhalla `exclude_locations` (a spot on the road beside each camera: that stretch of road is left out, and the
 * streets around it stay open, so the router can turn off onto a side street right before the camera), and the app
 * routes again, a few cameras at a time (the loop lives in feature/explore PlateReaders):
 *
 * 1. Route; note the cameras within [NEAR_ROUTE_METERS] of the best route (the ones the route card counts).
 * 2. Exclude the road beside each and route again, up to [MAX_PASSES] times while new cameras turn up, or until
 *    [MAX_CAMERAS] roads are excluded or [MAX_ROUTINGS] routings are spent.
 * 3. When a pass finds no route (a camera on the only way: a bridge, the one road in), [passable] bisects the new
 *    cameras to find which, drops it for good and carries on with the rest, instead of giving up.
 * 4. A camera the new route passes again on another road within reach gets a second spot ([SAME_ROAD_METERS]).
 * 5. Of every pass's routes, the plain one included, the one with the fewest cameras wins (quickest when tied): a
 *    late pass can be pushed onto streets with more cameras than the highway it left.
 *
 * Measured with pyvalhalla on the release's tiles and search pack (October 2026). Over 48 Hudson Valley trips past
 * plate readers, this clears every reader on 38 trips and leaves 20 of the 262 the plain routes met, for a median
 * 7 minutes more (p90 21); the squares did 31 trips and 42 left for 6.3 minutes. With DOT cameras avoided too (53
 * trips, 534 cameras): 32 trips cleared and 104 left against 28 and 178, for a median 12 minutes more (p90 34).
 * The owner's Pelham Manor to the Hudson Valley example went from 8 readers on every option to 2 (77 minutes
 * instead of 62). Squares (`exclude_polygons`) were used before: Valhalla caps their total perimeter at 10 km
 * (60 cameras), which a long trip reached by its fifth pass; a pass that found no route ended the whole thing,
 * often on the first pass, leaving the plain route; and the last route was kept even when an earlier pass had
 * fewer cameras.
 */
object CameraAvoidance {
    /**
     * A camera this close to a route counts as on it, for avoiding: the same reach as the count on each route card
     * (RouteAlerts.MAX_OFFSET_M in core:search), so what is counted is what the app tries to avoid.
     */
    const val NEAR_ROUTE_METERS = 40.0

    /** At most this many roads are excluded for one trip; the phone's Valhalla is configured to take them (OfflineRouter). */
    const val MAX_CAMERAS = 500

    /**
     * Route, exclude the cameras found, route again: at most this many times after the first route. 12 was enough for
     * 52 trips in 53 measured; avoiding is to win however long the way around, so the long trips get room to go on.
     */
    const val MAX_PASSES = 30

    /**
     * Routings one preview may spend on avoiding, the bisections of [passable] included; each is a second or so on
     * the phone. The median trip takes 6 or 7, nine in ten under 18, so this only lets the rare long trip (many
     * cameras, many dead ends) keep looking for a way around rather than stopping at 24.
     */
    const val MAX_ROUTINGS = 60

    /**
     * A camera already excluded gets another spot when a later route passes it on a road farther than this from its
     * earlier spots (the other carriageway, a frontage road, the cross street at a corner camera). Without this,
     * 29 trips cleared and 39 readers were left instead of 38 and 20.
     */
    const val SAME_ROAD_METERS = 8.0

    /**
     * A camera this close to the start, a stop or the destination is left alone: excluding the road a waypoint
     * snaps to makes routing fail.
     */
    const val WAYPOINT_CLEARANCE_METERS = 150.0

    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val DEG = Math.PI / 180

    /** A point in WGS84 degrees. */
    data class Spot(val lat: Double, val lon: Double)

    /** Whether [spot] is far enough from every one of [waypoints] (the start, stops and destination) to exclude. */
    fun clearOfWaypoints(spot: Spot, waypoints: List<Spot>): Boolean =
        waypoints.none { distanceMeters(spot, it) < WAYPOINT_CLEARANCE_METERS }

    /**
     * The spots a routing from where the car is may exclude: those at least [WAYPOINT_CLEARANCE_METERS] from [car]. A
     * reroute starts where the car is, which can be the very road a spot excludes (a driver who missed the detour), and
     * excluding the road the start snaps to makes routing fail, as for the waypoints.
     */
    fun clearOfCar(spots: List<Spot>, car: Spot): List<Spot> =
        spots.filter { distanceMeters(it, car) >= WAYPOINT_CLEARANCE_METERS }

    /**
     * Routes from where the car is with [route], given the spots to exclude: first with the spots clear of [car]
     * ([clearOfCar]), and when that finds no route ([RoutingException]), once more with none. A driver still on an
     * excluded road gets a route, instead of "Rerouting." on every fix until past the camera. Only the trip's own
     * reroutes use this: in a preview, no route is how [passable] finds the camera on the only way.
     */
    inline fun <R> fromCar(spots: List<Spot>, car: Spot, route: (List<Spot>) -> R): R {
        val clear = clearOfCar(spots, car)
        return try {
            route(clear)
        } catch (e: RoutingException) {
            if (clear.isEmpty()) throw e
            route(emptyList())
        }
    }

    /** What [passable] found: the items of a batch that can be excluded together, and the ones that shut the only way. */
    class Split<T>(val kept: List<T>, val dropped: List<T>)

    /**
     * When excluding a whole [batch] leaves no route, finds the largest part of it that does: [fits] says whether a
     * route exists with the given items excluded (on top of whatever was excluded before). Halves the batch and
     * tries each half on top of what already fits, down to single items; an item that fits with nothing else new is
     * [Split.dropped], unavoidable. About 2 log2(n) calls of [fits] for each dropped item; a batch that fits whole
     * costs one.
     */
    suspend fun <T> passable(batch: List<T>, fits: suspend (List<T>) -> Boolean): Split<T> {
        val kept = ArrayList<T>()
        val dropped = ArrayList<T>()
        suspend fun split(part: List<T>) {
            if (part.isEmpty()) return
            if (fits(kept + part)) {
                kept += part
                return
            }
            if (part.size == 1) {
                dropped += part
                return
            }
            val half = part.size / 2
            split(part.subList(0, half))
            split(part.subList(half, part.size))
        }
        split(batch)
        return Split(kept, dropped)
    }

    fun distanceMeters(a: Spot, b: Spot): Double {
        val dLat = (b.lat - a.lat) * METERS_PER_DEGREE_LAT
        val dLon = (b.lon - a.lon) * METERS_PER_DEGREE_LAT * cos((a.lat + b.lat) / 2 * DEG)
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
