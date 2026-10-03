package app.southbound.core.traffic

import kotlin.math.max

/**
 * Live traffic on a trip's route, matched once and then trimmed to what is
 * left of the trip.
 *
 * Matching NYC DOT links onto a route ([RouteTraffic.slowdowns]) projects both
 * ends of every link onto the route line, so it costs links x route points:
 * about 10 ms on a desktop JVM for 200 links on a 5,000-point route, likely
 * several times that on a phone. [match] does it only when the route or the
 * traffic snapshot changes (a reroute, or a feed poll about once a minute).
 * [ahead] (under 1 ms on the same route) then only finds the driver on the
 * route and trims: links already driven drop off, and the one being driven
 * counts only the part still ahead, so the trip's "+X min" shrinks as a slow
 * stretch is driven instead of dropping all at once when it is entered.
 */
class TripTraffic private constructor(
    private val line: RouteTraffic.Line?,
    private val links: List<RouteTraffic.MatchedLink>,
    private val incidents: List<Pair<Double, Incident>>,
) {
    /** Metres from the start of the route to its end. */
    val lengthMeters: Double get() = line?.total ?: 0.0

    /**
     * How far along the route [p] is, in metres. [notBefore] is the last
     * known position: the search starts [BACKTRACK_METERS] behind it, so on a
     * route that comes back along the same road (out to a stop and back) the
     * driver isn't placed on the way out again. A point far from the route
     * leaves the position at [notBefore].
     */
    fun along(p: GeoPoint, notBefore: Double = 0.0): Double {
        val line = line ?: return 0.0
        val hit = line.project(p, max(0.0, notBefore - BACKTRACK_METERS))
        return if (hit.distance.isInfinite()) notBefore.coerceIn(0.0, line.total) else hit.along
    }

    /**
     * The slow stretches still ahead of [along] metres, and the delay they
     * add: links behind it are dropped, a link it is part way along is cut at
     * it and counts that share of its delay.
     */
    fun ahead(along: Double): RouteSlowdowns {
        val line = line ?: return RouteSlowdowns.NONE
        val segments = ArrayList<SlowSegment>()
        var delay = 0.0
        for (link in links) {
            if (link.end <= along) continue
            val from = max(link.start, along)
            segments += link.segment(line, from)
            delay += link.delaySeconds * remainingShare(link, from)
        }
        if (segments.isEmpty()) return RouteSlowdowns.NONE
        return RouteSlowdowns(segments, delay)
    }

    /** [ahead] from [p], a point on (or near) the route. */
    fun ahead(p: GeoPoint, notBefore: Double = 0.0): RouteSlowdowns = ahead(along(p, notBefore))

    /** The incidents on the route not yet passed at [along] metres, in the order they come. */
    fun incidentsAhead(along: Double): List<Incident> = incidents.filter { it.first >= along }.map { it.second }

    private fun remainingShare(link: RouteTraffic.MatchedLink, from: Double): Double {
        val length = link.end - link.start
        return if (length <= 0.0) 1.0 else ((link.end - from) / length).coerceIn(0.0, 1.0)
    }

    companion object {
        /** No route, or no traffic: nothing ahead. */
        val NONE = TripTraffic(null, emptyList(), emptyList())

        /** How far behind the last known position [along] still looks, for GPS jitter and a short reverse. */
        const val BACKTRACK_METERS = 200.0

        /**
         * Lays [speeds] and [incidents] on the whole of [route] once. Call it
         * off the main thread when the route or the traffic snapshot changes,
         * then use [ahead] and [incidentsAhead] as the trip goes on.
         */
        fun match(
            route: List<GeoPoint>,
            speeds: List<SpeedLink>,
            incidents: List<Incident> = emptyList(),
            incidentMeters: Double = RouteTraffic.INCIDENT_METERS,
        ): TripTraffic {
            if (route.size < 2) return NONE
            val line = RouteTraffic.Line(route)
            val matched = if (speeds.isEmpty()) emptyList() else RouteTraffic.match(line, speeds)
            val onRoute = incidents
                .map { line.project(it.location) to it }
                .filter { (hit, _) -> hit.distance <= incidentMeters }
                .sortedBy { (hit, _) -> hit.along }
                .map { (hit, incident) -> hit.along to incident }
            return TripTraffic(line, matched, onRoute)
        }
    }
}

/**
 * Orders route options by what they take with live traffic, so the first
 * option (the one preview selects) is the fastest once delay is added.
 *
 * Valhalla on the phone ranks routes without live speeds (see
 * docs/traffic-routing-spike.md). An alternative is moved to the front only
 * when it beats Valhalla's first choice by at least [MIN_GAIN_SECONDS] with
 * delay counted, so a minute of noise in the feed doesn't flip the order; the
 * other options keep Valhalla's order behind it.
 */
object TrafficRanking {
    /** An alternative must be at least this much faster, with traffic, to go ahead of Valhalla's first route. */
    const val MIN_GAIN_SECONDS = 120.0

    /**
     * The order to show routes in: indices into [durationsSeconds]. Index 0 of
     * the result is the fastest with [delaysSeconds] added, when it gains at
     * least [minGainSeconds] on Valhalla's first route; otherwise the order is
     * unchanged. [mayLead] says which routes may go first at all (e.g. not one
     * with more plate readers when the driver avoids them); route 0 always may.
     */
    fun order(
        durationsSeconds: List<Double>,
        delaysSeconds: List<Double>,
        mayLead: (Int) -> Boolean = { true },
        minGainSeconds: Double = MIN_GAIN_SECONDS,
    ): List<Int> {
        val n = durationsSeconds.size
        if (n < 2) return List(n) { it }
        fun total(i: Int) = durationsSeconds[i] + delaysSeconds.getOrElse(i) { 0.0 }
        val best = (1 until n).filter(mayLead).minByOrNull(::total)
        if (best == null || total(0) - total(best) < minGainSeconds) return List(n) { it }
        return listOf(best) + (0 until n).filter { it != best }
    }

    /** [items] in [order] (from [order]); returned as is when the sizes don't match. */
    fun <T> reorder(items: List<T>, order: List<Int>): List<T> =
        if (items.size != order.size) items else order.map { items[it] }
}
