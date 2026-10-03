package app.southbound.core.routing

/**
 * Phase 6: the intermediate stops of a multi-stop trip, kept as a plain list
 * of any type [T] (the app uses its Place) so the list logic is testable
 * without Android. The destination is not in the list; it always comes last.
 */
object TripStops {
    const val MAX_STOPS = 8

    /** [stop] goes in just before the destination. Same spot as an existing stop (or the full list) leaves it unchanged. */
    fun <T> add(stops: List<T>, stop: T, sameSpot: (T, T) -> Boolean): List<T> =
        if (stops.size >= MAX_STOPS || stops.any { sameSpot(it, stop) }) stops else stops + stop

    fun <T> remove(stops: List<T>, index: Int): List<T> =
        if (index in stops.indices) stops.filterIndexed { i, _ -> i != index } else stops

    /** Swaps the stop at [index] with its neighbour ([delta] is -1 for up, +1 for down); out of range is a no-op. */
    fun <T> move(stops: List<T>, index: Int, delta: Int): List<T> {
        val target = index + delta
        if (index !in stops.indices || target !in stops.indices || delta == 0) return stops
        return stops.toMutableList().also { it[index] = stops[target]; it[target] = stops[index] }
    }

    /** The waypoints to route through, in order: the stops, then the destination. */
    fun <T> route(stops: List<T>, destination: T): List<T> = stops + destination

    /**
     * What is left to visit after skipping the next waypoint. The destination
     * can't be skipped, so a lone remaining waypoint comes back unchanged.
     */
    fun <T> skipNext(remaining: List<T>): List<T> = if (remaining.size > 1) remaining.drop(1) else remaining

    /** True when there is a stop to skip, i.e. more than the destination remains. */
    fun canSkip(remainingCount: Int): Boolean = remainingCount > 1
}
