package app.southbound.core.search

import kotlin.math.roundToInt

/**
 * Overture Places next to OSM's places in search results.
 *
 * The search pack keeps Overture's rows in their own tables (overture_place,
 * overture_fts; see pipeline/search/overture.py), because Overture notes
 * that a database joined with OSM may inherit OSM's ODbL. They meet OSM's
 * rows only here, per query: [merge] drops an Overture row that repeats an
 * OSM place, and [importance] ranks the rest a little below OSM's.
 *
 * Overture rows are for search only: guidance landmarks and "nearby" read
 * OSM's tables alone.
 */
object OverturePlaces {

    /** An Overture row with the same name as an OSM place this close is that place. */
    const val DUPLICATE_METERS = 60.0

    /**
     * Closer than this, a name that merely starts the other ("Starbucks" and
     * "Starbucks Coffee") is the same place too.
     */
    const val PREFIX_DUPLICATE_METERS = 30.0

    /** Importance an Overture row gives up against an OSM place row, so OSM wins ties. */
    const val PENALTY = 5

    /** What an OSM place row starts from (the pipeline's place_importance). */
    private const val PLACE_BASE = 30

    /** Up to this much lost for a confidence of 0 (Overture's own 0..1 score). */
    private const val CONFIDENCE_POINTS = 10.0

    /** Categories the pipeline's place_importance also raises (+10). */
    private val NOTABLE = setOf("attraction", "museum", "park", "stadium", "university", "hospital")

    /**
     * An Overture row's importance, on the scale of `feature.importance`: an
     * OSM place's base less [PENALTY], less up to [CONFIDENCE_POINTS] for a
     * shaky row, plus the notable-category bonus OSM rows get.
     */
    fun importance(category: String?, confidence: Double): Int {
        val notable = if (category in NOTABLE) 10 else 0
        val doubt = ((1.0 - confidence.coerceIn(0.0, 1.0)) * CONFIDENCE_POINTS).roundToInt()
        return PLACE_BASE - PENALTY + notable - doubt
    }

    /**
     * [osm] and [overture] candidates as one list, without the Overture rows
     * that repeat an OSM place or locality ([isDuplicate]). An OSM place
     * missing a phone or website takes its Overture twin's.
     */
    fun merge(osm: List<Candidate>, overture: List<Candidate>): List<Candidate> {
        if (overture.isEmpty()) return osm
        // OSM places and localities by the first word of their name, to compare only likely twins.
        val byWord = HashMap<String, MutableList<Int>>()
        osm.forEachIndexed { i, c ->
            if (c.place.kind == PlaceKind.PLACE || c.place.kind == PlaceKind.LOCALITY) {
                byWord.getOrPut(firstWord(c.nameKey)) { ArrayList() } += i
            }
        }
        val merged = osm.toMutableList()
        val kept = ArrayList<Candidate>()
        for (o in overture) {
            val twin = byWord[firstWord(o.nameKey)]?.firstOrNull { isDuplicate(osm[it], o) }
            if (twin == null) {
                kept += o
                continue
            }
            val place = merged[twin].place
            if ((place.phone == null && o.place.phone != null) || (place.website == null && o.place.website != null)) {
                merged[twin] = merged[twin].copy(
                    place = place.copy(phone = place.phone ?: o.place.phone, website = place.website ?: o.place.website),
                )
            }
        }
        return merged + kept
    }

    /**
     * Whether Overture's [overture] row is OSM's [osm] place: the same
     * normalised name within [DUPLICATE_METERS], or one name starting the
     * other within [PREFIX_DUPLICATE_METERS].
     */
    fun isDuplicate(osm: Candidate, overture: Candidate): Boolean {
        val a = osm.nameKey
        val b = overture.nameKey
        if (a.isEmpty() || b.isEmpty()) return false
        val sameName = a == b
        if (!sameName && !a.startsWith("$b ") && !b.startsWith("$a ")) return false
        val meters = SearchRanking.distanceMeters(LatLon(osm.place.lat, osm.place.lon), overture.place.lat, overture.place.lon)
        return meters <= if (sameName) DUPLICATE_METERS else PREFIX_DUPLICATE_METERS
    }

    private fun firstWord(key: String): String = key.substringBefore(' ')
}
