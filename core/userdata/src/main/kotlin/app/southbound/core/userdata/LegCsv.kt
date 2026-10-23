package app.southbound.core.userdata

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The trip log as text that leaves the phone only when the user shares or saves it: a CSV of a trip's legs and its one
 * mileage line (for Lumen's Share Summary, whose typed mileage line is cut at [MAX_LINE] characters, or a claim form).
 * Android-free. What it holds is what a leg holds: the trip, the day, the two labels, the mode and the distance; never
 * a coordinate, a road or a time of day.
 */
object LegCsv {
    val HEADER = listOf("trip", "date", "from", "to", "mode", "miles", "km", "source")

    /** Lumen's typed mileage line is cut at 60 characters, so the line is never longer. */
    const val MAX_LINE = 60

    private const val CRLF = "\r\n"

    /**
     * [legs] as RFC 4180 CSV: the header, then one row per leg by day then id, CRLF line ends, no total row. Miles and
     * kilometres to one decimal from the whole metres; `source` is `planned`, or `edited` when the distance was changed
     * by hand. A text field starting with `=`, `+`, `-`, `@` or a tab gets a leading `'`, so a spreadsheet never reads
     * it as a formula.
     */
    fun csv(legs: List<Leg>): String {
        val out = StringBuilder()
        out.append(HEADER.joinToString(",")).append(CRLF)
        for (leg in legs.sortedWith(LegLog.BY_DAY_THEN_ID)) {
            val row = listOf(
                field(leg.trip),
                leg.on,
                field(leg.from),
                field(leg.to),
                leg.mode.name.lowercase(),
                Distance.format(Distance.tenthsMiles(leg.meters)),
                Distance.format(Distance.tenthsKm(leg.meters)),
                if (leg.edited) "edited" else "planned",
            )
            out.append(row.joinToString(",")).append(CRLF)
        }
        return out.toString()
    }

    /** One text field: the formula guard, then quoted when it holds a comma, a quote or a line break. */
    internal fun field(text: String): String {
        val guarded = if (text.isNotEmpty() && text[0] in FORMULA_STARTS) "'$text" else text
        return if (guarded.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"" + guarded.replace("\"", "\"\"") + "\""
        } else {
            guarded
        }
    }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t')

    /**
     * "412.3 mi (663.5 km), 5 legs, 2026-10-24 to 2026-10-30": the trip's total as its CSV rows print it (the sum of
     * the rows' one-decimal values, so the line and the file agree), at most [MAX_LINE] characters: past that the
     * kilometres go, then the day range. One day reads as that day alone.
     */
    fun mileageLine(trip: TripTotals): String {
        val miles = "${Distance.format(trip.tenthsMiles)} mi"
        val km = " (${Distance.format(trip.tenthsKm)} km)"
        val count = ", ${trip.count} ${if (trip.count == 1) "leg" else "legs"}"
        val range = if (trip.firstDay == trip.lastDay) ", ${trip.firstDay}" else ", ${trip.firstDay} to ${trip.lastDay}"
        return listOf(miles + km + count + range, miles + count + range).firstOrNull { it.length <= MAX_LINE } ?: (miles + count)
    }

    /** The name Save CSV file offers: `southbound-legs-20261024.csv`, from the day it is saved. */
    fun fileName(today: LocalDate): String = "southbound-legs-${today.format(DateTimeFormatter.BASIC_ISO_DATE)}.csv"
}
