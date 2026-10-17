package app.southbound.core.routing

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * "Also offer the shorter way" (owner, October 2026). Valhalla picks driving routes by a cost that favours main
 * roads over residential streets, so a side street that is shorter, and faster by Valhalla's own estimate, can be
 * missing from the options: on a short town trip measured on the release's tiles it chose a state route (2:07) over
 * a residential street that cuts the corner (1:46), and neither alternative used the street. The preview asks once
 * more, for one route, with distance weighed in ([USE_DISTANCE], Valhalla's `use_distance`) and offers it too when
 * it is both faster and shorter than the first route and not one already offered ([merge]). The first route stays
 * first.
 *
 * At 0.5 that town trip's street comes back first (at 0.3 only as an alternative). Over 61 random trips of 5 to
 * 140 km in one region on the release's tiles, 0.5 added an option to 5: the town trip's, and long trips a few
 * seconds faster and 1 to 24 km shorter.
 */
object ShorterRoute {
    /** Valhalla's `use_distance` for the second request: 0 is fastest by its cost, 1 is shortest. */
    const val USE_DISTANCE = 0.5

    /** At most this many options in all: the shorter way takes the last alternative's place (the map has three colours). */
    const val MAX_OPTIONS = 3

    /** Two routes are the same when this share of one's length runs within [SAME_METERS] of the other. */
    const val SAME_SHARE = 0.9
    const val SAME_METERS = 30.0

    /** What [merge] needs of a route: its time and length by Valhalla's estimate, and its line as (lat, lon). */
    data class Option(val seconds: Double, val meters: Double, val line: List<Pair<Double, Double>>)

    /**
     * [options] (best first) with [shorter] added last when it is faster and shorter than the first and not the
     * same as any of them; else [options] as they are. [describe] reads a route.
     */
    fun <T> merge(options: List<T>, shorter: T?, describe: (T) -> Option): List<T> {
        if (shorter == null || options.isEmpty()) return options
        val best = describe(options.first())
        val candidate = describe(shorter)
        if (candidate.seconds >= best.seconds || candidate.meters >= best.meters) return options
        if (options.any { same(candidate.line, describe(it).line) }) return options
        return options.take(MAX_OPTIONS - 1) + shorter
    }

    /**
     * Where [shorter] sits in [ranked] (what [merge] returned, maybe reordered) when merge added it; null when it
     * didn't. Found by identity, not equality: Valhalla's distance-weighted answer can equal the first route, which
     * merge refuses, and an equal copy is no shorter way. Never the only option, as the preview tells the shorter
     * way apart from another one.
     */
    fun <T> indexIn(ranked: List<T>, shorter: T?): Int? {
        if (shorter == null || ranked.size < 2) return null
        return ranked.indexOfFirst { it === shorter }.takeIf { it >= 0 }
    }

    /** True when at least [SAME_SHARE] of [a]'s length lies within [SAME_METERS] of the line [b]. */
    fun same(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): Boolean {
        if (a.size < 2) return true
        if (b.size < 2) return false
        var total = 0.0
        var near = 0.0
        for (i in 1 until a.size) {
            val length = meters(a[i - 1], a[i])
            val mid = (a[i - 1].first + a[i].first) / 2 to (a[i - 1].second + a[i].second) / 2
            total += length
            if (distanceToLine(mid, b) <= SAME_METERS) near += length
        }
        return total == 0.0 || near / total >= SAME_SHARE
    }

    private const val METERS_PER_DEGREE = 111_320.0

    private fun meters(p: Pair<Double, Double>, q: Pair<Double, Double>): Double {
        val dy = (q.first - p.first) * METERS_PER_DEGREE
        val dx = (q.second - p.second) * METERS_PER_DEGREE * cos(Math.toRadians((p.first + q.first) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /** Metres from [p] to the nearest point of the polyline [line], on a flat projection around [p]. */
    private fun distanceToLine(p: Pair<Double, Double>, line: List<Pair<Double, Double>>): Double {
        val kx = METERS_PER_DEGREE * cos(Math.toRadians(p.first))
        var best = Double.MAX_VALUE
        for (i in 1 until line.size) {
            val ax = (line[i - 1].second - p.second) * kx
            val ay = (line[i - 1].first - p.first) * METERS_PER_DEGREE
            val bx = (line[i].second - p.second) * kx
            val by = (line[i].first - p.first) * METERS_PER_DEGREE
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + t * dx
            val y = ay + t * dy
            val d = x * x + y * y
            if (d < best) best = d
        }
        return sqrt(best)
    }
}
