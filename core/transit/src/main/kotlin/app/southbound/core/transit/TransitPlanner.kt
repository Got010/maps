package app.southbound.core.transit

import java.time.LocalDate

/** Where the trips for a search come from: the pack on the phone, or a test. */
fun interface TripSource {
    /** Trips of [services] whose first departure is within [startFrom, startTo] (service-day seconds). */
    fun trips(services: Set<Int>, startFrom: Int, startTo: Int): List<TripRow>
}

/** One step of a planned trip. Times are seconds after midnight of the search day. */
sealed interface JourneyLeg {
    val depart: Int
    val arrive: Int

    /** On foot. A null stop is the origin or destination point. */
    data class Walk(
        val from: TransitStop?,
        val to: TransitStop?,
        override val depart: Int,
        override val arrive: Int,
    ) : JourneyLeg

    data class Ride(
        val route: TransitRoute,
        val headsign: String?,
        val trip: TripRow,
        /** Boarding stop, the stops in between, then the alighting stop. */
        val stops: List<TransitStop>,
        /** Arrival time at each of [stops]; the first is the departure. */
        val times: List<Int>,
        override val depart: Int,
        override val arrive: Int,
        /** Seconds between the trip's service day and the search day (-86400 for yesterday's). */
        val shift: Int = 0,
    ) : JourneyLeg {
        val from: TransitStop get() = stops.first()
        val to: TransitStop get() = stops.last()
    }
}

data class Journey(val legs: List<JourneyLeg>) {
    val depart: Int get() = legs.first().depart
    val arrive: Int get() = legs.last().arrive
    val rides: List<JourneyLeg.Ride> get() = legs.filterIsInstance<JourneyLeg.Ride>()
    val transfers: Int get() = (rides.size - 1).coerceAtLeast(0)
    val walkSeconds: Int get() = legs.filterIsInstance<JourneyLeg.Walk>().sumOf { it.arrive - it.depart }
    val durationSeconds: Int get() = arrive - depart
}

/**
 * Plans trips on the phone: finds stops within walking distance of both
 * ends, loads the trips that could matter, and runs [Raptor]. Results trade
 * off arrival time against changes, plus a few later (or, for arrive-by,
 * earlier) departures.
 */
class TransitPlanner(
    private val network: TransitNetwork,
    private val source: TripSource,
    private val walkRadiusMeters: Double = WALK_RADIUS_M,
) {

    fun plan(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        date: LocalDate,
        /** Seconds after midnight of [date]: leave at, or arrive by. */
        time: Int,
        arriveBy: Boolean = false,
        options: Int = 3,
    ): List<Journey> {
        val access = network.walkableStops(fromLat, fromLon, walkRadiusMeters)
        val egress = network.walkableStops(toLat, toLon, walkRadiusMeters)
        val results = ArrayList<Journey>()
        val walkMeters = Walking.distanceMeters(fromLat, fromLon, toLat, toLon)
        if (walkMeters <= MAX_DIRECT_WALK_M) {
            val seconds = Walking.seconds(walkMeters)
            val start = if (arriveBy) time - seconds else time
            results.add(Journey(listOf(JourneyLeg.Walk(null, null, start, start + seconds))))
        }
        if (access.isEmpty() || egress.isEmpty()) return results

        val forward = timetable(date, time, arriveBy)
        val tt = if (arriveBy) forward.reversed() else forward
        val seen = HashSet<List<Int>>()
        var searchTime = time
        repeat(options) {
            val raw = if (arriveBy) {
                Raptor(tt).search(egress, access, -searchTime)
            } else {
                Raptor(tt).search(access, egress, searchTime)
            }
            if (raw.isEmpty()) return@repeat
            val journeys = raw.map { assemble(tt, it) }
            journeys.filter { seen.add(it.rides.map { r -> r.trip.id }) }.forEach(results::add)
            // Next round: just after the earliest-arriving option leaves
            // (or, arriving by, just before the latest-leaving one arrives).
            searchTime = if (arriveBy) {
                journeys.maxOf { it.arrive } - 60
            } else {
                journeys.minBy { it.arrive }.depart + 60
            }
        }
        return pruned(results).sortedWith(compareBy({ it.arrive }, { it.transfers }))
    }

    /**
     * Drops options nobody would pick: another one leaves no earlier and
     * costs no more, counting each change as [CHANGE_PENALTY_SECONDS] and
     * walking at half again its time.
     */
    internal fun pruned(journeys: List<Journey>): List<Journey> {
        fun cost(j: Journey) = j.arrive + CHANGE_PENALTY_SECONDS * j.transfers + j.walkSeconds / 2
        return journeys.filter { j ->
            journeys.none { k ->
                k !== j && k.depart >= j.depart && cost(k) <= cost(j) && (k.depart > j.depart || cost(k) < cost(j))
            }
        }
    }

    /** Trips from yesterday, today and tomorrow that overlap the search window. */
    internal fun timetable(date: LocalDate, time: Int, arriveBy: Boolean): Timetable {
        val windowStart = if (arriveBy) time - HORIZON_SECONDS - MAX_TRIP_SECONDS else time - MAX_TRIP_SECONDS
        val windowEnd = if (arriveBy) time else time + HORIZON_SECONDS
        val byShift = HashMap<Int, List<TripRow>>()
        for (dayOffset in -1..1) {
            val shift = dayOffset * DAY
            val from = windowStart - shift
            val to = windowEnd - shift
            if (to < 0 || from > LATEST_TRIP_START) continue
            val services = network.activeServices(date.plusDays(dayOffset.toLong()))
            if (services.isEmpty()) continue
            byShift[shift] = source.trips(services, from, to)
        }
        return Timetable.build(network, byShift)
    }

    private fun assemble(tt: Timetable, raw: Raptor.RawJourney): Journey {
        // Turn a reversed (arrive-by) result back into forward order.
        val steps = if (!tt.isReversed) {
            raw.legs
        } else {
            raw.legs.asReversed().map { leg ->
                when (leg) {
                    is Raptor.RawLeg.Access -> Raptor.RawLeg.Egress(leg.stop, leg.seconds)
                    is Raptor.RawLeg.Egress -> Raptor.RawLeg.Access(leg.stop, leg.seconds)
                    is Raptor.RawLeg.Walk -> Raptor.RawLeg.Walk(leg.to, leg.from, leg.seconds)
                    is Raptor.RawLeg.Ride -> leg
                }
            }
        }
        val rides = steps.filterIsInstance<Raptor.RawLeg.Ride>().map { ride(tt, it) }
        val legs = ArrayList<JourneyLeg>()
        var rideIndex = 0
        var clock = rides.first().depart
        for (step in steps) {
            when (step) {
                is Raptor.RawLeg.Access ->
                    legs.add(JourneyLeg.Walk(null, network.stop(step.stop), clock - step.seconds, clock))
                is Raptor.RawLeg.Ride -> {
                    val ride = rides[rideIndex++]
                    legs.add(ride)
                    clock = ride.arrive
                }
                is Raptor.RawLeg.Walk -> {
                    legs.add(JourneyLeg.Walk(network.stop(step.from), network.stop(step.to), clock, clock + step.seconds))
                    clock += step.seconds
                }
                is Raptor.RawLeg.Egress ->
                    legs.add(JourneyLeg.Walk(network.stop(step.stop), null, clock, clock + step.seconds))
            }
        }
        // Zero-length walks (the stop is the destination) aren't worth a line.
        return Journey(legs.filter { it !is JourneyLeg.Walk || it.arrive > it.depart || legs.size == 1 })
    }

    private fun ride(tt: Timetable, leg: Raptor.RawLeg.Ride): JourneyLeg.Ride {
        var route = tt.routes[leg.route]
        var trip = leg.trip
        var board = leg.board
        var alight = leg.alight
        route.original?.let { original ->
            trip = route.tripCount - 1 - trip
            board = route.stopCount - 1 - leg.alight
            alight = route.stopCount - 1 - leg.board
            route = original
        }
        val row = route.trips[trip]
        val stops = (board..alight).map { network.stop(route.stops[it]) ?: error("Unknown stop ${route.stops[it]}") }
        val times = (board..alight).map { if (it == board) route.departure(trip, it) else route.arrival(trip, it) }
        return JourneyLeg.Ride(
            route = network.route(route.pattern.route) ?: error("Unknown route ${route.pattern.route}"),
            headsign = row.headsign ?: route.pattern.headsign,
            trip = row,
            stops = stops,
            times = times,
            depart = route.departure(trip, board),
            arrive = route.arrival(trip, alight),
            shift = route.shift,
        )
    }

    companion object {
        const val WALK_RADIUS_M = 800.0
        const val MAX_DIRECT_WALK_M = 2_000.0
        /** How much arrival time a change must save to be worth it. */
        const val CHANGE_PENALTY_SECONDS = 300
        private const val DAY = 86_400
        /** How far past the requested time a search looks. */
        private const val HORIZON_SECONDS = 4 * 3600
        /** Longest a single trip runs; earlier-starting trips can still be boarded. */
        private const val MAX_TRIP_SECONDS = 4 * 3600
        private const val LATEST_TRIP_START = 30 * 3600
    }
}
