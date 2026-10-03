package app.southbound.feature.explore

import app.southbound.core.map.MapLine
import app.southbound.core.map.MapPoint
import app.southbound.core.transit.Journey
import app.southbound.core.transit.JourneyLeg
import app.southbound.core.transit.RealtimeSnapshot
import app.southbound.core.transit.TransitPack
import app.southbound.core.transit.TransitSystem
import app.southbound.feature.transit.LiveDeparture
import app.southbound.feature.transit.StationBoard
import app.southbound.feature.transit.TransitLive
import app.southbound.feature.transit.TransitPlanState
import app.southbound.feature.transit.TransitTime
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZonedDateTime

/** Transit runs on New York time, whatever the phone's zone. */
internal fun transitNow(): ZonedDateTime = ZonedDateTime.now(RealtimeSnapshot.NEW_YORK)

internal fun secondsOfDay(now: ZonedDateTime = transitNow()): Int = now.toLocalTime().toSecondOfDay()

private fun LocalDate.key(): Int = year * 10_000 + monthValue * 100 + dayOfMonth

/** Plans a transit trip from the pack. Blocking; call off the main thread. */
internal fun planTransit(
    pack: TransitPack,
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
    time: TransitTime,
): TransitPlanState {
    val now = transitNow()
    val date = now.toLocalDate()
    if (pack.validTo != 0 && date.key() > pack.validTo) return TransitPlanState.Failed(TransitPlanState.Reason.OUT_OF_DATE)
    val seconds = if (time.mode == TransitTime.Mode.NOW) secondsOfDay(now) else time.seconds
    val journeys = pack.planner().plan(
        fromLat, fromLon, toLat, toLon, date, seconds,
        arriveBy = time.mode == TransitTime.Mode.ARRIVE_BY,
    )
    return if (journeys.isEmpty()) {
        TransitPlanState.Failed(TransitPlanState.Reason.NO_TRIPS)
    } else {
        TransitPlanState.Ready(journeys)
    }
}

/** Live times at each option's first boarding, and alerts on its lines. */
internal fun liveFor(snapshot: RealtimeSnapshot, journeys: List<Journey>): TransitLive {
    val date = transitNow().toLocalDate()
    val nowEpoch = System.currentTimeMillis() / 1000
    val boarding = journeys.mapNotNull { journey ->
        val ride = journey.rides.firstOrNull() ?: return@mapNotNull null
        val serviceDate = date.plusDays((ride.shift / 86_400).toLong())
        snapshot.predict(ride.route.system, ride.trip, ride.from, serviceDate, ride.depart - ride.shift, ride.shift)
            ?.let { ride.trip.id to it }
    }.toMap()
    val alerts = journeys.flatMap { it.rides }.map { it.route }.distinctBy { it.id }
        .associate { it.id to snapshot.alertsFor(it, nowEpoch) }
        .filterValues { it.isNotEmpty() }
    return TransitLive(boarding, alerts)
}

/** Departure boards for the stations near a point. Blocking; call off the main thread. */
internal fun boardsNear(pack: TransitPack, lat: Double, lon: Double, snapshot: RealtimeSnapshot?): List<StationBoard> {
    val now = transitNow()
    val date = now.toLocalDate()
    val seconds = secondsOfDay(now)
    return pack.stationsNear(lat, lon).map { station ->
        val departures = pack.departures(pack.network.platforms(station.id), date, seconds - 120)
        StationBoard(
            station,
            departures.map { d ->
                val prediction = snapshot?.predict(
                    d.route.system, d.trip, d.stop, date.plusDays((d.shift / 86_400).toLong()), d.time - d.shift, d.shift,
                )
                LiveDeparture(d, prediction)
            }.filter { it.time >= seconds - 30 },
        )
    }
}

internal fun TransitPack.hasBusNear(lat: Double, lon: Double): Boolean =
    stationsNear(lat, lon).any { it.system == TransitSystem.BUS }

internal fun Journey.usesBus(): Boolean = rides.any { it.route.system == TransitSystem.BUS }

/** Ride legs in colour, walks in grey, from [origin] to [destination]. */
internal fun Journey.mapLines(origin: MapPoint, destination: MapPoint): List<MapLine> = legs.map { leg ->
    when (leg) {
        is JourneyLeg.Ride -> MapLine(leg.stops.map { MapPoint(it.lat, it.lon) }, selected = true)
        is JourneyLeg.Walk -> MapLine(
            listOf(
                leg.from?.let { MapPoint(it.lat, it.lon) } ?: origin,
                leg.to?.let { MapPoint(it.lat, it.lon) } ?: destination,
            ),
            selected = false,
        )
    }
}

/** Runs [block], turning failures other than cancellation into null. */
internal inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
