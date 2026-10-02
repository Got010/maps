package app.southbound.core.map

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** US units for distances and times shown in lists and cards. */
object Units {
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    /** "450 ft", "0.3 mi", "2.4 mi", "18 mi". */
    fun distance(meters: Double): String {
        val miles = meters / METERS_PER_MILE
        return when {
            miles < 0.1 -> {
                val feet = (meters * FEET_PER_METER / 50).roundToLong() * 50
                "${feet.coerceAtLeast(50)} ft"
            }
            miles < 10 -> String.format(Locale.US, "%.1f mi", miles)
            else -> "${miles.roundToLong()} mi"
        }
    }

    /** "1 min", "25 min", "1 hr 5 min", "3 hr". */
    fun duration(seconds: Double): String {
        val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$minutes min"
            rest == 0 -> "$hours hr"
            else -> "$hours hr $rest min"
        }
    }
}
