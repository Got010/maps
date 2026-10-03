package app.southbound.core.search

/** Things on the route worth a spoken warning, from the search pack's landmarks. */
enum class AlertKind(val category: String) {
    SPEED_CAMERA(Landmark.SPEED_CAMERA),
    SCHOOL_ZONE(Landmark.SCHOOL_ZONE),

    /** A licence-plate reader, e.g. a Flock camera, as mapped by DeFlock. */
    ALPR_CAMERA(Landmark.ALPR_CAMERA),
    ;

    companion object {
        fun of(category: String): AlertKind? = entries.firstOrNull { it.category == category }
    }
}

/**
 * One alert point on the active route, [alongMeters] from the route's start.
 * The map draws these; guidance speaks them on approach.
 */
data class RouteAlert(
    val id: Long,
    val kind: AlertKind,
    val lat: Double,
    val lon: Double,
    val alongMeters: Double,
)

/**
 * Matches alert points to a route and decides when each is spoken. Pure, so
 * it is unit tested with hand-drawn routes.
 */
object RouteAlerts {

    /** A camera further than this from the route line is on another road. */
    const val MAX_OFFSET_M = 40.0

    /** Spoken when the driver is this close (along the route). */
    const val ANNOUNCE_AHEAD_M = 300.0

    /** Alerts of one kind this close together along the route are spoken once (a pair of cameras, a zone's two signs). */
    const val SAME_ALERT_M = 200.0

    /** The alerts on [route], in driving order. */
    fun onRoute(route: RouteLine, landmarks: List<Landmark>): List<RouteAlert> =
        landmarks.mapNotNull { mark ->
            val kind = AlertKind.of(mark.category) ?: return@mapNotNull null
            val loc = route.locate(mark.point) ?: return@mapNotNull null
            if (loc.offset > MAX_OFFSET_M) return@mapNotNull null
            RouteAlert(mark.id, kind, mark.lat, mark.lon, loc.along)
        }.sortedBy { it.alongMeters }

    /**
     * The alerts to speak now that the driver is [userAlong] metres along
     * the route: ahead within [ANNOUNCE_AHEAD_M], not yet [announced], and
     * enabled in [kinds]. Alerts of the same kind right behind a due one are
     * included in [Due.covered] so they aren't spoken again a moment later.
     */
    fun due(alerts: List<RouteAlert>, userAlong: Double, announced: Set<Long>, kinds: Set<AlertKind>): Due {
        val speak = mutableListOf<RouteAlert>()
        val covered = mutableSetOf<Long>()
        for (alert in alerts) {
            if (alert.id in announced || alert.id in covered || alert.kind !in kinds) continue
            val ahead = alert.alongMeters - userAlong
            if (ahead < 0 || ahead > ANNOUNCE_AHEAD_M) continue
            speak += alert
            covered += alert.id
            alerts.filter { it.kind == alert.kind && it.alongMeters - alert.alongMeters in 0.0..SAME_ALERT_M }
                .forEach { covered += it.id }
        }
        return Due(speak, covered)
    }

    /** [speak]: one per alert to say now. [covered]: every id to mark as announced. */
    data class Due(val speak: List<RouteAlert>, val covered: Set<Long>)
}

/**
 * Follows the driver along a route, looking only a little behind and ahead
 * of the last position so a route that loops back near itself doesn't jump.
 */
class RouteProgress(private val route: RouteLine) {
    private var along = -1.0

    /** Metres along the route for [position], or null when it's off the route. */
    fun update(position: LatLon, maxOffsetMeters: Double = 60.0): Double? {
        val window = if (along < 0) null else route.locate(position, along - BACK_M, along + AHEAD_M)
        val loc = window?.takeIf { it.offset <= maxOffsetMeters } ?: route.locate(position)
        if (loc == null || loc.offset > maxOffsetMeters) return null
        along = loc.along
        return along
    }

    private companion object {
        const val BACK_M = 100.0
        const val AHEAD_M = 2_000.0
    }
}
