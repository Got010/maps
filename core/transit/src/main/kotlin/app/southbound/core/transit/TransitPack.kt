package app.southbound.core.transit

import java.io.Closeable
import java.time.LocalDate

/** The few SQLite calls the transit pack needs, so the loader runs on any driver. */
interface TransitDb : Closeable {
    fun query(sql: String, args: List<Any> = emptyList(), each: (TransitRow) -> Unit)
}

interface TransitRow {
    fun int(index: Int): Int
    fun double(index: Int): Double
    fun text(index: Int): String?
    fun blob(index: Int): ByteArray
    fun isNull(index: Int): Boolean
}

/** A scheduled departure from a stop, before any realtime update. */
data class Departure(
    val stop: TransitStop,
    val route: TransitRoute,
    val headsign: String?,
    val direction: Int?,
    val trip: TripRow,
    /** Seconds after midnight of the board's day. */
    val time: Int,
    /** Seconds between the trip's service day and the board's day. */
    val shift: Int = 0,
)

/**
 * The transit pack (transit-<region>.sqlite, built by
 * pipeline/transit/build_transit.py): stops, lines and every scheduled trip.
 * The network loads once; trips are read per search or board.
 */
class TransitPack(private val db: TransitDb) : TripSource, Closeable {

    val network: TransitNetwork by lazy { loadNetwork() }

    /** Valid service dates, yyyymmdd, from the pack's metadata. */
    val validTo: Int by lazy { meta("valid_to")?.toIntOrNull() ?: 0 }

    fun planner(): TransitPlanner = TransitPlanner(network, this)

    override fun trips(services: Set<Int>, startFrom: Int, startTo: Int): List<TripRow> {
        if (services.isEmpty()) return emptyList()
        val rows = ArrayList<TripRow>()
        db.query(
            "SELECT $TRIP_COLUMNS FROM trip WHERE start BETWEEN ? AND ? AND service IN (${services.joinToString(",")})",
            listOf(startFrom, startTo),
        ) { rows.add(it.toTrip()) }
        return rows
    }

    /**
     * The next departures from [stops] (platforms of one station, or one bus
     * stop) after [time] on [date], soonest first.
     */
    fun departures(stops: Collection<Int>, date: LocalDate, time: Int, windowSeconds: Int = 2 * 3600, limit: Int = 30): List<Departure> {
        val net = network
        val patternIds = stops.flatMap { net.patternsAt(it).toList() }.distinct()
        if (patternIds.isEmpty()) return emptyList()
        val wanted = stops.toSet()
        val result = ArrayList<Departure>()
        for (dayOffset in -1..1) {
            val shift = dayOffset * DAY
            val from = time - shift - MAX_TRIP_SECONDS
            val to = time - shift + windowSeconds
            if (to < 0 || from > LATEST_TRIP_START) continue
            val services = net.activeServices(date.plusDays(dayOffset.toLong()))
            if (services.isEmpty()) continue
            db.query(
                "SELECT $TRIP_COLUMNS FROM trip WHERE pattern IN (${patternIds.joinToString(",")}) " +
                    "AND start BETWEEN ? AND ? AND service IN (${services.joinToString(",")})",
                listOf(from, to),
            ) { row ->
                val trip = row.toTrip()
                val pattern = net.pattern(trip.pattern) ?: return@query
                val n = pattern.stops.size
                val arrivals = IntArray(n)
                val departures = IntArray(n)
                TransitCodec.times(trip.start, trip.times, arrivals, departures, shift = shift)
                for (i in 0 until n) {
                    val stop = pattern.stops[i]
                    if (stop !in wanted || !pattern.pickup[i]) continue
                    val at = departures[i]
                    if (at < time || at > time + windowSeconds) continue
                    result.add(
                        Departure(
                            stop = net.stop(stop) ?: continue,
                            route = net.route(pattern.route) ?: continue,
                            headsign = trip.headsign ?: pattern.headsign,
                            direction = pattern.direction,
                            trip = trip,
                            time = at,
                            shift = shift,
                        ),
                    )
                }
            }
        }
        return result.sortedBy { it.time }.take(limit)
    }

    /**
     * Stations near a point, nearest first: subway stations and rail stops
     * within [radiusMeters], then bus stops within [busRadiusMeters].
     */
    fun stationsNear(lat: Double, lon: Double, radiusMeters: Double = 400.0, busRadiusMeters: Double = 150.0, limit: Int = 4): List<TransitStop> {
        val net = network
        return net.allStops.asSequence()
            .filter { it.parent == null }
            .filter { it.isStation || net.patternsAt(it.id).isNotEmpty() }
            .map { it to Walking.distanceMeters(lat, lon, it.lat, it.lon) }
            .filter { (stop, d) -> d <= if (stop.system == TransitSystem.BUS) busRadiusMeters else radiusMeters }
            .sortedWith(compareBy({ it.first.system == TransitSystem.BUS }, { it.second }))
            .map { it.first }
            .take(limit)
            .toList()
    }

    override fun close() = db.close()

    private fun meta(key: String): String? {
        var value: String? = null
        db.query("SELECT value FROM meta WHERE key = ?", listOf(key)) { value = it.text(0) }
        return value
    }

    private fun loadNetwork(): TransitNetwork {
        val systems = HashMap<Int, TransitSystem?>()
        db.query("SELECT id, name FROM feed") { systems[it.int(0)] = TransitSystem.of(it.text(1).orEmpty()) }

        val stops = ArrayList<TransitStop>()
        db.query("SELECT id, feed, gtfs_id, name, lat, lon, parent, is_station, change_seconds FROM stop") { r ->
            stops.add(
                TransitStop(
                    id = r.int(0), system = systems[r.int(1)], gtfsId = r.text(2).orEmpty(), name = r.text(3).orEmpty(),
                    lat = r.double(4), lon = r.double(5), parent = if (r.isNull(6)) null else r.int(6),
                    isStation = r.int(7) == 1, changeSeconds = r.int(8),
                ),
            )
        }

        val routes = ArrayList<TransitRoute>()
        db.query("SELECT id, feed, gtfs_id, short_name, long_name, type, color, text_color, sort_order FROM route") { r ->
            routes.add(
                TransitRoute(
                    id = r.int(0), system = systems[r.int(1)], gtfsId = r.text(2).orEmpty(), shortName = r.text(3),
                    longName = r.text(4), type = r.int(5), color = r.text(6), textColor = r.text(7),
                    sortOrder = if (r.isNull(8)) null else r.int(8),
                ),
            )
        }

        val patterns = ArrayList<TransitPattern>()
        db.query("SELECT id, route, headsign, direction, stops, flags FROM pattern") { r ->
            val flags = r.blob(5)
            patterns.add(
                TransitPattern(
                    id = r.int(0), route = r.int(1), headsign = r.text(2),
                    direction = if (r.isNull(3)) null else r.int(3),
                    stops = TransitCodec.uints(r.blob(4)),
                    pickup = BooleanArray(flags.size) { flags[it].toInt() and PICKUP_OK != 0 },
                    dropOff = BooleanArray(flags.size) { flags[it].toInt() and DROP_OFF_OK != 0 },
                ),
            )
        }

        val exceptions = HashMap<Int, HashMap<Int, Boolean>>()
        db.query("SELECT service, date, added FROM service_date") { r ->
            exceptions.getOrPut(r.int(0)) { HashMap() }[r.int(1)] = r.int(2) == 1
        }
        val services = HashMap<Int, Service>()
        db.query("SELECT id, days, start_date, end_date FROM service") { r ->
            val id = r.int(0)
            services[id] = Service(id, r.int(1), r.int(2), r.int(3), exceptions[id].orEmpty())
        }

        val transfers = ArrayList<Transfer>()
        db.query("SELECT from_stop, to_stop, seconds FROM transfer") { r -> transfers.add(Transfer(r.int(0), r.int(1), r.int(2))) }

        return TransitNetwork(stops, routes, patterns, services, transfers)
    }

    private fun TransitRow.toTrip() = TripRow(
        id = int(0), pattern = int(1), service = int(2), gtfsId = text(3).orEmpty(), headsign = text(4),
        shortName = text(5), start = int(6), times = blob(7),
    )

    companion object {
        /** "SBT1"; the pipeline sets it, and core:packs recognises it. */
        const val APPLICATION_ID = 0x53425431L
        const val SCHEMA_VERSION = 1

        private const val TRIP_COLUMNS = "id, pattern, service, gtfs_id, headsign, short_name, start, times"
        private const val PICKUP_OK = 1
        private const val DROP_OFF_OK = 2
        private const val DAY = 86_400
        private const val MAX_TRIP_SECONDS = 4 * 3600
        private const val LATEST_TRIP_START = 30 * 3600
    }
}
