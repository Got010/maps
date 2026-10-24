package app.southbound.core.road

import app.southbound.core.search.LatLon
import app.southbound.core.search.SearchRanking

/**
 * What the Road layer and the lists do with the rows the pack hands over. The pack files a place that is two things
 * under both (the pumps of a truck stop are `fuel` and `truck`, a services area with `hgv=yes` is `rest` and `truck`),
 * one row each, with the same coordinates, name and brand; shown as they come, the same place would be listed twice
 * and pinned twice. [distinct] folds them.
 */
object RoadPlaces {

    /**
     * [spots] with each place once, at the first row of it in [spots]'s order (so a list sorted by distance keeps
     * its order). A place is the rows with the same position, name and brand. The row that shows is the better
     * category: a truck stop over its pumps or its services area, else the table's order ([RoadCategory]); it takes
     * the facts the other rows have that it lacks (the pumps' diesel), and lists their categories in
     * [RoadSpot.alsoCategories]. Rows that are not the same place are never folded, even at one point.
     */
    fun distinct(spots: List<RoadSpot>): List<RoadSpot> {
        if (spots.size < 2) return spots
        val groups = LinkedHashMap<PlaceKey, MutableList<RoadSpot>>()
        for (spot in spots) groups.getOrPut(PlaceKey(spot)) { mutableListOf() }.add(spot)
        return groups.values.map { fold(it) }
    }

    /** One place's rows folded into one spot (see [distinct]); [rows] must not be empty. */
    fun fold(rows: List<RoadSpot>): RoadSpot {
        if (rows.size == 1) return rows.first()
        val best = rows.minWith(compareBy<RoadSpot>({ if (it.category == RoadCategory.TRUCK) 0 else 1 }, { it.category.ordinal }, { it.id }))
        val others = rows.filter { it !== best }
        val extra = others.fold(best.extra) { acc, other -> acc.orElse(other.extra) }
        return best.copy(extra = extra, alsoCategories = (best.alsoCategories + others.map { it.category }.filter { it != best.category }).toSet())
    }
}

/** The rows of one place: the same position, name and brand. */
internal data class PlaceKey(val lat: Double, val lon: Double, val name: String?, val brand: String?) {
    constructor(spot: RoadSpot) : this(spot.lat, spot.lon, spot.name, spot.brand)
}

/** What the Road list does with the rows the pack hands over: the circle, the places, the order. */
object RoadResults {

    /**
     * The [spots] within [radiusMeters] of [center], each place once ([RoadPlaces.distinct]), nearest first, at most
     * [limit]. Ties in distance go to the table's order, then the lower id, so the order never shuffles between reads.
     */
    fun select(spots: List<RoadSpot>, center: LatLon, radiusMeters: Double, limit: Int): List<RoadResult> =
        RoadPlaces.distinct(spots).asSequence()
            .map { RoadResult(it, SearchRanking.distanceMeters(center, it.lat, it.lon)) }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedWith(compareBy<RoadResult>({ it.distanceMeters }, { it.spot.category.ordinal }, { it.spot.id }))
            .take(limit.coerceAtLeast(0))
            .toList()

    /**
     * How many rows to read from the pack for a list of [limit]: the nearest few, enough that cutting the square's
     * corners to the circle and folding a place's second row still leaves [limit].
     */
    fun candidateRows(limit: Int): Int = limit * CANDIDATE_FACTOR

    private const val CANDIDATE_FACTOR = 3
}
