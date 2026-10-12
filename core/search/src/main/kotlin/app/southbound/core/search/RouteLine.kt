package app.southbound.core.search

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A route (or one step of it) with distances along it, for placing
 * landmarks and alerts relative to the drive. Each distance uses a flat
 * projection around the point being measured, which is plenty for the tens
 * to hundreds of metres that matter here. core/traffic has similar helpers
 * (Geo), but this module doesn't depend on it, so a small copy lives here.
 */
class RouteLine(val points: List<LatLon>) {

    /** Metres from the first point to each point. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) acc[i] = acc[i - 1] + distanceMeters(points[i - 1], points[i])
    }

    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    /** Where a point sits relative to the line: [along] it from the start, [offset] metres off it. */
    data class Located(val along: Double, val offset: Double)

    /**
     * The nearest place on the line to [p], looking only at the part between
     * [from] and [to] metres along (so a route that loops back near itself
     * doesn't confuse progress). Null for a line without segments.
     */
    fun locate(p: LatLon, from: Double = 0.0, to: Double = Double.POSITIVE_INFINITY): Located? {
        if (points.size < 2) return null
        var best: Located? = null
        val k = cos(Math.toRadians(p.lat)) * METERS_PER_DEGREE
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < from || cumulative[i] > to) continue
            val a = points[i]
            val b = points[i + 1]
            // Metres east and north of p.
            val ax = (a.lon - p.lon) * k
            val ay = (a.lat - p.lat) * METERS_PER_DEGREE
            val bx = (b.lon - p.lon) * k
            val by = (b.lat - p.lat) * METERS_PER_DEGREE
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            // Keep the nearest point inside the [from, to] window.
            val segment = cumulative[i + 1] - cumulative[i]
            val tMin = if (segment > 0) ((from - cumulative[i]) / segment).coerceIn(0.0, 1.0) else 0.0
            val tMax = if (segment > 0) ((to - cumulative[i]) / segment).coerceIn(0.0, 1.0) else 1.0
            val t = if (len2 == 0.0) tMin else (-(ax * dx + ay * dy) / len2).coerceIn(tMin, tMax)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val offset = sqrt(cx * cx + cy * cy)
            if (best == null || offset < best.offset) {
                best = Located(cumulative[i] + t * (cumulative[i + 1] - cumulative[i]), offset)
            }
        }
        return best
    }

    /**
     * Each time the line passes within [withinMeters] of [p], as the nearest place on that stretch, in order
     * along the line: twice for a point beside a road the line drives out and back along. Stretches less than
     * [joinMeters] apart along the line are one pass (a road that bends away from the point and back). Empty
     * when the line never comes that close.
     */
    fun passes(p: LatLon, withinMeters: Double, joinMeters: Double): List<Located> {
        if (points.size < 2) return emptyList()
        val k = cos(Math.toRadians(p.lat)) * METERS_PER_DEGREE
        val r2 = withinMeters * withinMeters
        // Each stretch as [from, to] metres along the line; segments come in order, so stretches do too.
        val stretches = ArrayList<DoubleArray>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            // Metres east and north of p.
            val ax = (a.lon - p.lon) * k
            val ay = (a.lat - p.lat) * METERS_PER_DEGREE
            val dx = (b.lon - a.lon) * k
            val dy = (b.lat - a.lat) * METERS_PER_DEGREE
            val len2 = dx * dx + dy * dy
            val t0: Double
            val t1: Double
            if (len2 == 0.0) {
                if (ax * ax + ay * ay > r2) continue
                t0 = 0.0
                t1 = 0.0
            } else {
                // The part of the segment inside the circle: |a + t d|^2 <= r^2, a quadratic in t.
                val half = ax * dx + ay * dy
                val disc = half * half - len2 * (ax * ax + ay * ay - r2)
                if (disc < 0) continue
                val root = sqrt(disc)
                t0 = ((-half - root) / len2).coerceAtLeast(0.0)
                t1 = ((-half + root) / len2).coerceAtMost(1.0)
                if (t0 > t1) continue
            }
            val segment = cumulative[i + 1] - cumulative[i]
            val from = cumulative[i] + t0 * segment
            val to = cumulative[i] + t1 * segment
            val last = stretches.lastOrNull()
            if (last != null && from - last[1] < joinMeters) last[1] = maxOf(last[1], to) else stretches += doubleArrayOf(from, to)
        }
        return stretches.mapNotNull { locate(p, it[0], it[1]) }
    }

    /** The point [along] metres from the start, clamped to the line's ends. Null for an empty line. */
    fun pointAt(along: Double): LatLon? {
        if (points.isEmpty()) return null
        if (points.size == 1 || along <= 0.0) return points.first()
        if (along >= length) return points.last()
        var i = 1
        while (i < points.size - 1 && cumulative[i] < along) i++
        val a = points[i - 1]
        val b = points[i]
        val segment = cumulative[i] - cumulative[i - 1]
        val t = if (segment > 0) (along - cumulative[i - 1]) / segment else 0.0
        return LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }

    /**
     * The directions of travel, in degrees clockwise from north (0 up to, not including,
     * 360), of every segment of the line that passes within [withinMeters] of [p]. At a corner
     * that is both legs, so something beside it can be matched against the one it faces.
     * Segments with no length are skipped; empty when none is that close.
     */
    fun bearingsNear(p: LatLon, withinMeters: Double): List<Double> {
        val k = cos(Math.toRadians(p.lat)) * METERS_PER_DEGREE
        val found = mutableListOf<Double>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            // Metres east and north of p.
            val ax = (a.lon - p.lon) * k
            val ay = (a.lat - p.lat) * METERS_PER_DEGREE
            val dx = (b.lon - a.lon) * k
            val dy = (b.lat - a.lat) * METERS_PER_DEGREE
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) continue
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            if (sqrt(cx * cx + cy * cy) <= withinMeters) {
                found += (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
            }
        }
        return found
    }

    /**
     * Boxes covering the line in pieces of about [chunkMeters], each grown by
     * [padMeters]: small boxes keep a long diagonal route from selecting a
     * whole region's worth of rows.
     */
    fun boxes(chunkMeters: Double = 2_000.0, padMeters: Double = 80.0): List<NearbyBox> {
        if (points.isEmpty()) return emptyList()
        val boxes = mutableListOf<NearbyBox>()
        var start = 0
        while (start < points.size) {
            var end = start
            while (end + 1 < points.size && cumulative[end + 1] - cumulative[start] <= chunkMeters) end++
            if (end == start && end + 1 < points.size) end++
            val piece = points.subList(start, end + 1)
            val padLat = padMeters / METERS_PER_DEGREE
            val midLat = piece.sumOf { it.lat } / piece.size
            val padLon = padMeters / (METERS_PER_DEGREE * cos(Math.toRadians(midLat)).coerceAtLeast(0.01))
            boxes += NearbyBox(
                south = piece.minOf { it.lat } - padLat,
                north = piece.maxOf { it.lat } + padLat,
                west = piece.minOf { it.lon } - padLon,
                east = piece.maxOf { it.lon } + padLon,
            )
            if (end == points.size - 1) break
            start = end
        }
        return boxes
    }

    companion object {
        /** Metres per degree of latitude (mean Earth radius). */
        const val METERS_PER_DEGREE = 111_195.0

        fun distanceMeters(a: LatLon, b: LatLon): Double {
            val dLat = (b.lat - a.lat) * METERS_PER_DEGREE
            val dLon = (b.lon - a.lon) * METERS_PER_DEGREE * cos(Math.toRadians((a.lat + b.lat) / 2))
            return sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
