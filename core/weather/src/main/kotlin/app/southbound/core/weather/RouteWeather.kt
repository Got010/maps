package app.southbound.core.weather

import app.southbound.core.traffic.Geo
import app.southbound.core.traffic.GeoPoint

/** An alert whose area the route passes through, and how far along the route it starts (0 when already inside). */
data class AlertOnRoute(val alert: WeatherAlert, val metersAhead: Double)

/**
 * Which alert areas a route crosses, from the alerts' own polygons or their zones' shapes.
 * Pure geometry on the phone: the route never leaves it.
 */
object RouteWeather {
    /** Points are checked at least this often along the route, so a small area between two far-apart points isn't missed. */
    const val STEP_METERS = 500.0

    /**
     * The alerts in [areas] (an alert's id to its rings) that [route] enters, ordered by
     * where it first does. Alerts that ended before [now] are left out.
     */
    fun alongRoute(route: List<GeoPoint>, alerts: List<WeatherAlert>, areas: Map<String, List<Ring>>, now: Long): List<AlertOnRoute> {
        if (route.isEmpty()) return emptyList()
        val samples = sample(route)
        return alerts.mapNotNull { alert ->
            if (alert.ends != null && alert.ends < now) return@mapNotNull null
            val rings = areas[alert.id].orEmpty().ifEmpty { alert.polygons }
            if (rings.isEmpty()) return@mapNotNull null
            val boxes = rings.map(::box)
            val first = samples.firstOrNull { (point, _) ->
                rings.indices.any { i -> boxes[i].contains(point) && inside(point, rings[i]) }
            } ?: return@mapNotNull null
            AlertOnRoute(alert, first.second)
        }.sortedWith(compareBy<AlertOnRoute> { it.metersAhead }.thenBy { it.alert.severity.ordinal })
    }

    /** Whether [point] is in any of [rings]. */
    fun contains(rings: List<Ring>, point: GeoPoint): Boolean = rings.any { box(it).contains(point) && inside(point, it) }

    /** The route's points, with extra ones on long segments, each with its distance along the route. */
    private fun sample(route: List<GeoPoint>): List<Pair<GeoPoint, Double>> {
        val out = ArrayList<Pair<GeoPoint, Double>>(route.size)
        var along = 0.0
        out += route[0] to 0.0
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val len = Geo.distanceMeters(a, b)
            val steps = (len / STEP_METERS).toInt()
            for (s in 1..steps) {
                val t = s * STEP_METERS / len
                out += GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t) to along + s * STEP_METERS
            }
            along += len
            out += b to along
        }
        return out
    }

    private class Box(val south: Double, val west: Double, val north: Double, val east: Double) {
        fun contains(p: GeoPoint) = p.lat in south..north && p.lon in west..east
    }

    private fun box(ring: Ring) = Box(ring.minOf { it.lat }, ring.minOf { it.lon }, ring.maxOf { it.lat }, ring.maxOf { it.lon })

    /** Ray casting; a closed or open ring both work. */
    private fun inside(p: GeoPoint, ring: Ring): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
