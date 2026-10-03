package app.southbound.core.transit

import java.time.DayOfWeek
import java.time.LocalDate

enum class TransitSystem(val code: String) {
    SUBWAY("subway"),
    BUS("bus"),
    LIRR("lirr"),
    METRO_NORTH("mnr"),
    ;

    companion object {
        fun of(code: String): TransitSystem? = entries.firstOrNull { it.code == code }
    }
}

data class TransitStop(
    val id: Int,
    val system: TransitSystem?,
    val gtfsId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** The station this platform belongs to, or null. */
    val parent: Int?,
    val isStation: Boolean,
    /** Minimum time to change vehicles here. */
    val changeSeconds: Int,
)

data class TransitRoute(
    val id: Int,
    val system: TransitSystem?,
    val gtfsId: String,
    val shortName: String?,
    val longName: String?,
    /** GTFS route_type: 1 subway, 2 rail, 3 bus. */
    val type: Int,
    /** "D82233", without '#'. */
    val color: String?,
    val textColor: String?,
    val sortOrder: Int?,
) {
    /** "A", "B44-SBS" or "Babylon Branch": what a line bullet or chip shows. */
    val label: String get() = shortName?.takeIf { it.isNotBlank() } ?: longName ?: gtfsId
}

/** Trips of one route serving the same stops in the same order. */
class TransitPattern(
    val id: Int,
    val route: Int,
    val headsign: String?,
    /** GTFS direction_id: 0 or 1, null when the feed doesn't say. */
    val direction: Int?,
    val stops: IntArray,
    /** Per stop: may passengers board / alight here. */
    val pickup: BooleanArray,
    val dropOff: BooleanArray,
)

/** One GTFS calendar entry with its date exceptions. */
data class Service(
    val id: Int,
    /** Monday = bit 0 ... Sunday = bit 6. */
    val days: Int,
    /** yyyymmdd; 0 when the service only has dated exceptions. */
    val startDate: Int,
    val endDate: Int,
    /** yyyymmdd -> true (added) or false (removed). */
    val exceptions: Map<Int, Boolean> = emptyMap(),
) {
    fun runsOn(date: LocalDate): Boolean {
        val key = date.toKey()
        exceptions[key]?.let { return it }
        if (startDate == 0 || key < startDate || key > endDate) return false
        return days and (1 shl date.dayOfWeek.bit()) != 0
    }
}

internal fun LocalDate.toKey(): Int = year * 10_000 + monthValue * 100 + dayOfMonth

private fun DayOfWeek.bit(): Int = value - 1 // MONDAY = 1
