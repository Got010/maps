package app.southbound.core.traffic

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/** A police-activity incident on the route, and how far along what is left of the route it is. */
data class PoliceAhead(val incident: Incident, val metersAhead: Double)

/**
 * Which police-activity incidents (511NY events of kind [IncidentKind.POLICE])
 * to warn about on the way: on the route that is left, within about a mile,
 * and on the carriageway the driver is using. Pure geometry; the caller keeps
 * the set of ids it has already spoken.
 */
object PoliceAlerts {
    /** Warn once the incident is this close along the route: about a mile, 1.5 km. */
    const val AHEAD_METERS = 1600.0

    /** Incidents further than this from the route line are not on it (the same reach as pins on the route). */
    const val ROUTE_METERS = RouteTraffic.INCIDENT_METERS

    /** A heading further than this from the incident's stated direction of travel means the other carriageway. */
    const val OPPOSITE_DEGREES = 100.0

    private const val DEG = Math.PI / 180

    /**
     * The police incidents in [incidents] (other kinds are ignored) that sit on
     * [route] (what is left of it, starting at the driver) within [withinMeters]
     * of the driver, nearest first. An incident whose direction ("Northbound")
     * runs against the route there is left out.
     */
    fun ahead(route: List<GeoPoint>, incidents: List<Incident>, withinMeters: Double = AHEAD_METERS): List<PoliceAhead> {
        if (route.size < 2) return emptyList()
        val police = incidents.filter { it.kind == IncidentKind.POLICE }
        if (police.isEmpty()) return emptyList()
        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) cumulative[i] = cumulative[i - 1] + Geo.distanceMeters(route[i - 1], route[i])
        return police.mapNotNull { incident ->
            var bestDistance = Double.POSITIVE_INFINITY
            var along = 0.0
            var heading = 0.0
            for (i in 0 until route.size - 1) {
                val a = route[i]
                val b = route[i + 1]
                val k = cos(incident.location.lat * DEG)
                val dx = (b.lon - a.lon) * k
                val dy = b.lat - a.lat
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else ((((incident.location.lon - a.lon) * k) * dx + (incident.location.lat - a.lat) * dy) / len2).coerceIn(0.0, 1.0)
                val foot = GeoPoint(a.lat + t * dy, a.lon + t * (b.lon - a.lon))
                val d = Geo.distanceMeters(incident.location, foot)
                if (d < bestDistance) {
                    bestDistance = d
                    along = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
                    heading = bearing(dx, dy)
                }
            }
            if (bestDistance > ROUTE_METERS || along > withinMeters) return@mapNotNull null
            val bound = boundHeading(incident.direction)
            if (bound != null && angleBetween(bound, heading) > OPPOSITE_DEGREES) return@mapNotNull null
            PoliceAhead(incident, along)
        }.sortedBy { it.metersAhead }
    }

    /** Compass degrees (0 north, 90 east) of a displacement of [dx] east and [dy] north (same units). */
    private fun bearing(dx: Double, dy: Double): Double = (atan2(dx, dy) / DEG + 360) % 360

    /** 0, 90, 180 or 270 for "Northbound", "Eastbound", ...; null for "Both Directions", "Inbound" and the like. */
    private fun boundHeading(direction: String?): Double? {
        val d = direction?.lowercase() ?: return null
        if ("both" in d) return null
        return when {
            "north" in d -> 0.0
            "east" in d -> 90.0
            "south" in d -> 180.0
            "west" in d -> 270.0
            else -> null
        }
    }

    private fun angleBetween(a: Double, b: Double): Double = abs(((a - b + 540) % 360 + 360) % 360 - 180)
}
