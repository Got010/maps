package app.southbound.feature.car

import androidx.car.app.model.Distance
import app.southbound.core.routing.TripSnapshot
import kotlin.math.roundToLong

/** A distance as the car host wants it: a number and one of [Distance]'s UNIT_ constants. */
internal data class DisplayDistance(val value: Double, val unit: Int) {
    fun toDistance(): Distance = Distance.create(value, unit)
}

/** US units, rounded like the phone's banner: 50 ft steps under 0.1 mi, tenths to 10 mi, then whole miles. */
internal object CarDistance {
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    fun of(meters: Double): DisplayDistance {
        val m = meters.coerceAtLeast(0.0)
        val miles = m / METERS_PER_MILE
        return when {
            miles < 0.1 -> DisplayDistance(((m * FEET_PER_METER / 50).roundToLong() * 50).coerceAtLeast(50).toDouble(), Distance.UNIT_FEET)
            miles < 10 -> DisplayDistance((miles * 10).roundToLong() / 10.0, Distance.UNIT_MILES_P1)
            else -> DisplayDistance(miles.roundToLong().toDouble(), Distance.UNIT_MILES)
        }
    }
}

/** One manoeuvre's words and picture. */
internal data class CarStep(val cue: String, val maneuver: CarManeuver)

/**
 * Everything the car shows during a trip, already rounded to what is displayed (distances as the
 * car prints them, times to the minute) so an unchanged display compares equal and the car isn't
 * sent a new template every second.
 */
internal data class CarTripDisplay(
    val step: CarStep,
    val distanceToStep: DisplayDistance,
    /** When the car reaches the step's manoeuvre, to the minute (an estimate from the average speed left). */
    val stepArrivalMillis: Long,
    val next: CarStep?,
    val road: String?,
    val remaining: DisplayDistance,
    val remainingSeconds: Long,
    val arrivalMillis: Long,
    val rerouting: Boolean,
)

/** What the car's screen is showing: nothing to do, a trip, or the arrival. */
internal sealed interface CarModel {
    object Idle : CarModel

    object Arrived : CarModel

    data class Trip(val display: CarTripDisplay) : CarModel

    companion object {
        fun of(snapshot: TripSnapshot?, nowMillis: Long): CarModel = when {
            snapshot == null -> Idle
            snapshot.arrived -> Arrived
            else -> Trip(display(snapshot, nowMillis))
        }

        private fun display(s: TripSnapshot, nowMillis: Long): CarTripDisplay {
            val remainingSeconds = s.remainingSeconds.coerceAtLeast(0.0)
            // Whole minutes, at least one: "0 min left" while still driving reads as a fault.
            val shownSeconds = (remainingSeconds / 60).roundToLong().coerceAtLeast(1) * 60
            // The share of the trip's time left before the manoeuvre, from the share of its distance.
            val share = if (s.remainingMeters > 0) (s.distanceToManeuverMeters / s.remainingMeters).coerceIn(0.0, 1.0) else 0.0
            return CarTripDisplay(
                step = CarStep(s.instruction.ifBlank { s.roadName.orEmpty() }, CarManeuvers.of(s.maneuverType, s.maneuverModifier)),
                distanceToStep = CarDistance.of(s.distanceToManeuverMeters),
                stepArrivalMillis = minute(nowMillis + (remainingSeconds * share * 1000).toLong()),
                next = s.nextInstruction?.let { CarStep(it, CarManeuvers.of(s.nextManeuverType, s.nextManeuverModifier)) },
                road = s.roadName,
                remaining = CarDistance.of(s.remainingMeters),
                remainingSeconds = shownSeconds,
                arrivalMillis = minute(nowMillis + (remainingSeconds * 1000).toLong()),
                rerouting = s.rerouting,
            )
        }

        private fun minute(millis: Long): Long = millis / 60_000 * 60_000
    }
}
