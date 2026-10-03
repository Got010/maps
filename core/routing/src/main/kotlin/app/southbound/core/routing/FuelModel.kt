package app.southbound.core.routing

import uniffi.ferrostar.Route
import java.util.Locale

/**
 * A rough estimate of the gas a route burns, from the car's combined mpg.
 * Each step's average speed bends the mpg: stop-and-go costs more, a steady
 * 45-60 mph a little less, and fast highway driving more again. Standing in
 * traffic delay burns idle fuel on top. It ranks routes against each other
 * well; the dollar figure is approximate.
 */
object FuelModel {
    private const val METERS_PER_MILE = 1609.344

    /** Gallons an idling engine burns per hour. */
    const val IDLE_GALLONS_PER_HOUR = 0.4

    /** One stretch of a route. */
    data class Leg(val distanceMeters: Double, val durationSeconds: Double)

    /** Gallons for [route] at [mpg], plus [delaySeconds] of live-traffic delay spent idling. */
    fun gallons(route: Route, mpg: Double, delaySeconds: Double = 0.0): Double =
        gallons(route.steps.map { Leg(it.distance, it.duration) }, mpg, delaySeconds)

    fun gallons(legs: List<Leg>, mpg: Double, delaySeconds: Double = 0.0): Double {
        val effectiveMiles = legs.sumOf { it.distanceMeters / METERS_PER_MILE * factor(it) }
        val idle = delaySeconds.coerceAtLeast(0.0) / 3600.0 * IDLE_GALLONS_PER_HOUR
        return effectiveMiles / mpg + idle
    }

    fun cost(gallons: Double, pricePerGallon: Double): Double = gallons * pricePerGallon

    /** How much a leg's miles weigh against the rated mpg, from its average speed. */
    internal fun factor(leg: Leg): Double {
        if (leg.distanceMeters <= 0.0 || leg.durationSeconds <= 0.0) return 1.0
        val mph = leg.distanceMeters / METERS_PER_MILE / (leg.durationSeconds / 3600.0)
        return when {
            mph < 25.0 -> 1.25
            mph < 45.0 -> 1.0
            mph < 60.0 -> 0.95
            mph <= 65.0 -> 1.0
            else -> 1.10
        }
    }

    /** Index of the route that burns the least gas (the earlier one on a tie), or null for none. */
    fun mostEfficient(gallons: List<Double>): Int? {
        var best: Int? = null
        var bestGallons = 0.0
        gallons.forEachIndexed { i, g ->
            if (g.isFinite() && (best == null || g < bestGallons)) {
                best = i
                bestGallons = g
            }
        }
        return best
    }

    /** "$4.10" under ten dollars, whole dollars from there ("$14"). */
    fun formatCost(dollars: Double): String =
        if (dollars < 9.995) String.format(Locale.US, "$%.2f", dollars) else String.format(Locale.US, "$%.0f", dollars)
}
