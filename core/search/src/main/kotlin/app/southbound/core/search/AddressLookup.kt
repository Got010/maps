package app.southbound.core.search

import kotlin.math.abs

/** A street row from the pack's address_street table. */
data class AddressStreet(
    val id: Long,
    val street: String,
    val city: String?,
    val state: String,
    val streetKey: String,
    val searchText: String,
    val lat: Double,
    val lon: Double,
)

/** A house number on a street, from the pack's address table. */
data class HouseNumber(
    val id: Long,
    val number: String,
    val numberKey: Long,
    val postcode: String?,
    val lat: Double,
    val lon: Double,
)

/** How well a street (or a street row of `feature`) fits an [AddressQuery]. */
data class StreetFit(
    /** The typed street words are the whole street name, e.g. "main st" for "Main Street". */
    val exact: Boolean,
    /** How many typed words were the street (the rest are the town). */
    val streetWords: Int,
    /** The words after the street, taken as the town. */
    val townWords: List<String>,
    /** How many [townWords] are in the street row's text (its town, municipality and state). */
    val townMatched: Int,
) {
    val townFraction: Double get() = if (townWords.isEmpty()) 0.0 else townMatched.toDouble() / townWords.size
}

/**
 * The pure parts of the address lookup in [SearchIndex]: which streets fit,
 * where a missing number probably is, and the scores that rank addresses
 * against everything else.
 *
 * Scores share [SearchRanking]'s scale, where a place's text and importance
 * add up to about 160. An exact address (300) outranks any place at the
 * same distance; a nearest-number guess (200) and a bare street (120) come
 * after it. A typed town or ZIP that the row has adds [TOWN] / [ZIP], more
 * than any distance can take away inside the state, so "123 Main St Nyack"
 * puts Nyack first wherever you are; without a town, the closest wins.
 */
object AddressLookup {
    const val EXACT = 300.0
    /** "123" typed and "123A" found, or the other way round. */
    const val SAME_KEY = 290.0
    const val APPROXIMATE = 200.0
    const val STREET = 120.0
    const val STREET_EXACT = 40.0
    const val TOWN = 1000.0
    const val ZIP = 1000.0
    const val STATE = 10.0

    /** Neighbours further apart than this aren't interpolated between. */
    const val MAX_SPAN_METERS = 1000.0
    /** A guess past the ends of a street must be this close to a real number. */
    const val MAX_OVERHANG = 200L

    /**
     * How [query]'s words fit a street named [streetKey] (normalised, "main
     * st") whose row text is [searchText] (" main st nyack ny "). Null if
     * the first word doesn't even start a word of the street.
     *
     * Exact: the words begin with the whole street name. Otherwise the
     * longest run of leading words that each start a word of the name ("main
     * s" while typing) is the street. The words after it are the town.
     */
    fun streetFit(query: AddressQuery, streetKey: String, searchText: String): StreetFit? {
        val words = query.placeTokens
        val key = streetKey.split(' ').filter { it.isNotEmpty() }
        val exact = key.isNotEmpty() && words.size >= key.size && words.subList(0, key.size) == key
        val streetWords = if (exact) {
            key.size
        } else {
            val name = " $streetKey"
            words.indices.firstOrNull { !name.contains(" ${words[it]}") } ?: words.size
        }
        if (streetWords == 0) return null
        val town = words.drop(streetWords)
        return StreetFit(exact, streetWords, town, town.count { searchText.contains(" $it") })
    }

    /** Orders streets before their numbers are looked up: exact names, then towns typed. */
    fun streetOrder(fit: StreetFit): Double =
        (if (fit.exact) STREET_EXACT else 0.0) + fit.streetWords + TOWN * fit.townFraction

    /**
     * The score of an address or street result. [base] is [EXACT],
     * [SAME_KEY], [APPROXIMATE] or [STREET].
     */
    fun score(
        base: Double,
        fit: StreetFit,
        query: AddressQuery,
        postcode: String?,
        state: String?,
        distanceMeters: Double?,
    ): Double {
        var score = base + (if (fit.exact) STREET_EXACT else 0.0) + TOWN * fit.townFraction
        if (query.zip != null && query.zip == postcode) score += ZIP
        if (query.state != null && query.state.equals(state, ignoreCase = true)) score += STATE
        return score - (distanceMeters ?: 0.0) / 1000.0 * SearchRanking.POINTS_PER_KM
    }

    /** [EXACT] if the number is as typed, [SAME_KEY] if only the key matches ("123" and "123A"). */
    fun exactness(query: AddressQuery, house: HouseNumber): Double =
        if (house.number.equals(query.number, ignoreCase = true)) EXACT else SAME_KEY

    /**
     * Where house [target] probably is, from the numbers [below] it (nearest
     * first) and [above] it (nearest first) on the same street. Prefers
     * neighbours on the same side (same parity); between two within
     * [MAX_SPAN_METERS] it interpolates, otherwise it takes the nearer
     * number, if within [MAX_OVERHANG] of it. Null if there's no fair guess.
     *
     * The result carries the nearest neighbour's id and postcode.
     */
    fun nearestHouse(target: Long, below: List<HouseNumber>, above: List<HouseNumber>): HouseNumber? {
        fun sameSide(h: HouseNumber) = h.numberKey % 2 == target % 2
        val low = below.firstOrNull(::sameSide) ?: below.firstOrNull()
        val high = above.firstOrNull(::sameSide) ?: above.firstOrNull()
        if (low != null && high != null && high.numberKey > low.numberKey &&
            SearchRanking.distanceMeters(LatLon(low.lat, low.lon), high.lat, high.lon) <= MAX_SPAN_METERS
        ) {
            val t = (target - low.numberKey).toDouble() / (high.numberKey - low.numberKey)
            val nearer = if (t <= 0.5) low else high
            return nearer.copy(
                numberKey = target,
                lat = low.lat + (high.lat - low.lat) * t,
                lon = low.lon + (high.lon - low.lon) * t,
                postcode = nearer.postcode ?: low.postcode ?: high.postcode,
            )
        }
        val nearest = listOfNotNull(low, high).minByOrNull { abs(it.numberKey - target) } ?: return null
        if (abs(nearest.numberKey - target) > MAX_OVERHANG) return null
        return nearest.copy(numberKey = target)
    }
}
