package app.southbound.core.search

/**
 * Kinds of place to look through without typing, for the chips under the search box ("Gas", "Hotels"): the
 * category values that make each one up, OSM's and Overture's as the pipeline maps them (overture.py
 * `CATEGORY_MAP`: `gas_station` is stored as `fuel`, `coffee_shop` as `cafe`).
 *
 * Every place row's search text carries its category as words (build_search.py and overture.py), so [ftsMatch]
 * finds the candidates through the pack's trigram index, and [sqlCondition] keeps only the rows really of the
 * kind: "Fuel Grill" has "fuel" in its text, not in its category.
 */
enum class PlaceCategory(
    /** Category values as the pack stores them. */
    val categories: Set<String>,
    /** Overture taxonomy slugs the pipeline keeps as they are, ending in one of these, count too. */
    val suffixes: List<String> = emptyList(),
) {
    GAS(setOf("fuel")),
    // "pizza_restaurant", "sushi_restaurant": Overture's own slugs, kept as they are.
    FOOD(setOf("restaurant", "fast_food", "food_court"), suffixes = listOf("_restaurant")),
    COFFEE(setOf("cafe")),
    HOTELS(setOf("hotel", "motel", "hostel", "guest_house", "bed_and_breakfast")),
    GROCERIES(setOf("supermarket", "grocery_store", "greengrocer")),
    PHARMACY(setOf("pharmacy", "chemist")),
    PARKING(setOf("parking")),
    EV_CHARGING(setOf("charging_station")),
    ;

    /** Whether a row's category is of this kind. */
    fun matches(category: String?): Boolean =
        category != null && (category in categories || suffixes.any { category.endsWith(it) })

    /**
     * The FTS5 query for rows whose text has any of the categories' words: `("fue" AND "uel") OR (...)`. The words
     * are normalised as the pipeline normalised the text, so "food court" looks for "food ct".
     */
    val ftsMatch: String
        get() = (categories + suffixes.map { it.trimStart('_') })
            .mapNotNull { SearchQuery.trigramMatch(SearchText.normalize(it.replace('_', ' ')).split(' ')) }
            .distinct()
            .joinToString(" OR ") { "($it)" }

    /**
     * SQL that [column] is one of this kind's categories, and its arguments: `(f.category IN (?, ?) OR f.category
     * LIKE ? ESCAPE '\')`, with the suffixes' underscores escaped so LIKE takes them literally.
     */
    fun sqlCondition(column: String): Pair<String, List<String>> {
        val parts = ArrayList<String>()
        parts += "$column IN (${categories.joinToString(", ") { "?" }})"
        suffixes.forEach { parts += "$column LIKE ? ESCAPE '\\'" }
        return parts.joinToString(" OR ", "(", ")") to (categories.toList() + suffixes.map { "%" + it.replace("_", "\\_") })
    }

    companion object {
        /** How far the chips look: 30 miles, a drive's worth, for the motel or the gas station out of town. */
        const val RADIUS_KM = 48.28

        /** Places a chip lists. */
        const val LIMIT = 40

        /**
         * One pack's [osm] and [overture] rows of a kind as one list, as search merges them ([OverturePlaces.merge]):
         * an Overture row that repeats an OSM place goes. Those within [RADIUS_KM] of [near], nearest first.
         */
        fun nearest(osm: List<Candidate>, overture: List<Candidate>, near: LatLon, limit: Int): List<SearchResult> =
            NearbyBox.within(OverturePlaces.merge(osm, overture).map { it.place }, near, RADIUS_KM * 1000, limit)
    }
}
