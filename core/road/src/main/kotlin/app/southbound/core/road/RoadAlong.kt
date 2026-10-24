package app.southbound.core.road

import app.southbound.core.search.LatLon
import app.southbound.core.search.NearbyBox
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * "Along my route": the spots within a corridor of a route's geometry, in the order the driver meets them. Plain
 * Kotlin with the pack's box read passed in as a function, so it is tested on the JVM against a list of spots.
 *
 * The route is cut into pieces of about [CHUNK_METERS]. Each piece has a box (its points' extent, grown by the
 * corridor): the pack is asked for the spots in that box, a coarse prefilter, and each one is then measured against
 * the segments of its own piece only (a point-to-segment distance on a flat projection around the spot, which is
 * plenty for the few kilometres of a corridor), so a route of 4,000 kilometres costs a few hundred small reads and
 * a few million multiplications, not every spot against every segment.
 *
 * Nothing here touches the network or logs: the route and the spots stay in the phone's memory.
 */
object RoadAlong {

    /** About how much route one pack read covers. Long enough to be few reads, short enough that the box is not the whole country. */
    const val CHUNK_METERS = 15_000.0

    /**
     * The most spots one read asks for. A read that returns exactly this many was cut (the pack answers in category
     * order, not distance), so the piece's box is split in two and each half read ([readPiece]).
     */
    const val CHUNK_ROWS = 3_000

    /** A box whose longer side is under this many metres is not split again; its capped rows are kept. */
    const val MIN_SPLIT_METERS = 2_000.0

    /** The most reads one piece may take when splitting; past it the capped rows of what is left are kept. */
    const val MAX_READS_PER_PIECE = 64

    private const val METERS_PER_DEGREE = 111_195.0

    /** A stretch of the route: points [first] to [last] (inclusive), the segments between them, and the box round them. */
    class Chunk(val first: Int, val last: Int, val box: NearbyBox, val startMeters: Double, val endMeters: Double)

    /** A route with distances along it. Fewer than two usable points is a route with no length and no chunks. */
    class Route(points: List<LatLon>) {
        /** The points with the ones that repeat or are not finite removed. */
        val points: List<LatLon> = points.filter { it.lat.isFinite() && it.lon.isFinite() }.let { all ->
            all.filterIndexed { i, p -> i == 0 || p != all[i - 1] }
        }

        /** Metres from the first point to each point. */
        val cumulative: DoubleArray = DoubleArray(this.points.size).also { acc ->
            for (i in 1 until this.points.size) acc[i] = acc[i - 1] + distance(this.points[i - 1], this.points[i])
        }

        val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

        /**
         * The pieces of the route, in order, from [fromMeters] along it (the piece the driver is on included), each box
         * grown by [corridorMeters]. A piece shares its last point with the next one's first.
         */
        fun chunks(corridorMeters: Double, fromMeters: Double = 0.0): List<Chunk> {
            if (points.size < 2) return emptyList()
            val out = ArrayList<Chunk>()
            var start = 0
            while (start < points.size - 1) {
                var end = start + 1
                while (end < points.size - 1 && cumulative[end] - cumulative[start] < CHUNK_METERS) end++
                if (cumulative[end] >= fromMeters) out += chunk(start, end, corridorMeters)
                start = end
            }
            return out
        }

        private fun chunk(first: Int, last: Int, corridorMeters: Double): Chunk {
            var south = Double.MAX_VALUE
            var north = -Double.MAX_VALUE
            var west = Double.MAX_VALUE
            var east = -Double.MAX_VALUE
            for (i in first..last) {
                val p = points[i]
                if (p.lat < south) south = p.lat
                if (p.lat > north) north = p.lat
                if (p.lon < west) west = p.lon
                if (p.lon > east) east = p.lon
            }
            val padLat = corridorMeters / METERS_PER_DEGREE
            // A degree of longitude is shortest at the pole-most edge of the box: pad for that one.
            val widest = maxOf(Math.abs(south), Math.abs(north))
            val padLon = corridorMeters / (METERS_PER_DEGREE * cos(Math.toRadians(widest)).coerceAtLeast(0.01))
            return Chunk(first, last, NearbyBox(south - padLat, north + padLat, west - padLon, east + padLon), cumulative[first], cumulative[last])
        }

        /**
         * Where ([lat], [lon]) sits relative to the segments of [chunk]: metres [Located.along] the route and
         * [Located.offset] off it, at the nearest place on those segments. Null when the route has no segment there.
         */
        fun locate(lat: Double, lon: Double, chunk: Chunk): Located? {
            var best: Located? = null
            val k = cos(Math.toRadians(lat)) * METERS_PER_DEGREE
            for (i in chunk.first until chunk.last) {
                val a = points[i]
                val b = points[i + 1]
                // Metres east and north of the spot.
                val ax = (a.lon - lon) * k
                val ay = (a.lat - lat) * METERS_PER_DEGREE
                val dx = (b.lon - a.lon) * k
                val dy = (b.lat - a.lat) * METERS_PER_DEGREE
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val cx = ax + t * dx
                val cy = ay + t * dy
                val offset = sqrt(cx * cx + cy * cy)
                if (best == null || offset < best.offset) {
                    best = Located(cumulative[i] + t * (cumulative[i + 1] - cumulative[i]), offset)
                }
            }
            return best
        }
    }

    /** Where a point sits relative to a route: [along] it from the start, [offset] metres off it. */
    data class Located(val along: Double, val offset: Double)

    /**
     * The spots within [corridorMeters] of [route] and at least [aheadMeters] along it (spots already passed are
     * left out), in the order the driver meets them, at most [limit]. [fetch] returns the spots in a box, each place
     * once is made of them here ([RoadPlaces.distinct]). A spot the route passes twice is listed at its first pass in
     * the corridor. [RoadAlongResult.aheadMeters] is the distance from [aheadMeters] on.
     *
     * [isActive] is asked before each piece; once it says no, the search stops and returns what it has, which the
     * caller (cancelled) must not show.
     *
     * Pieces are read in route order and the search stops as soon as [limit] spots lie before the end of the piece
     * just read: nothing further along the route can come before them.
     */
    fun search(
        route: Route,
        corridorMeters: Double,
        aheadMeters: Double,
        limit: Int,
        fetch: (NearbyBox) -> List<RoadSpot>,
        isActive: () -> Boolean = { true },
    ): List<RoadAlongResult> {
        if (limit <= 0 || corridorMeters <= 0.0) return emptyList()
        val found = HashMap<Long, Pair<RoadSpot, Located>>()
        for (chunk in route.chunks(corridorMeters, aheadMeters)) {
            // Between pieces, a caller that has been cancelled stops the reading; what is returned then is partial and is dropped.
            if (!isActive()) break
            for (spot in readPiece(chunk.box, fetch)) {
                val at = route.locate(spot.lat, spot.lon, chunk) ?: continue
                if (at.offset > corridorMeters) continue
                val known = found[spot.id]
                if (known == null || at.along < known.second.along) found[spot.id] = spot to at
            }
            // Everything met later is at least this far along: the first [limit] by along are settled.
            val settled = found.values.count { it.second.along >= aheadMeters && it.second.along <= chunk.endMeters }
            if (settled >= limit * SETTLE_FACTOR) break
        }
        val ahead = found.values.filter { it.second.along >= aheadMeters }
        // Fold a place the pack filed twice (pumps and truck stop) after measuring, at the row that was met first.
        val byPlace = LinkedHashMap<PlaceKey, MutableList<Pair<RoadSpot, Located>>>()
        for (item in ahead.sortedWith(compareBy({ it.second.along }, { it.second.offset }, { it.first.id }))) {
            byPlace.getOrPut(PlaceKey(item.first)) { mutableListOf() }.add(item)
        }
        return byPlace.values
            .map { rows ->
                val folded = RoadPlaces.fold(rows.map { it.first })
                val first = rows.first().second
                RoadAlongResult(folded, first.along, first.along - aheadMeters, first.offset)
            }
            .sortedWith(compareBy({ it.alongMeters }, { it.offsetMeters }, { it.spot.id }))
            .take(limit)
    }

    /**
     * The spots [fetch] finds in [box]. A read that comes back with [CHUNK_ROWS] rows was cut, and the pack cuts by
     * category, so a dense box could lose fuel or truck stops while keeping water: the box is split across its longer
     * side and each half read, and so on down to [MIN_SPLIT_METERS] or [MAX_READS_PER_PIECE] reads, where the capped
     * rows are kept. A spot on the line between the halves may come twice; the caller keeps each id once.
     */
    internal fun readPiece(box: NearbyBox, fetch: (NearbyBox) -> List<RoadSpot>): List<RoadSpot> {
        var reads = 0
        fun read(b: NearbyBox): List<RoadSpot> {
            reads++
            val rows = fetch(b)
            if (rows.size < CHUNK_ROWS || reads >= MAX_READS_PER_PIECE) return rows
            val midLat = (b.south + b.north) / 2
            val heightMeters = (b.north - b.south) * METERS_PER_DEGREE
            val widthMeters = (b.east - b.west) * METERS_PER_DEGREE * cos(Math.toRadians(midLat))
            if (maxOf(heightMeters, widthMeters) < MIN_SPLIT_METERS) return rows
            val (first, second) = if (heightMeters >= widthMeters) {
                NearbyBox(b.south, midLat, b.west, b.east) to NearbyBox(midLat, b.north, b.west, b.east)
            } else {
                val midLon = (b.west + b.east) / 2
                NearbyBox(b.south, b.north, b.west, midLon) to NearbyBox(b.south, b.north, midLon, b.east)
            }
            return read(first) + read(second)
        }
        return read(box)
    }

    /**
     * Stop once this many times [limit] spots are settled: a place folded away from two rows leaves fewer than were
     * counted, and the extra keeps the list full.
     */
    private const val SETTLE_FACTOR = 2

    private fun distance(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat) * METERS_PER_DEGREE
        val dLon = (b.lon - a.lon) * METERS_PER_DEGREE * cos(Math.toRadians((a.lat + b.lat) / 2))
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
