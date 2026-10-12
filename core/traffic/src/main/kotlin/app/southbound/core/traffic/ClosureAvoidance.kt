package app.southbound.core.traffic

import kotlin.math.cos

/**
 * Turns 511NY closures into Valhalla `exclude_polygons`: a small square
 * around each closed spot, so the router finds a way around it. Valhalla
 * caps the total perimeter (10 km by default), so only the closures that
 * could matter for a trip are sent: those in the trip's box, nearest to the
 * straight line between its ends first.
 */
object ClosureAvoidance {
    /** Half the side of the square drawn around a closure, metres: covers the road and its ramps. */
    const val HALF_SIDE_METERS = 40.0

    /** 10 km of perimeter at 320 m each leaves room to spare. */
    const val MAX_POLYGONS = 25

    /** How far from the route a closure counts as "on" it. */
    const val ON_ROUTE_METERS = 50.0

    private const val DEG = Math.PI / 180
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    /** A ring of (lon, lat) pairs, closed, as Valhalla wants it. */
    fun square(center: GeoPoint, halfSideMeters: Double = HALF_SIDE_METERS): List<Pair<Double, Double>> {
        val dLat = halfSideMeters / METERS_PER_DEGREE_LAT
        val dLon = dLat / cos(center.lat * DEG)
        val west = center.lon - dLon
        val east = center.lon + dLon
        val south = center.lat - dLat
        val north = center.lat + dLat
        return listOf(west to south, east to south, east to north, west to north, west to south)
    }

    /**
     * The closures worth avoiding for a trip from [from] to [to]: inside the
     * trip's box grown by a quarter (at least 3 km), closest to the straight
     * line first, at most [max].
     */
    fun relevant(closures: List<Incident>, from: GeoPoint, to: GeoPoint, max: Int = MAX_POLYGONS): List<Incident> {
        val padLat = maxOf((maxOf(from.lat, to.lat) - minOf(from.lat, to.lat)) * 0.25, 3_000 / METERS_PER_DEGREE_LAT)
        val padLon = maxOf((maxOf(from.lon, to.lon) - minOf(from.lon, to.lon)) * 0.25, 3_000 / (METERS_PER_DEGREE_LAT * cos(from.lat * DEG)))
        val minLat = minOf(from.lat, to.lat) - padLat
        val maxLat = maxOf(from.lat, to.lat) + padLat
        val minLon = minOf(from.lon, to.lon) - padLon
        val maxLon = maxOf(from.lon, to.lon) + padLon
        return closures
            .filter { it.isClosure && it.location.lat in minLat..maxLat && it.location.lon in minLon..maxLon }
            .sortedBy { Geo.distanceToSegmentMeters(it.location, from, to) }
            .take(max)
    }

    /** Exclude polygons for [relevant] closures, ready for a route request. */
    fun polygons(closures: List<Incident>, from: GeoPoint, to: GeoPoint, max: Int = MAX_POLYGONS): List<List<Pair<Double, Double>>> =
        relevant(closures, from, to, max).map { square(it.location) }

    /**
     * The polygons for a reroute around [ahead] (closures just found on the route, see [ahead]) on top of [current]
     * (what the trip already avoids: the preview's closures near the trip), each once, those ahead first, at most
     * [max]. Not the first closures the state feeds list: 511NY alone carries a couple of hundred, and a closure
     * in Pennsylvania would never make the cut.
     */
    fun forReroute(ahead: List<Incident>, current: List<List<Pair<Double, Double>>>, max: Int = MAX_POLYGONS): List<List<Pair<Double, Double>>> =
        (ahead.filter { it.isClosure }.map { square(it.location) } + current).distinct().take(max)

    /**
     * The closures within [withinMeters] of [line] (what is left of the route, from the driver on), nearest first
     * along the way. One whose direction ("Southbound") runs against the route there is on the other carriageway,
     * 10 to 30 m off on a divided highway, and left out, as for police ([PoliceAlerts.onRoute]).
     */
    fun onRoute(closures: List<Incident>, line: List<GeoPoint>, withinMeters: Double = ON_ROUTE_METERS): List<Incident> =
        PoliceAlerts.onRoute(line, closures.filter { it.isClosure }, routeMeters = withinMeters) { Double.POSITIVE_INFINITY }
            .map { it.first }

    /** The closures on what is left of [route] from [here] on ([onRoute]): one behind the driver is no reason to reroute. */
    fun ahead(closures: List<Incident>, route: List<GeoPoint>, here: GeoPoint, withinMeters: Double = ON_ROUTE_METERS): List<Incident> =
        onRoute(closures, RouteTraffic.remaining(route, here), withinMeters)
}
