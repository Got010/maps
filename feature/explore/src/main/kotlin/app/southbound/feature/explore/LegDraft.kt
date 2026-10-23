package app.southbound.feature.explore

import app.southbound.core.routing.TravelMode
import app.southbound.core.userdata.Distance
import app.southbound.core.userdata.Leg
import app.southbound.core.userdata.LegLog
import app.southbound.core.userdata.LegMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * What the leg sheet holds while it is open: the fields as typed, and the distance it started from. Plain Kotlin, no
 * Android, so the rules are tested on the JVM; the sheet keeps it with `remember` only (never saved state: To may hold
 * the typed search text, which must not outlive the screen unless the leg is saved).
 *
 * The miles field starts as the planned distance printed to one decimal ([plannedMiles]); while it still reads that,
 * the leg keeps the exact planned metres, and a change sets `edited`.
 */
internal data class LegDraft(
    /** The leg being changed from the log, or null for a new one from the route preview. */
    val legId: Long?,
    val trip: String,
    val day: String,
    val miles: String,
    val mode: LegMode,
    val from: String,
    val to: String,
    val plannedMeters: Long,
    val plannedMiles: String,
) {
    /** Whole metres: the planned ones while the miles read as they started, else the typed miles; null when not a number. */
    fun meters(): Long? = if (miles.trim() == plannedMiles) plannedMeters else parseMiles(miles)

    /** The distance is not the one the sheet started with (a new leg is then saved as edited). */
    val distanceChanged: Boolean get() = meters() != plannedMeters

    val cleanTrip: String get() = Leg.clean(trip, Leg.MAX_TRIP)
    val cleanFrom: String get() = Leg.clean(from, Leg.MAX_LABEL)
    val cleanTo: String get() = Leg.clean(to, Leg.MAX_LABEL)
    val cleanDay: String get() = day.trim()

    /** Why Save can't keep this yet, as the sentence to show, or null. The store checks [Leg.problem] again. */
    fun problem(): String? {
        val m = meters() ?: return MILES_PROBLEM
        return Leg(legId ?: 1, cleanTrip, cleanDay, m, mode, cleanFrom, cleanTo, edited = false).problem()
    }

    companion object {
        const val MILES_PROBLEM = "Miles must be a number, such as 212.4."

        private val MILES = Regex("[0-9]{1,7}(\\.[0-9]*)?|\\.[0-9]+")

        /** A new leg for the route option chosen in the preview: its distance, its mode, today, the last trip used. */
        fun forRoute(distanceMeters: Double, mode: LegMode, today: LocalDate, log: LegLog): LegDraft {
            val meters = if (distanceMeters.isFinite() && distanceMeters > 0) Math.round(distanceMeters) else 0L
            val miles = Distance.format(Distance.tenthsMiles(meters))
            return LegDraft(
                legId = null,
                trip = log.lastTrip.orEmpty(),
                day = today.format(DateTimeFormatter.ISO_LOCAL_DATE),
                miles = miles,
                mode = mode,
                // Never filled from the map or a pack: the user types them or taps a chip.
                from = "",
                to = "",
                plannedMeters = meters,
                plannedMiles = miles,
            )
        }

        /** A leg from the log, to change. */
        fun forLeg(leg: Leg): LegDraft {
            val miles = Distance.format(Distance.tenthsMiles(leg.meters))
            return LegDraft(leg.id, leg.trip, leg.on, miles, leg.mode, leg.from, leg.to, leg.meters, miles)
        }

        /** "212.4", "212", "1,212.4" or ".5" miles as whole metres; null for anything else. */
        fun parseMiles(text: String): Long? {
            val t = text.trim().replace(",", "")
            if (!MILES.matches(t)) return null
            return Distance.metersFromMiles(t.toDouble())
        }

        /** [day] moved by [by] days, or null when [day] isn't a real day. */
        fun shiftDay(day: String, by: Long): String? = try {
            LocalDate.parse(day.trim(), DateTimeFormatter.ISO_LOCAL_DATE).plusDays(by).format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: DateTimeParseException) {
            null
        }

        /** The From chips: the trip's previous To first, then the log's labels, at most [LegLog.MAX_CHIPS]. */
        fun fromChips(log: LegLog, trip: String): List<String> =
            (listOfNotNull(log.previousTo(Leg.clean(trip, Leg.MAX_TRIP))) + log.labelChips()).distinct().take(LegLog.MAX_CHIPS)

        /** The To chips: the log's labels. "What I searched" is offered beside them, on its own ([searchedChip]). */
        fun toChips(log: LegLog): List<String> = log.labelChips()

        /**
         * The text of the "What I searched" chip: the query typed for the place this route goes to, cleaned to one
         * label, or null when there is none (the destination wasn't picked from a typed search).
         */
        fun searchedChip(query: String?): String? = query?.let { Leg.clean(it, Leg.MAX_LABEL) }?.takeIf { it.isNotEmpty() }

        /** The leg's mode for the route's: the same three names. */
        fun legMode(mode: TravelMode): LegMode = when (mode) {
            TravelMode.DRIVE -> LegMode.DRIVE
            TravelMode.WALK -> LegMode.WALK
            TravelMode.BIKE -> LegMode.BIKE
        }

        /** The trips offered as chips, the newest first, at most [LegLog.MAX_CHIPS]. */
        fun tripChips(log: LegLog): List<String> = log.tripNames.take(LegLog.MAX_CHIPS)
    }
}
