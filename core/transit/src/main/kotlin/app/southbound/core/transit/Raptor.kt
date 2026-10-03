package app.southbound.core.transit

/**
 * RAPTOR (Delling, Pajor and Werneck, 2012): round k finds the earliest
 * arrival at every stop using at most k vehicles. Each round that improves
 * the arrival at the destination adds a journey, so the result is the set of
 * trade-offs between arriving earlier and changing less.
 */
internal class Raptor(private val tt: Timetable, private val maxRides: Int = MAX_RIDES) {

    /** A journey in the timetable's own orientation, before it becomes [Journey]. */
    data class RawJourney(val legs: List<RawLeg>, val arrival: Int, val rides: Int)

    sealed interface RawLeg {
        data class Access(val stop: Int, val seconds: Int) : RawLeg
        data class Ride(val route: Int, val trip: Int, val board: Int, val alight: Int) : RawLeg
        data class Walk(val from: Int, val to: Int, val seconds: Int) : RawLeg
        data class Egress(val stop: Int, val seconds: Int) : RawLeg
    }

    private val stops = tt.network.stopCount
    private val rounds = maxRides + 1

    // Per round: best arrival, how it was reached, and the ride and walk
    // labels kept apart so a walk can always point back at the ride before it.
    private val arrival = Array(rounds) { IntArray(stops) { INF } }
    private val kind = Array(rounds) { ByteArray(stops) }
    private val byRide = Array(rounds) { BooleanArray(stops) }
    private val rideArrival = Array(rounds) { IntArray(stops) { INF } }
    private val rideRoute = Array(rounds) { IntArray(stops) }
    private val rideTrip = Array(rounds) { IntArray(stops) }
    private val rideBoard = Array(rounds) { IntArray(stops) }
    private val rideAlight = Array(rounds) { IntArray(stops) }
    private val walkFrom = Array(rounds) { IntArray(stops) }
    private val walkSeconds = Array(rounds) { IntArray(stops) }
    private val accessSeconds = IntArray(stops)
    private val best = IntArray(stops) { INF }

    fun search(access: List<StopAccess>, egress: List<StopAccess>, departAt: Int): List<RawJourney> {
        var marked = BooleanArray(stops)
        for (a in access) {
            val time = departAt + a.seconds
            if (time < arrival[0][a.stop]) {
                arrival[0][a.stop] = time
                best[a.stop] = time
                kind[0][a.stop] = ACCESS
                accessSeconds[a.stop] = a.seconds
                marked[a.stop] = true
            }
        }

        val egressSeconds = HashMap<Int, Int>()
        egress.forEach { e -> egressSeconds.merge(e.stop, e.seconds, ::minOf) }
        var target = INF
        val results = ArrayList<RawJourney>()

        val routeStart = IntArray(tt.routes.size) { Int.MAX_VALUE }
        for (k in 1 until rounds) {
            val prev = arrival[k - 1]
            val cur = arrival[k]
            System.arraycopy(prev, 0, cur, 0, stops)
            System.arraycopy(byRide[k - 1], 0, byRide[k], 0, stops)
            java.util.Arrays.fill(kind[k], CARRY)

            // Routes through a stop improved last round, from their first such stop.
            val queue = ArrayList<Int>()
            for (s in 0 until stops) {
                if (!marked[s]) continue
                val rs = tt.stopRoutes[s]
                val ps = tt.stopPositions[s]
                for (j in rs.indices) {
                    val r = rs[j]
                    if (routeStart[r] == Int.MAX_VALUE) queue.add(r)
                    if (ps[j] < routeStart[r]) routeStart[r] = ps[j]
                }
            }

            val improved = BooleanArray(stops)
            for (r in queue) {
                val route = tt.routes[r]
                var trip = -1
                var board = -1
                for (i in routeStart[r] until route.stopCount) {
                    val s = route.stops[i]
                    if (trip >= 0 && route.dropOff[i]) {
                        val time = route.arrival(trip, i)
                        if (time < best[s] && time < target) {
                            cur[s] = time
                            best[s] = time
                            kind[k][s] = RIDE
                            byRide[k][s] = true
                            rideArrival[k][s] = time
                            rideRoute[k][s] = r
                            rideTrip[k][s] = trip
                            rideBoard[k][s] = board
                            rideAlight[k][s] = i
                            improved[s] = true
                        }
                    }
                    if (route.pickup[i] && prev[s] != INF) {
                        val ready = prev[s] + if (byRide[k - 1][s]) tt.network.stop(s)?.changeSeconds ?: 0 else 0
                        if (trip < 0 || ready <= route.departure(trip, i)) {
                            val t = route.earliestTrip(i, ready, if (trip < 0) route.tripCount else trip + 1)
                            if (t >= 0 && (trip < 0 || t < trip)) {
                                trip = t
                                board = i
                            }
                        }
                    }
                }
                routeStart[r] = Int.MAX_VALUE
            }

            // Walk on from every stop a vehicle reached this round.
            val next = BooleanArray(stops)
            for (s in 0 until stops) {
                if (!improved[s]) continue
                next[s] = true
                val targets = tt.transferTargets[s]
                val seconds = tt.transferSeconds[s]
                for (j in targets.indices) {
                    val to = targets[j]
                    val time = rideArrival[k][s] + seconds[j]
                    if (time < best[to] && time < target) {
                        cur[to] = time
                        best[to] = time
                        kind[k][to] = WALK
                        byRide[k][to] = false
                        walkFrom[k][to] = s
                        walkSeconds[k][to] = seconds[j]
                        next[to] = true
                    }
                }
            }

            var bestStop = -1
            for ((stop, seconds) in egressSeconds) {
                if (cur[stop] == INF) continue
                val time = cur[stop] + seconds
                if (time < target) {
                    target = time
                    bestStop = stop
                }
            }
            if (bestStop >= 0) {
                val legs = reconstruct(k, bestStop)
                results.add(RawJourney(legs + RawLeg.Egress(bestStop, egressSeconds.getValue(bestStop)), target, legs.count { it is RawLeg.Ride }))
            }

            marked = next
            if (next.none { it }) break
        }
        // Keep the trade-offs: each extra ride must buy an earlier arrival.
        var latest = INF
        return results.groupBy { it.rides }.values.map { group -> group.minBy { it.arrival } }
            .sortedBy { it.rides }
            .filter { j -> (j.arrival < latest).also { if (it) latest = j.arrival } }
    }

    private fun reconstruct(round: Int, stop: Int): List<RawLeg> {
        val legs = ArrayList<RawLeg>()
        var k = round
        var s = stop
        var viaRide = false // following a walk back to the ride that preceded it
        while (true) {
            if (viaRide) {
                legs.add(RawLeg.Ride(rideRoute[k][s], rideTrip[k][s], rideBoard[k][s], rideAlight[k][s]))
                s = tt.routes[rideRoute[k][s]].stops[rideBoard[k][s]]
                k--
                viaRide = false
                continue
            }
            when (kind[k][s]) {
                CARRY -> k--
                ACCESS -> {
                    legs.add(RawLeg.Access(s, accessSeconds[s]))
                    break
                }
                RIDE -> viaRide = true
                WALK -> {
                    legs.add(RawLeg.Walk(walkFrom[k][s], s, walkSeconds[k][s]))
                    s = walkFrom[k][s]
                    viaRide = true
                }
            }
            if (k < 0) error("RAPTOR label chain broken at stop $s")
        }
        return legs.asReversed()
    }

    companion object {
        const val INF = Int.MAX_VALUE
        const val MAX_RIDES = 5
        private const val CARRY: Byte = 0
        private const val ACCESS: Byte = 1
        private const val RIDE: Byte = 2
        private const val WALK: Byte = 3
    }
}
