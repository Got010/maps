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

    /** Cameras this close to each other (one pole facing both ways, or mapped twice) count as one. */
    const val SAME_SPOT_M = 25.0

    /**
     * A camera with a known direction counts for a route when it faces within this many degrees
     * of the route's direction of travel there, or of the opposite (the rear-plate side); past that
     * it faces across the road. The boundary counts.
     */
    const val DIRECTION_TOLERANCE_DEG = 45

    /**
     * The [kind] landmarks within [withinMeters] of [route], each once even
     * when overlapping boxes returned it twice; in no particular order. E.g.
     * the plate readers to steer around.
     */
    fun alongRoute(route: RouteLine, landmarks: List<Landmark>, kind: AlertKind, withinMeters: Double = MAX_OFFSET_M): List<Landmark> =
        landmarks.distinctBy { it.id }.filter { mark ->
            mark.category == kind.category && (route.locate(mark.point)?.offset ?: Double.POSITIVE_INFINITY) <= withinMeters
        }

    /**
     * Whether a camera facing [direction] (degrees clockwise from north) is
     * watching a road travelled on [travelBearing]: within [toleranceDegrees]
     * of the bearing or of the bearing + 180. A null direction (the pack has
     * no direction, or OSM doesn't say) always counts, and so does a null
     * bearing (a route with no length to point along).
     */
    fun faces(direction: Int?, travelBearing: Double?, toleranceDegrees: Int = DIRECTION_TOLERANCE_DEG): Boolean {
        if (direction == null || travelBearing == null) return true
        val off = angleBetween(direction.toDouble(), travelBearing)
        return off <= toleranceDegrees || 180.0 - off <= toleranceDegrees
    }

    /** The angle between two bearings in degrees, 0 to 180. */
    private fun angleBetween(a: Double, b: Double): Double {
        val d = ((a - b) % 360.0 + 360.0) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /**
     * Whether [mark] counts for [route] by its direction: it faces ([faces]) any leg of the route
     * within [withinMeters] of it, so at a corner both legs are tried, not only the one arriving.
     * No direction, or no leg that close, counts.
     */
    private fun facesRoute(route: RouteLine, mark: Landmark, withinMeters: Double): Boolean {
        if (mark.direction == null) return true
        val bearings = route.bearingsNear(mark.point, withinMeters)
        return bearings.isEmpty() || bearings.any { faces(mark.direction, it) }
    }

    /**
     * How many [kind] landmarks a drive along [route] passes, e.g. "3 plate
     * readers" on a route option: those within [withinMeters] of the line
     * and not facing across the road (see [faces]; landmarks without a
     * direction always count), with cameras within [sameSpotMeters] of one
     * already counted counting as the same one. This is the count only:
     * what the app steers around is [alongRoute], whatever way a camera faces.
     */
    fun count(
        route: RouteLine,
        landmarks: List<Landmark>,
        kind: AlertKind,
        withinMeters: Double = MAX_OFFSET_M,
        sameSpotMeters: Double = SAME_SPOT_M,
    ): Int {
        val spots = mutableListOf<LatLon>()
        for (mark in alongRoute(route, landmarks, kind, withinMeters)) {
            if (!facesRoute(route, mark, withinMeters)) continue
            if (spots.none { RouteLine.distanceMeters(it, mark.point) <= sameSpotMeters }) spots += mark.point
        }
        return spots.size
    }

    /** What a drive along [route] is exposed to: its plate readers and speed cameras, by [count]. */
    fun exposure(route: RouteLine, landmarks: List<Landmark>): RouteExposure = RouteExposure(
        plateReaders = count(route, landmarks, AlertKind.ALPR_CAMERA),
        speedCameras = count(route, landmarks, AlertKind.SPEED_CAMERA),
    )

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

/** The per-route exposure line: how many plate readers and speed cameras a route passes. */
data class RouteExposure(val plateReaders: Int, val speedCameras: Int) {
    val isEmpty: Boolean get() = plateReaders == 0 && speedCameras == 0
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
