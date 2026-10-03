package app.southbound.core.map

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise, sunset and "is it dark" from a position and the date, computed on
 * the phone with NOAA's solar calculator equations (the same as their
 * spreadsheet). Nothing is looked up, so night mode works offline.
 */
object SunTimes {
    /** Sun's centre this far below the horizon is sunrise or sunset (refraction plus the sun's radius). */
    private const val HORIZON_DEG = 90.833

    /** Sunrise and sunset as UTC epoch milliseconds; null when the sun doesn't rise or set that day (polar). */
    data class Day(val sunriseUtcMs: Long?, val sunsetUtcMs: Long?)

    /**
     * Sunrise and sunset for the calendar day [year]-[month]-[day] at ([lat], [lon]),
     * where the day is the UTC date the solar day starts on at that longitude.
     * West of Greenwich sunset can fall on the next UTC date; the returned
     * instants are still the right ones for that local day.
     */
    fun of(year: Int, month: Int, day: Int, lat: Double, lon: Double): Day {
        val jd0 = julianDay(year, month, day)
        val dayStartMs = ((jd0 - 2440587.5) * 86_400_000.0).toLong()
        return Day(event(jd0, dayStartMs, lat, lon, rising = true), event(jd0, dayStartMs, lat, lon, rising = false))
    }

    /** Height of the sun's centre above the horizon, in degrees, at [epochMs] (UTC) for ([lat], [lon]). */
    fun elevationDegrees(epochMs: Long, lat: Double, lon: Double): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val decl = declination(t)
        val minutesUtc = ((epochMs % 86_400_000L) + 86_400_000L) % 86_400_000L / 60_000.0
        val trueSolarTime = ((minutesUtc + equationOfTime(t) + 4 * lon) % 1440 + 1440) % 1440
        val hourAngle = rad(trueSolarTime / 4 - 180)
        val cosZenith = sin(rad(lat)) * sin(decl) + cos(rad(lat)) * cos(decl) * cos(hourAngle)
        return 90 - deg(acos(cosZenith.coerceIn(-1.0, 1.0)))
    }

    /**
     * True when the sun is below the horizon at [epochMs]. Uses the sun's
     * height at that instant rather than a day's sunrise and sunset, so it is
     * right across midnight and time zones, and in polar summer and winter.
     */
    fun isNight(epochMs: Long, lat: Double, lon: Double): Boolean = elevationDegrees(epochMs, lat, lon) < -(HORIZON_DEG - 90)

    private fun event(jd0: Double, dayStartMs: Long, lat: Double, lon: Double, rising: Boolean): Long? {
        // First pass at noon UTC, then once more at the time found, as NOAA does.
        var minutes = 720.0
        repeat(2) {
            val t = (jd0 + minutes / 1440.0 - 2451545.0) / 36525.0
            val ha = hourAngleAtHorizon(lat, declination(t)) ?: return null
            val delta = lon + (if (rising) deg(ha) else -deg(ha))
            minutes = 720 - 4 * delta - equationOfTime(t)
        }
        return dayStartMs + (minutes * 60_000).toLong()
    }

    /** Half the day's arc in radians, or null when the sun never crosses the horizon that day. */
    private fun hourAngleAtHorizon(lat: Double, decl: Double): Double? {
        val c = cos(rad(HORIZON_DEG)) / (cos(rad(lat)) * cos(decl)) - tan(rad(lat)) * tan(decl)
        return if (c < -1.0 || c > 1.0) null else acos(c)
    }

    private fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun meanLongitude(t: Double) = (280.46646 + t * (36000.76983 + 0.0003032 * t)) % 360
    private fun meanAnomaly(t: Double) = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    private fun eccentricity(t: Double) = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun apparentLongitude(t: Double): Double {
        val m = rad(meanAnomaly(t))
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val omega = 125.04 - 1934.136 * t
        return meanLongitude(t) + c - 0.00569 - 0.00478 * sin(rad(omega))
    }

    private fun obliquity(t: Double): Double {
        val seconds = 21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))
        val mean = 23.0 + (26.0 + seconds / 60.0) / 60.0
        return mean + 0.00256 * cos(rad(125.04 - 1934.136 * t))
    }

    private fun declination(t: Double): Double = asin(sin(rad(obliquity(t))) * sin(rad(apparentLongitude(t))))

    /** Minutes the sundial runs ahead of the clock. */
    private fun equationOfTime(t: Double): Double {
        val l0 = rad(meanLongitude(t))
        val e = eccentricity(t)
        val m = rad(meanAnomaly(t))
        val y = tan(rad(obliquity(t)) / 2).let { it * it }
        return 4 * deg(y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) - 0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m))
    }

    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
}
