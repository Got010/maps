package app.southbound.core.traffic

import java.util.Locale

/** What a spoken incident alert is about. */
enum class IncidentAlertKind {
    /** A crash (not a disabled vehicle on its own: those are frequent and mostly on the shoulder). */
    CRASH,

    /** Some lanes closed or blocked, the road still open: roadwork, a disabled vehicle, debris. */
    LANES,
}

/** An incident to speak, how far along what is left of the route it is, and why. */
data class IncidentAhead(val incident: Incident, val metersAhead: Double, val kind: IncidentAlertKind)

/**
 * Which 511NY crashes and lane closures to speak during a trip: on the route that is left,
 * within about two miles, on the driver's side of the road (the same geometry as
 * [PoliceAlerts]). Closures have their own alert and reroute and police their own line, so
 * neither is picked here. Pure; the caller keeps the ids it has already spoken.
 */
object IncidentAlerts {
    /** Warn this far ahead along the route, about two miles: time to change lanes on a highway. */
    const val AHEAD_METERS = 3200.0

    /** Why [incident] is worth speaking, or null when it isn't. */
    fun kindOf(incident: Incident): IncidentAlertKind? = when {
        incident.isClosure || incident.kind == IncidentKind.POLICE -> null
        incident.kind == IncidentKind.ACCIDENT && !isDisabledVehicle(incident) -> IncidentAlertKind.CRASH
        lanesClosed(incident) != null -> IncidentAlertKind.LANES
        else -> null
    }

    /** The crashes and lane closures in [incidents] ahead on [route] (starting at the driver), nearest first. */
    fun ahead(route: List<GeoPoint>, incidents: List<Incident>, withinMeters: Double = AHEAD_METERS): List<IncidentAhead> {
        val kinds = incidents.mapNotNull { i -> kindOf(i)?.let { i to it } }.toMap()
        if (kinds.isEmpty()) return emptyList()
        return PoliceAlerts.onRoute(route, kinds.keys.toList()) { withinMeters }
            .map { (incident, along) -> IncidentAhead(incident, along, kinds.getValue(incident)) }
    }

    /**
     * The incident's lane closure as it is said ("Right lane closed"), or null when it closes no
     * lane or all of them (that is a closure).
     */
    fun lanesClosed(incident: Incident): String? {
        val lanes = incident.lanesStatus?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (incident.isClosure) return null
        val l = lanes.lowercase(Locale.US)
        if ("lane" !in l || ("closed" !in l && "blocked" !in l)) return null
        return lanes
    }

    private fun isDisabledVehicle(incident: Incident): Boolean {
        val d = incident.description.lowercase(Locale.US)
        return "disabled vehicle" in d || "stalled vehicle" in d || "disabled tractor" in d || "disabled bus" in d
    }
}
