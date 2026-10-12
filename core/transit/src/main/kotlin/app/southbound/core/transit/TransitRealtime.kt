package app.southbound.core.transit

import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

/** MTA's realtime feeds. Subway, rail and alerts need no key; Bus Time does. */
object MtaFeeds {
    private const val BASE = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds/"

    /** Every subway line group: fetching all of them reveals nothing about which line you ride. */
    val SUBWAY = listOf("", "-ace", "-bdfm", "-g", "-jz", "-nqrw", "-l", "-si").map { "${BASE}nyct%2Fgtfs$it" }
    const val LIRR = "${BASE}lirr%2Fgtfs-lirr"
    const val METRO_NORTH = "${BASE}mnr%2Fgtfs-mnr"
    const val ALERTS = "${BASE}camsys%2Fall-alerts"

    fun busTripUpdates(key: String) = "https://gtfsrt.prod.obanyc.com/tripUpdates?key=${URLEncoder.encode(key, "UTF-8")}"
    fun busAlerts(key: String) = "https://gtfsrt.prod.obanyc.com/alerts?key=${URLEncoder.encode(key, "UTF-8")}"
}

/** What realtime says about one scheduled departure or arrival. */
data class Prediction(
    /** Seconds after midnight of the service day, or null when only cancellation is known. */
    val time: Int?,
    val canceled: Boolean = false,
    val skipped: Boolean = false,
) {
    fun delayFrom(scheduled: Int): Int? = time?.let { it - scheduled }
}

/** One fetch of every realtime feed the user has allowed. */
class RealtimeSnapshot(
    feeds: Map<TransitSystem, List<RealtimeFeed>>,
    allSystemAlerts: List<ServiceAlert> = emptyList(),
    /** POSIX milliseconds. */
    val fetchedAt: Long,
    /** Feeds that couldn't be fetched, with why. */
    val errors: List<String> = emptyList(),
) {
    private val updates: Map<TransitSystem, Map<String, List<TripUpdate>>> = feeds.mapValues { (system, list) ->
        list.flatMap { it.tripUpdates }.filter { it.trip.tripId != null }.groupBy { key(system, it.trip.tripId!!, fromRealtime = true) }
    }

    /** Every alert, with the system of the feed it came from (null: MTA's all-systems feed). */
    val alerts: List<Pair<TransitSystem?, ServiceAlert>> =
        feeds.flatMap { (system, list) -> list.flatMap { feed -> feed.alerts.map { system to it } } } +
            allSystemAlerts.map { null to it }

    val hasData: Boolean get() = updates.values.any { it.isNotEmpty() } || alerts.isNotEmpty()

    /**
     * The prediction for [trip] at [stop], on the service day [serviceDate].
     * [scheduled] is the timetable time there, in GTFS seconds of that day
     * ([GtfsTime]); [shift] converts the answer to another day's clock (as in
     * [Timetable]). With [network], a stop the update says nothing about takes
     * the delay of the trip's nearest earlier stop it does cover.
     */
    fun predict(
        system: TransitSystem?,
        trip: TripRow,
        stop: TransitStop,
        serviceDate: LocalDate,
        scheduled: Int,
        shift: Int = 0,
        network: TransitNetwork? = null,
    ): Prediction? {
        system ?: return null
        val candidates = updates[system]?.get(key(system, trip.gtfsId, fromRealtime = false)) ?: return null
        val wanted = serviceDate.toKey().toString()
        val update = candidates.firstOrNull { it.trip.startDate == null || it.trip.startDate == wanted } ?: return null
        if (update.trip.canceled) return Prediction(null, canceled = true)
        val atStop = update.stopTimeUpdates.firstOrNull { it.stopId == stop.gtfsId }
        if (atStop?.skipped == true) return Prediction(null, skipped = true)
        val event = atStop?.departure ?: atStop?.arrival
        event?.time?.let { return Prediction(GtfsTime.secondsOf(it, serviceDate) + shift) }
        val carried = if (atStop == null && network != null) carriedDelay(update, trip, stop, serviceDate, network) else null
        val delay = event?.delay ?: carried ?: update.delay ?: return null
        return Prediction(scheduled + delay + shift)
    }

    /**
     * GTFS-realtime's delay propagation: a stop with no update of its own runs as late as the nearest earlier
     * stop of the trip that has one. That stop's departure delay counts, else its arrival delay, else its
     * predicted time against the timetable. A skipped stop is passed over; an earlier update with no times (no
     * data) ends the search. Null when [network] doesn't know the trip's stops or no earlier stop has an update.
     */
    private fun carriedDelay(update: TripUpdate, trip: TripRow, stop: TransitStop, serviceDate: LocalDate, network: TransitNetwork): Int? {
        val pattern = network.pattern(trip.pattern) ?: return null
        val here = pattern.stops.indexOf(stop.id)
        if (here <= 0) return null
        val ids = pattern.stops.map { network.stop(it)?.gtfsId }
        // Each update for a stop before this one, with that stop's place in the trip, nearest first.
        val earlier = update.stopTimeUpdates.mapNotNull { u ->
            val at = u.stopId?.let { ids.indexOf(it) } ?: -1
            if (at in 0 until here) at to u else null
        }.sortedByDescending { it.first }
        for ((at, u) in earlier) {
            if (u.skipped) continue
            u.departure?.delay?.let { return it }
            u.arrival?.delay?.let { return it }
            val time = u.departure?.time ?: u.arrival?.time ?: return null
            val arrivals = IntArray(pattern.stops.size)
            val departures = IntArray(pattern.stops.size)
            if (TransitCodec.times(trip.start, trip.times, arrivals, departures) <= at) return null
            val planned = if (u.departure?.time != null) departures[at] else arrivals[at]
            return GtfsTime.secondsOf(time, serviceDate) - planned
        }
        return null
    }

    /** Alerts naming [route], active at [nowSeconds], each once ([sameAlertKey]). */
    fun alertsFor(route: TransitRoute, nowSeconds: Long): List<ServiceAlert> =
        alerts.filter { (feedSystem, alert) ->
            alert.isActive(nowSeconds) && alert.routes.any { named ->
                named.routeId == route.gtfsId && (agencySystem(named.agencyId) ?: feedSystem) == route.system
            }
        }.map { it.second }.distinctBy { it.sameAlertKey() }

    /**
     * What tells one alert from another. Alerts with the same header read the same in the list (one alert in two
     * feeds, say), so the header; one without a header goes by its description, and failing both by its id, so
     * header-less alerts don't all count as one.
     */
    private fun ServiceAlert.sameAlertKey(): Any = header ?: description?.let { "description" to it } ?: ("id" to id)

    private fun key(system: TransitSystem, tripId: String, fromRealtime: Boolean): String =
        // Subway realtime ids are the tail of the schedule's: "050950_R..N93R"
        // for "L0S6-R-1097-S01_050950_R..N93R".
        if (system == TransitSystem.SUBWAY && !fromRealtime) tripId.substringAfter('_') else tripId

    companion object {
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

        /** MTA agency ids as used in its GTFS and alerts. */
        fun agencySystem(agencyId: String?): TransitSystem? = when (agencyId) {
            "MTASBWY" -> TransitSystem.SUBWAY
            "MTA NYCT", "MTABC" -> TransitSystem.BUS
            "LI" -> TransitSystem.LIRR
            "MNR" -> TransitSystem.METRO_NORTH
            else -> null
        }

        val EMPTY = RealtimeSnapshot(emptyMap(), fetchedAt = 0)
    }
}

/**
 * Fetches MTA realtime through [NetworkGate], only for the features the user
 * turned on, and caches the result briefly. Requests carry no location,
 * search or route, and always cover a whole system.
 */
class TransitRealtime(
    private val gate: NetworkGate,
    private val busKey: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (NetworkFeature, String) -> ByteArray = gate::get,
) {
    private val mutex = Mutex()
    private var cached: RealtimeSnapshot? = null
    private var cachedWithBus = false

    val enabled: Boolean get() = gate.isAllowed(NetworkFeature.TRANSIT_REALTIME)
    val busEnabled: Boolean get() = gate.isAllowed(NetworkFeature.TRANSIT_BUS_TIME) && !busKey().isNullOrBlank()

    /**
     * The latest snapshot, fetched if the cached one is older than
     * [maxAgeMillis]. Bus Time is fetched only when [includeBus] is set,
     * since its feed is large. Null when realtime is off.
     */
    suspend fun snapshot(includeBus: Boolean = false, maxAgeMillis: Long = 30_000): RealtimeSnapshot? = mutex.withLock {
        val wantBus = includeBus && busEnabled
        if (!enabled && !wantBus) return null
        cached?.let { if (clock() - it.fetchedAt < maxAgeMillis && (cachedWithBus || !wantBus)) return it }

        val requests = buildList {
            if (enabled) {
                MtaFeeds.SUBWAY.forEach { add(Triple(TransitSystem.SUBWAY, NetworkFeature.TRANSIT_REALTIME, it)) }
                add(Triple(TransitSystem.LIRR, NetworkFeature.TRANSIT_REALTIME, MtaFeeds.LIRR))
                add(Triple(TransitSystem.METRO_NORTH, NetworkFeature.TRANSIT_REALTIME, MtaFeeds.METRO_NORTH))
            }
            if (wantBus) {
                val key = busKey().orEmpty()
                add(Triple(TransitSystem.BUS, NetworkFeature.TRANSIT_BUS_TIME, MtaFeeds.busTripUpdates(key)))
                add(Triple(TransitSystem.BUS, NetworkFeature.TRANSIT_BUS_TIME, MtaFeeds.busAlerts(key)))
            }
        }
        val errors = ArrayList<String>()
        val results = coroutineScope {
            requests.map { (system, feature, url) ->
                async(Dispatchers.IO) {
                    runCatching { system to GtfsRealtime.parse(fetch(feature, url)) }
                        .onFailure { synchronized(errors) { errors.add("${url.substringBefore('?')}: ${it.message}") } }
                        .getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        val feeds = results.groupBy({ it.first }, { it.second })
        // MTA's alerts feed covers every system; agency ids tell routes apart.
        val allAlerts = if (enabled) {
            runCatching { GtfsRealtime.parse(fetch(NetworkFeature.TRANSIT_REALTIME, MtaFeeds.ALERTS)).alerts }
                .onFailure { errors.add("alerts: ${it.message}") }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        RealtimeSnapshot(feeds, allAlerts, clock(), errors).also {
            cached = it
            cachedWithBus = wantBus
        }
    }
}
