package app.southbound.core.traffic

import java.util.Locale

/** What a spoken incident alert is about. */
enum class IncidentAlertKind {
    /** A crash (not a disabled vehicle on its own: those are frequent and mostly on the shoulder). */
    CRASH,

    /** Roadwork or construction under way, the road still open; any lanes it closes are said with it. */
    ROADWORK,

    /** Some lanes closed or blocked, the road still open, by something else: a disabled vehicle, debris. */
    LANES,
}

/** An incident to speak, how far along what is left of the route it is, and why. */
data class IncidentAhead(val incident: Incident, val metersAhead: Double, val kind: IncidentAlertKind)

/**
 * Which 511NY crashes, roadwork and lane closures to speak during a trip: on the route that is
 * left, within about two miles, on the driver's side of the road (the same geometry as
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
        incident.kind == IncidentKind.ROADWORK -> IncidentAlertKind.ROADWORK
        lanesClosed(incident) != null -> IncidentAlertKind.LANES
        else -> null
    }

    /** Roadwork on the same road this close after a spoken one belongs to the same work zone. */
    const val SAME_ZONE_METERS = 1600.0

    /**
     * The alerts [spoken] stands for besides itself: later roadwork on the same road within
     * [SAME_ZONE_METERS] of it, since a long work zone is often several events in a row. The
     * caller marks them spoken too.
     */
    fun sameZone(spoken: IncidentAhead, found: List<IncidentAhead>): List<IncidentAhead> {
        if (spoken.kind != IncidentAlertKind.ROADWORK) return emptyList()
        val road = spoken.incident.roadway?.lowercase(Locale.US) ?: return emptyList()
        return found.filter {
            it !== spoken && it.kind == IncidentAlertKind.ROADWORK &&
                it.incident.roadway?.lowercase(Locale.US) == road &&
                it.metersAhead - spoken.metersAhead in 0.0..SAME_ZONE_METERS
        }
    }

    /** The crashes, roadwork and lane closures in [incidents] ahead on [route] (starting at the driver), nearest first. */
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
