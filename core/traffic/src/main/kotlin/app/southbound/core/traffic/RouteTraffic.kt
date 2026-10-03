package app.southbound.core.traffic

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A stretch of the route that runs along one NYC DOT link, with that link's speed. */
data class SlowSegment(val points: List<GeoPoint>, val speedMph: Double, val band: SpeedBand)

/** Speed bands, with the thresholds the map's speed layer uses (OverlayLayers): stopped, slow, moving, free. */
enum class SpeedBand {
    STOPPED,
    SLOW,
    MODERATE,
    FREE,
    ;

    companion object {
        fun of(mph: Double): SpeedBand = when {
            mph < 10 -> STOPPED
            mph < 25 -> SLOW
            mph < 40 -> MODERATE
            else -> FREE
        }
    }
}

/** What live speeds say about a route: the coloured stretches and the time they add. */
data class RouteSlowdowns(val segments: List<SlowSegment>, val delaySeconds: Double) {
    companion object {
        val NONE = RouteSlowdowns(emptyList(), 0.0)
    }
}

/**
 * Lays live traffic over a route: which stretches run along a slow NYC DOT
 * link, how much time they add, and which incidents sit on the route. Pure
 * geometry, so it is tested without Android.
 */
object RouteTraffic {
    /** A link counts as on the route when both of its ends are this close to the route line. */
    const val MATCH_METERS = 25.0

    /** Incidents this close to the route line are shown on it. */
    const val INCIDENT_METERS = 60.0

    /** Speed assumed when free flow is unknown (the DOT feed carries no speed limit). */
    const val FREE_FLOW_MPH = 45.0

    /** Links at or above this speed are flowing: drawn green, adding no delay. */
    const val FLOWING_MPH = 40.0

    private const val MPH_TO_MPS = 0.44704
    private const val DEG = Math.PI / 180

    /**
     * The parts of [route] that run along [links] in the route's direction of
     * travel, coloured by speed band, and the delay they add: for each matched
     * slow link its travel time minus the time at [freeFlowMph].
     */
    fun slowdowns(
        route: List<GeoPoint>,
        links: List<SpeedLink>,
        matchMeters: Double = MATCH_METERS,
        freeFlowMph: Double = FREE_FLOW_MPH,
    ): RouteSlowdowns {
        if (route.size < 2 || links.isEmpty()) return RouteSlowdowns.NONE
        val line = Line(route)
        val found = ArrayList<Pair<Double, SlowSegment>>()
        var delay = 0.0
        for (link in links) {
            if (link.points.size < 2 || link.speedMph <= 0.0) continue
            if (!line.nearBox(link.points, matchMeters)) continue
            val start = line.project(link.points.first())
            val end = line.project(link.points.last())
            if (start.distance > matchMeters || end.distance > matchMeters) continue
            // A link running against the route is the other carriageway.
            if (end.along <= start.along) continue
            val linkLength = pathLength(link.points)
            val span = end.along - start.along
            if (linkLength > 0 && span > linkLength * 3 + 50) continue
            found += start.along to SlowSegment(line.slice(start.along, end.along), link.speedMph, SpeedBand.of(link.speedMph))
            if (link.speedMph < FLOWING_MPH) {
                val actual = link.travelTimeSeconds?.toDouble() ?: (linkLength / (link.speedMph * MPH_TO_MPS))
                val free = linkLength / (freeFlowMph * MPH_TO_MPS)
                delay += max(0.0, actual - free)
            }
        }
        return RouteSlowdowns(found.sortedBy { it.first }.map { it.second }, delay)
    }

    /** The incidents within [withinMeters] of [route], in the order they come along it. */
    fun incidentsOnRoute(route: List<GeoPoint>, incidents: List<Incident>, withinMeters: Double = INCIDENT_METERS): List<Incident> {
        if (route.size < 2) return emptyList()
        val line = Line(route)
        return incidents
            .map { it to line.project(it.location) }
            .filter { (_, p) -> p.distance <= withinMeters }
            .sortedBy { (_, p) -> p.along }
            .map { it.first }
    }

    /** The part of [route] from the point nearest [from] on, e.g. what is left of a trip. */
    fun remaining(route: List<GeoPoint>, from: GeoPoint): List<GeoPoint> {
        if (route.size < 2) return route
        val line = Line(route)
        return line.slice(line.project(from).along, line.total)
    }

    private fun pathLength(points: List<GeoPoint>): Double {
        var sum = 0.0
        for (i in 0 until points.size - 1) sum += Geo.distanceMeters(points[i], points[i + 1])
        return sum
    }

    /** A route polyline with cumulative distances, for projecting points onto it. */
    private class Line(val points: List<GeoPoint>) {
        private val cumulative = DoubleArray(points.size)
        private val minLat: Double
        private val maxLat: Double
        private val minLon: Double
        private val maxLon: Double

        init {
            for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
            minLat = points.minOf { it.lat }
            maxLat = points.maxOf { it.lat }
            minLon = points.minOf { it.lon }
            maxLon = points.maxOf { it.lon }
        }

        val total: Double get() = cumulative.last()

        /** False when no point of [other] can be within [meters] of this line (cheap reject). */
        fun nearBox(other: List<GeoPoint>, meters: Double): Boolean {
            val padLat = meters / METERS_PER_DEGREE + 1e-6
            val padLon = padLat / max(cos(minLat * DEG), 0.01)
            return other.any { it.lat in (minLat - padLat)..(maxLat + padLat) && it.lon in (minLon - padLon)..(maxLon + padLon) }
        }

        /** The closest spot on the line to [p]: how far away, and how far along the line it is. */
        fun project(p: GeoPoint): Projection {
            var best = Projection(Double.POSITIVE_INFINITY, 0.0)
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                // Skip segments clearly out of reach before doing the maths.
                if (min(a.lat, b.lat) - 0.01 > p.lat || max(a.lat, b.lat) + 0.01 < p.lat) continue
                val k = cos(p.lat * DEG)
                val dx = (b.lon - a.lon) * k
                val dy = b.lat - a.lat
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else ((((p.lon - a.lon) * k) * dx + (p.lat - a.lat) * dy) / len2).coerceIn(0.0, 1.0)
                val c = GeoPoint(a.lat + t * dy, a.lon + t * (b.lon - a.lon))
                val d = Geo.distanceMeters(p, c)
                if (d < best.distance) best = Projection(d, cumulative[i] + t * (cumulative[i + 1] - cumulative[i]))
            }
            return best
        }

        /** The points of the line between two distances along it, ends interpolated. */
        fun slice(from: Double, to: Double): List<GeoPoint> {
            val out = ArrayList<GeoPoint>()
            out += pointAt(from)
            for (i in 1 until points.size - 1) if (cumulative[i] > from && cumulative[i] < to) out += points[i]
            out += pointAt(to)
            return out
        }

        private fun pointAt(along: Double): GeoPoint {
            if (along <= 0.0) return points.first()
            if (along >= total) return points.last()
            var i = 1
            while (cumulative[i] < along) i++
            val seg = cumulative[i] - cumulative[i - 1]
            val t = if (seg == 0.0) 0.0 else (along - cumulative[i - 1]) / seg
            val a = points[i - 1]
            val b = points[i]
            return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
        }

        companion object {
            private const val METERS_PER_DEGREE = 111_320.0
        }
    }

    private class Projection(val distance: Double, val along: Double)
}

/**
 * The mid-trip "faster route found" decision: compare a fresh route against
 * what is left of the current one, both with the delay live speeds add, and
 * remember offers the driver turned down.
 */
object FasterRoute {
    /** A new route must save at least this much to be offered. */
    const val MIN_SAVING_SECONDS = 5 * 60.0

    /** After Dismiss, the same route isn't offered again for this long. */
    const val DISMISS_MILLIS = 10 * 60 * 1000L

    /** How often a trip checks for a faster route. */
    const val CHECK_MILLIS = 3 * 60 * 1000L

    /** Seconds saved by taking the candidate; negative when it is slower. */
    fun savingSeconds(
        currentRemainingSeconds: Double,
        currentDelaySeconds: Double,
        candidateSeconds: Double,
        candidateDelaySeconds: Double,
    ): Double = (currentRemainingSeconds + currentDelaySeconds) - (candidateSeconds + candidateDelaySeconds)

    /** Whole minutes to say in the banner, or null when the saving is too small to offer. */
    fun offerMinutes(savingSeconds: Double, minSeconds: Double = MIN_SAVING_SECONDS): Int? =
        if (savingSeconds >= minSeconds) (savingSeconds / 60).roundToInt() else null

    /** Coarse ~200 m grid cells a route passes through; two routes along the same roads share nearly all of them. */
    fun cells(route: List<GeoPoint>): Set<Long> = route.mapTo(HashSet()) {
        (Math.round(it.lat / CELL_DEGREES) shl 32) xor (Math.round(it.lon / CELL_DEGREES) and 0xFFFFFFFFL)
    }

    /** Routes the driver dismissed, so the same one isn't offered again for a while. */
    class Dismissals(private val holdMillis: Long = DISMISS_MILLIS) {
        private val dismissed = ArrayList<Pair<Set<Long>, Long>>()

        fun dismiss(route: List<GeoPoint>, now: Long) {
            dismissed += cells(route) to now + holdMillis
        }

        /** True when [route] follows (nearly) the same roads as one dismissed less than [holdMillis] ago. */
        fun isDismissed(route: List<GeoPoint>, now: Long): Boolean {
            dismissed.removeAll { it.second <= now }
            val mine = cells(route)
            if (mine.isEmpty()) return false
            return dismissed.any { (cells, _) -> mine.count { it in cells } >= mine.size * SAME_ROUTE_SHARE }
        }
    }

    private const val CELL_DEGREES = 0.002
    private const val SAME_ROUTE_SHARE = 0.85
}
