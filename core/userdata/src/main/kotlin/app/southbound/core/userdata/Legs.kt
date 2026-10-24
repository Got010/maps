package app.southbound.core.userdata

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * How a leg was travelled. The same three names as the routing module's `TravelMode` (DRIVE, WALK, BIKE), kept here so
 * the saved places file does not depend on the router; the screen maps one to the other by name. Transit legs are not
 * logged.
 */
enum class LegMode { DRIVE, WALK, BIKE }

/**
 * One leg of the trip log (the mileage claim): a planned route from [from] to [to] that the user chose to log, on the
 * day [on], [meters] long. Nothing else: never a coordinate, a place reference, a road, an address, the route's line, a
 * time of day, a duration or a GPS trail. The labels are the user's own words (typed or picked from the log's chips),
 * never the map's or a pack's place names.
 *
 * [id] comes from [UserData.lastId] and is never reused: an order, not a time. [edited] is true once the distance was
 * changed by hand (the CSV's `source` says `edited` instead of `planned`).
 */
data class Leg(
    val id: Long,
    /** The trip it is claimed under: 1 to [MAX_TRIP] characters on one line (Lumen's trip bounds, so the names match). */
    val trip: String,
    /** The day, `YYYY-MM-DD`, a real calendar day. */
    val on: String,
    /** Whole metres, [MIN_METERS] to [MAX_METERS]. */
    val meters: Long,
    val mode: LegMode,
    /** 1 to [MAX_LABEL] characters on one line. */
    val from: String,
    val to: String,
    val edited: Boolean,
) {
    /**
     * Why this leg can't be kept, as a sentence for the person, or null when it can. The one rule for the sheet, the
     * export's check and the import's strict reader. It names the field, never what it holds.
     */
    fun problem(): String? = when {
        id < 1 -> "A leg's number is not positive."
        !oneLine(trip, MAX_TRIP) -> "The trip name must be 1 to $MAX_TRIP characters on one line."
        !isDay(on) -> "The date must be a real day, written YYYY-MM-DD."
        meters !in MIN_METERS..MAX_METERS -> "The distance must be more than 0 and at most 6,213.7 miles (10,000 km)."
        !oneLine(from, MAX_LABEL) -> "From must be 1 to $MAX_LABEL characters on one line."
        !oneLine(to, MAX_LABEL) -> "To must be 1 to $MAX_LABEL characters on one line."
        else -> null
    }

    companion object {
        const val MAX_TRIP = 40
        const val MAX_LABEL = 80
        const val MIN_METERS = 1L
        const val MAX_METERS = 10_000_000L

        private val DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        /** A real calendar day written `YYYY-MM-DD` (strict: no 2026-02-30), as Lumen's `VaultExpense.isDate`. */
        fun isDay(s: String): Boolean {
            if (!DAY.matches(s)) return false
            return try {
                LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)
                true
            } catch (e: DateTimeParseException) {
                false
            }
        }

        /** 1 to [max] characters, not blank, no line break or control character, no lone surrogate. */
        fun oneLine(s: String, max: Int): Boolean =
            s.length in 1..max && s.isNotBlank() && s.none { breaksLine(it) } && wellFormed(s)

        /**
         * What the user typed, as a label: line breaks, tabs and other control characters become spaces, runs of
         * spaces one, the ends trimmed, and cut to [max] characters (never through a surrogate pair). May be empty.
         */
        fun clean(typed: String, max: Int): String {
            val flat = typed.map { if (breaksLine(it)) ' ' else it }.joinToString("").replace(SPACES, " ").trim()
            if (flat.length <= max) return flat
            var end = max
            if (flat[end - 1].isHighSurrogate()) end--
            return flat.substring(0, end).trimEnd()
        }

        private val SPACES = Regex("\\s+")

        private fun breaksLine(c: Char): Boolean = c.isISOControl() || c == '\u2028' || c == '\u2029' || c == '\u0085'

        private fun wellFormed(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isHighSurrogate()) {
                    if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                    i += 2
                } else if (c.isLowSurrogate()) {
                    return false
                } else {
                    i++
                }
            }
            return true
        }
    }
}

/** A leg the log refused (a field out of bounds, or the log full); [message] is the sentence to show. */
class LegRefusedException(message: String) : IllegalArgumentException(message)

/** A trip as the log shows it: its name, its legs (by day, then id) and their totals. */
data class TripTotals(
    val trip: String,
    val legs: List<Leg>,
    /** The sum of the legs' miles as each row prints them, in tenths. */
    val tenthsMiles: Long,
    /** The same for kilometres. */
    val tenthsKm: Long,
    val firstDay: String,
    val lastDay: String,
) {
    val count: Int get() = legs.size
}

/**
 * The trip log as the screens read it, from [UserData.legs]: trips newest first (by their latest day, then their newest
 * leg), each trip's legs by day then id, the totals and the label chips. Pure; nothing here is stored.
 */
class LegLog(val legs: List<Leg>) {

    /** Every trip, newest first: by its latest day, then by its newest leg's id. */
    val trips: List<TripTotals> by lazy {
        legs.groupBy { it.trip }.map { (name, its) -> totals(name, its) }
            .sortedWith(compareByDescending<TripTotals> { it.lastDay }.thenByDescending { t -> t.legs.maxOf { it.id } })
    }

    fun trip(name: String): TripTotals? = trips.firstOrNull { it.trip == name }

    /** The trip names, the one with the newest leg first, then the others by their newest leg. */
    val tripNames: List<String>
        get() = legs.sortedByDescending { it.id }.map { it.trip }.distinct()

    /** The trip to preselect on the sheet: the one the newest leg went to, or null with an empty log. */
    val lastTrip: String? get() = legs.maxByOrNull { it.id }?.trip

    /** Labels already in the log (From and To alike), the newest leg's first, at most [MAX_CHIPS]. */
    fun labelChips(): List<String> =
        legs.sortedByDescending { it.id }.flatMap { listOf(it.to, it.from) }.distinct().take(MAX_CHIPS)

    /** Where [trip]'s last leg (by day, then id) ended: the natural From of its next one. */
    fun previousTo(trip: String): String? =
        legs.filter { it.trip == trip }.maxWithOrNull(BY_DAY_THEN_ID)?.to

    companion object {
        const val MAX_LEGS = 5_000
        const val MAX_CHIPS = 8

        val BY_DAY_THEN_ID: Comparator<Leg> = compareBy<Leg> { it.on }.thenBy { it.id }

        /** [trip]'s legs in order, with the totals each printed row adds up to. */
        fun totals(trip: String, its: List<Leg>): TripTotals {
            val ordered = its.sortedWith(BY_DAY_THEN_ID)
            return TripTotals(
                trip = trip,
                legs = ordered,
                tenthsMiles = ordered.sumOf { Distance.tenthsMiles(it.meters) },
                tenthsKm = ordered.sumOf { Distance.tenthsKm(it.meters) },
                firstDay = ordered.first().on,
                lastDay = ordered.last().on,
            )
        }
    }
}

/** Metres to the one-decimal miles and kilometres the log, the CSV and the mileage line print. */
object Distance {
    const val METERS_PER_MILE = 1_609.344

    /** Miles to one decimal, in tenths, rounded half up (exact integer arithmetic: 1 mile = 1,609,344 mm). */
    fun tenthsMiles(meters: Long): Long = (meters * 20_000 + 1_609_344) / 3_218_688

    /** Kilometres to one decimal, in tenths, rounded half up. */
    fun tenthsKm(meters: Long): Long = (meters + 50) / 100

    /** "212.4" from 2124 tenths. */
    fun format(tenths: Long): String = "${tenths / 10}.${tenths % 10}"

    /** Whole metres from miles typed on the sheet, or null when that is not a number. */
    fun metersFromMiles(miles: Double): Long? =
        if (!miles.isFinite() || miles < 0) null else Math.round(miles * METERS_PER_MILE)
}
