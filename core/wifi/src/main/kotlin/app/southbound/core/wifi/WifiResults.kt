package app.southbound.core.wifi

import app.southbound.core.search.LatLon
import app.southbound.core.search.OpeningHours
import app.southbound.core.search.SearchRanking
import java.time.LocalDateTime

/** What the nearest-Wi-Fi list does with the rows the pack hands over: the circle, the hours, the order. */
object WifiResults {

    /**
     * The [spots] within [radiusMeters] of [center] and of one of [tiers], nearest first, at most [limit].
     *
     * With [openNow] a spot stays only if its `opening_hours` says it is open at [now]. A spot whose hours are
     * unknown (no tag, or one [OpeningHours] can't read) stays if [hoursUnknownCounts], as most free Wi-Fi spots
     * (libraries and hotel lobbies aside) carry no hours at all: dropping them would empty the list. A spot that
     * is closed never stays.
     *
     * Ties in distance go to the better tier, then the lower id, so the order never shuffles between reads.
     */
    fun select(
        spots: List<WifiSpot>,
        center: LatLon,
        radiusMeters: Double,
        tiers: Set<WifiTier>,
        openNow: Boolean,
        now: LocalDateTime,
        hoursUnknownCounts: Boolean,
        limit: Int,
    ): List<WifiResult> =
        spots.asSequence()
            .filter { it.tier in tiers }
            .map { WifiResult(it, SearchRanking.distanceMeters(center, it.lat, it.lon)) }
            .filter { it.distanceMeters <= radiusMeters }
            .filter { !openNow || isOpen(it.spot.openingHours, now, hoursUnknownCounts) }
            .sortedWith(compareBy<WifiResult>({ it.distanceMeters }, { it.spot.tier.code }, { it.spot.id }))
            .take(limit.coerceAtLeast(0))
            .toList()

    /**
     * The list the user sees: [results] (nearest first) with the spots the user marked "didn't work" ([didntWork], by
     * [WifiSpot.osmRef]) after every other row, or left out with [hide]. The order within each part is unchanged
     * (a stable partition), so the pipeline's distance order still holds for both.
     */
    fun arrange(results: List<WifiResult>, didntWork: Set<String>, hide: Boolean): List<WifiResult> {
        if (didntWork.isEmpty()) return results
        val (failed, rest) = results.partition { it.spot.osmRef in didntWork }
        return if (hide) rest else rest + failed
    }

    /** Whether [openingHours] allow a visit at [now]; see [select] for [unknownCounts]. */
    fun isOpen(openingHours: String?, now: LocalDateTime, unknownCounts: Boolean): Boolean =
        when (OpeningHours.statusOf(openingHours, now)) {
            is OpeningHours.Status.Open -> true
            is OpeningHours.Status.Closed -> false
            OpeningHours.Status.Unknown -> unknownCounts
        }

    /**
     * How many rows to read from the pack for a list of [limit]: the nearest few, enough that cutting the square's
     * corners to the circle still leaves [limit]; many more when [openNow] drops the closed ones after the read.
     */
    fun candidateRows(limit: Int, openNow: Boolean): Int =
        if (openNow) maxOf(limit * OPEN_NOW_FACTOR, MIN_OPEN_NOW_ROWS) else limit * CORNER_FACTOR

    private const val CORNER_FACTOR = 2
    private const val OPEN_NOW_FACTOR = 25
    private const val MIN_OPEN_NOW_ROWS = 1_000
}
