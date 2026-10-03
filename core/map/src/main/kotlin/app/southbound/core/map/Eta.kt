package app.southbound.core.map

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Arrival times and how traffic changes them, for route options and the navigation screen. */
object Eta {
    /** Delay beyond this many seconds turns the ETA red and shows "+X min". */
    const val LATE_SECONDS = 120.0

    /** "3:42 PM": the clock time [secondsAhead] seconds after [nowMillis]. */
    fun clock(nowMillis: Long, secondsAhead: Double, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val format = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        format.timeZone = zone
        return format.format(Date(nowMillis + (secondsAhead * 1000).toLong()))
    }

    /** True when traffic adds enough time to flag it. */
    fun isLate(delaySeconds: Double): Boolean = delaySeconds > LATE_SECONDS

    /** Whole minutes of delay to print, never less than 1 for a delay worth showing. */
    fun delayMinutes(delaySeconds: Double): Int = (delaySeconds / 60).roundToInt().coerceAtLeast(1)
}

/** Which side of the road the destination is on, as the driver arrives. */
enum class ArrivalSide { LEFT, RIGHT, AHEAD }

/**
 * Works out the side of the street from the end of the route: Valhalla's
 * side_of_street isn't carried through Ferrostar's route model, so it is
 * computed from the last stretch of the route's geometry and the destination.
 */
object Arrival {
    /** Closer than this to the road's line, the destination counts as straight ahead (or on it). */
    const val AHEAD_METERS = 4.0

    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * [route] is the route's final stretch (the last step's points), driving
     * toward its last point; [destination] is where the user is going.
     */
    fun side(route: List<MapPoint>, destination: MapPoint): ArrivalSide? {
        val end = route.lastOrNull() ?: return null
        val k = Math.cos(Math.toRadians(end.lat))
        fun east(p: MapPoint) = (p.lon - end.lon) * k * METERS_PER_DEGREE
        fun north(p: MapPoint) = (p.lat - end.lat) * METERS_PER_DEGREE
        // Heading from the last point that is a few metres back, so a tiny final segment doesn't skew it.
        val before = route.dropLast(1).lastOrNull { Math.hypot(east(it), north(it)) >= 5.0 } ?: return null
        val hx = -east(before)
        val hy = -north(before)
        val vx = east(destination)
        val vy = north(destination)
        val length = Math.hypot(hx, hy)
        // Cross product of heading and the way to the destination: positive is to the left.
        val cross = (hx * vy - hy * vx) / length
        return when {
            Math.abs(cross) < AHEAD_METERS -> ArrivalSide.AHEAD
            cross > 0 -> ArrivalSide.LEFT
            else -> ArrivalSide.RIGHT
        }
    }
}
