package app.southbound.core.transit

/**
 * The trips one search may use, arranged for RAPTOR: per pattern and service
 * day, trips sorted by time with every stop time decoded.
 *
 * Times are seconds after midnight of the search day; a trip from the day
 * before carries a shift of -86400, one from the next day +86400.
 *
 * [reversed] builds the mirror image used for arrive-by searches: stop
 * order flipped, times negated, boarding and alighting swapped. A forward
 * search over it finds the latest departures that still arrive in time.
 */
class Timetable internal constructor(
    val network: TransitNetwork,
    internal val routes: List<TimetableRoute>,
    val isReversed: Boolean,
) {
    /** RAPTOR routes through each stop, and the stop's position in each. */
    internal val stopRoutes: Array<IntArray>
    internal val stopPositions: Array<IntArray>

    init {
        val routesAt = Array(network.stopCount) { ArrayList<Int>() }
        val positionsAt = Array(network.stopCount) { ArrayList<Int>() }
        routes.forEachIndexed { r, route ->
            route.stops.forEachIndexed { i, stop ->
                routesAt[stop].add(r)
                positionsAt[stop].add(i)
            }
        }
        stopRoutes = Array(network.stopCount) { routesAt[it].toIntArray() }
        stopPositions = Array(network.stopCount) { positionsAt[it].toIntArray() }
    }

    val tripCount: Int get() = routes.sumOf { it.tripCount }

    fun reversed(): Timetable = Timetable(network, routes.map { it.reversed() }, !isReversed)

    /** Walking transfers, flipped for a reversed timetable. */
    internal val transferTargets: Array<IntArray>
    internal val transferSeconds: Array<IntArray>

    init {
        if (!isReversed) {
            transferTargets = network.transferTargets
            transferSeconds = network.transferSeconds
        } else {
            val targets = Array(network.stopCount) { ArrayList<Int>() }
            val seconds = Array(network.stopCount) { ArrayList<Int>() }
            for (from in 0 until network.stopCount) {
                network.transferTargets[from].forEachIndexed { i, to ->
                    targets[to].add(from)
                    seconds[to].add(network.transferSeconds[from][i])
                }
            }
            transferTargets = Array(network.stopCount) { targets[it].toIntArray() }
            transferSeconds = Array(network.stopCount) { seconds[it].toIntArray() }
        }
    }

    companion object {
        /**
         * Builds a timetable from trips grouped by day shift (seconds added to
         * every time, e.g. -86400 for yesterday's trips).
         */
        fun build(network: TransitNetwork, tripsByShift: Map<Int, List<TripRow>>): Timetable {
            val routes = ArrayList<TimetableRoute>()
            for ((shift, trips) in tripsByShift) {
                for ((patternId, patternTrips) in trips.groupBy { it.pattern }) {
                    val pattern = network.pattern(patternId) ?: continue
                    routes.add(TimetableRoute.of(pattern, shift, patternTrips))
                }
            }
            return Timetable(network, routes, isReversed = false)
        }
    }
}

/** One pattern's trips on one service day; times are trip-major arrays. */
internal class TimetableRoute(
    val pattern: TransitPattern,
    val shift: Int,
    val trips: List<TripRow>,
    val stops: IntArray,
    val pickup: BooleanArray,
    val dropOff: BooleanArray,
    val arrivals: IntArray,
    val departures: IntArray,
    /** For a reversed route, the forward route it mirrors. */
    val original: TimetableRoute? = null,
) {
    val stopCount: Int get() = stops.size
    val tripCount: Int get() = trips.size

    fun arrival(trip: Int, position: Int): Int = arrivals[trip * stops.size + position]
    fun departure(trip: Int, position: Int): Int = departures[trip * stops.size + position]

    /** The first trip leaving [position] at or after [time], or -1. Trips never overtake. */
    fun earliestTrip(position: Int, time: Int, before: Int = trips.size): Int {
        var lo = 0
        var hi = before
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (departure(mid, position) < time) lo = mid + 1 else hi = mid
        }
        return if (lo < before) lo else -1
    }

    fun reversed(): TimetableRoute {
        val n = stops.size
        val t = trips.size
        val arr = IntArray(n * t)
        val dep = IntArray(n * t)
        for (trip in 0 until t) {
            for (i in 0 until n) {
                val target = (t - 1 - trip) * n + (n - 1 - i)
                arr[target] = -departures[trip * n + i]
                dep[target] = -arrivals[trip * n + i]
            }
        }
        return TimetableRoute(
            pattern, shift, trips.asReversed(), stops.reversedArray(),
            pickup = dropOff.reversedArray(), dropOff = pickup.reversedArray(),
            arrivals = arr, departures = dep, original = this,
        )
    }

    companion object {
        fun of(pattern: TransitPattern, shift: Int, trips: List<TripRow>): TimetableRoute {
            val n = pattern.stops.size
            val sorted = trips.sortedBy { it.start }
            val arr = IntArray(n * sorted.size)
            val dep = IntArray(n * sorted.size)
            sorted.forEachIndexed { i, trip -> TransitCodec.times(trip.start, trip.times, arr, dep, i * n, shift) }
            return TimetableRoute(pattern, shift, sorted, pattern.stops, pattern.pickup, pattern.dropOff, arr, dep)
        }
    }
}
