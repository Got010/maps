package app.southbound.core.voice

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Distances as they are said, not printed: "12 miles", "2.4 miles", "1 mile",
 * "500 feet". (Text-to-speech reads the screen's "12 mi" badly.) Pure and
 * unit tested; the rounding matches the trip screen's.
 */
object SpokenUnits {
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    fun distance(meters: Double): String {
        val miles = meters.coerceAtLeast(0.0) / METERS_PER_MILE
        return when {
            miles < 0.1 -> {
                val feet = ((meters.coerceAtLeast(0.0) * FEET_PER_METER / 50).roundToLong() * 50).coerceAtLeast(50)
                "$feet feet"
            }
            miles < 10 -> {
                val tenths = String.format(Locale.US, "%.1f", miles).removeSuffix(".0")
                if (tenths == "1") "1 mile" else "$tenths miles"
            }
            else -> {
                val whole = miles.roundToLong()
                "$whole miles"
            }
        }
    }
}
