package app.southbound.core.transit

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * GTFS times of day ("08:05:00", "25:10:00") count from noon minus 12 hours on the service day, not from
 * midnight. The two are the same instant except on the two days a year the clocks change: on the spring day noon
 * minus 12 hours is 23:00 the evening before, and on the autumn day it is the first 01:00. Counting from it keeps
 * a time of 08:00:00 at 8 am on the wall clock on those days too, where counting from midnight is an hour off.
 */
object GtfsTime {
    /** The instant, in POSIX seconds, that GTFS times on [serviceDate] count from. */
    fun dayStart(serviceDate: LocalDate, zone: ZoneId = RealtimeSnapshot.NEW_YORK): Long =
        serviceDate.atTime(LocalTime.NOON).atZone(zone).toEpochSecond() - HALF_DAY_SECONDS

    /** [epochSeconds] as a GTFS time on [serviceDate]: seconds after [dayStart], which may pass 24 hours. */
    fun secondsOf(epochSeconds: Long, serviceDate: LocalDate, zone: ZoneId = RealtimeSnapshot.NEW_YORK): Int =
        (epochSeconds - dayStart(serviceDate, zone)).toInt()

    private const val HALF_DAY_SECONDS = 12 * 3600L
}
