package app.southbound.core.search

/**
 * Picks a landmark for a turn, Apple style: "at the next light", "after the
 * next light", "at the stop sign", "past the Shell gas station", "at the
 * Walgreens". Pure, so the geometry rules are unit tested; the app feeds it
 * the landmarks near the route (from [SearchIndex.landmarks]).
 *
 * A phrase is only offered when it can't point at the wrong corner: the
 * signal or sign must sit on the road the driver is on, with no other one
 * between the driver and it, and a place must be the only one of its kind
 * at that turn.
 */
object Landmarks {

    /** A signal this close to the turn is the turn's own light. */
    const val SIGNAL_AT_M = 12.0

    /** A signal this far before the turn still names it: "after the next light". */
    const val SIGNAL_BEFORE_M = 40.0

    /** How far off the approach road a signal or sign may be mapped (the other carriageway, a pole). */
    const val ON_ROAD_M = 20.0

    /** Signals mapped this close together are one intersection (one per carriageway). */
    const val SAME_JUNCTION_M = 30.0

    const val STOP_RADIUS_M = 30.0

    /** Places within this of the turn qualify. */
    const val PLACE_RADIUS_M = 50.0

    /** A place this close to the turn is on its corner: "at the ...". */
    const val PLACE_AT_M = 25.0

    /** A place passed on the way in must be roadside, within this of the approach. */
    const val ROADSIDE_M = 35.0

    /** How far from the turn places are worth fetching, for callers that pre-filter (see [near]). */
    const val SEARCH_RADIUS_M = 60.0

    /**
     * The driver may hear a prompt this much further back than its trigger distance says: GPS that runs a little
     * ahead of the car, Ferrostar's own 5 m, and a fix a second old. A light in that stretch makes "the next light"
     * ambiguous too, so it is counted.
     */
    const val HEARD_MARGIN_M = 30.0

    private const val MAX_NAME_LENGTH = 30

    /**
     * The phrase for the turn at the end of [approach] (the step's geometry,
     * ending at the turn), heard [heardFromMeters] before the turn, or null.
     */
    fun phraseFor(approach: List<LatLon>, nearby: List<Landmark>, heardFromMeters: Double = 400.0): String? {
        if (approach.size < 2) return null
        val turn = approach.last()
        val line = RouteLine(approach)
        val heardAt = (line.length - heardFromMeters.coerceAtLeast(SIGNAL_BEFORE_M) - HEARD_MARGIN_M).coerceAtLeast(0.0)

        signalPhrase(line, turn, nearby, heardAt)?.let { return it }
        stopPhrase(line, turn, nearby, heardAt)?.let { return it }
        return placePhrase(line, turn, nearby)
    }

    /**
     * The landmarks [phraseFor] needs from those near the route: places by the turn, and every light and stop sign
     * the driver may still pass after hearing a prompt [heardFromMeters] before the turn. Only lights near the turn
     * would make a light further back invisible, and "at the next light" wrong when there are two.
     */
    fun near(turn: LatLon, nearRoute: List<Landmark>, heardFromMeters: Double): List<Landmark> {
        val signReach = heardFromMeters.coerceAtLeast(SIGNAL_BEFORE_M) + HEARD_MARGIN_M + ON_ROAD_M
        return nearRoute.filter {
            val d = RouteLine.distanceMeters(it.point, turn)
            if (it.category == Landmark.TRAFFIC_SIGNALS || it.category == Landmark.STOP) d <= signReach else d <= SEARCH_RADIUS_M
        }
    }

    /** Signs of one kind on the approach between where the driver hears it and the turn, by distance before the turn. */
    private fun onApproach(line: RouteLine, turn: LatLon, nearby: List<Landmark>, category: String, heardAt: Double): List<Double> =
        nearby.filter { it.category == category }.mapNotNull { sign ->
            val loc = line.locate(sign.point) ?: return@mapNotNull null
            val atTurn = RouteLine.distanceMeters(sign.point, turn)
            when {
                // On the cross street or the corner: it belongs to the turn.
                atTurn <= SIGNAL_AT_M -> 0.0
                loc.offset <= ON_ROAD_M && loc.along >= heardAt -> line.length - loc.along
                else -> null
            }
        }.sorted()

    private fun signalPhrase(line: RouteLine, turn: LatLon, nearby: List<Landmark>, heardAt: Double): String? {
        val before = onApproach(line, turn, nearby, Landmark.TRAFFIC_SIGNALS, heardAt)
        val nearest = before.firstOrNull() ?: return null
        if (nearest > SIGNAL_BEFORE_M) return null
        // Another light further back would be "the next light" instead.
        if (before.any { it > nearest + SAME_JUNCTION_M }) return null
        return if (nearest <= SIGNAL_AT_M) "at the next light" else "after the next light"
    }

    private fun stopPhrase(line: RouteLine, turn: LatLon, nearby: List<Landmark>, heardAt: Double): String? {
        val before = onApproach(line, turn, nearby, Landmark.STOP, heardAt)
        val nearest = before.firstOrNull() ?: return null
        if (nearest > STOP_RADIUS_M) return null
        if (before.any { it > nearest + SAME_JUNCTION_M }) return null
        return "at the stop sign"
    }

    private fun placePhrase(line: RouteLine, turn: LatLon, nearby: List<Landmark>): String? {
        data class Candidate(val label: String, val atTurn: Double, val phrase: String)
        val candidates = nearby.mapNotNull { place ->
            val label = label(place) ?: return@mapNotNull null
            val atTurn = RouteLine.distanceMeters(place.point, turn)
            if (atTurn > PLACE_RADIUS_M) return@mapNotNull null
            if (atTurn <= PLACE_AT_M) return@mapNotNull Candidate(label, atTurn, "at $label")
            val loc = line.locate(place.point) ?: return@mapNotNull null
            val before = line.length - loc.along
            // Passed on the way in, beside the road: "past the ...". Anything else is behind the turn.
            if (loc.offset <= ROADSIDE_M && before in PLACE_AT_M..PLACE_RADIUS_M) {
                Candidate(label, atTurn, "past $label")
            } else {
                null
            }
        }
        val best = candidates.minByOrNull { it.atTurn } ?: return null
        // Two of the same ("the gas station") at one turn: say nothing rather than guess.
        if (candidates.count { it.label == best.label } > 1) return null
        return best.phrase
    }

    /** "the Shell gas station", "the Walgreens", "the church"; null for things not worth naming. */
    fun label(place: Landmark): String? {
        val name = place.name?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
        val named = name?.let { if (it.startsWith("The ", ignoreCase = true)) it else "the $it" }
        return when (place.category) {
            Landmark.FUEL -> when {
                name == null -> "the gas station"
                Regex("""\b(gas|station|fuel)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name) -> named
                else -> "$named gas station"
            }
            Landmark.FAST_FOOD -> named
            Landmark.BANK -> when {
                name == null -> "the bank"
                name.contains("bank", ignoreCase = true) -> named
                else -> "$named bank"
            }
            Landmark.PHARMACY -> named ?: "the pharmacy"
            Landmark.SUPERMARKET -> named ?: "the supermarket"
            // Church and school names read badly aloud ("the P.S. 41 Greenwich Village").
            Landmark.CHURCH -> "the church"
            Landmark.SCHOOL -> "the school"
            else -> null
        }
    }
}
