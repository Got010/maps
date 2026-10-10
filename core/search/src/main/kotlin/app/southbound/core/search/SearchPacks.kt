package app.southbound.core.search

import kotlin.math.abs

/**
 * Several search packs read as one: New York's (search-nys) and the OSM-only packs of the other states' regions
 * (search-ct, search-pae, ...), one per region a phone has.
 *
 * Results are merged by their scores, which every pack computes the same way ([SearchRanking]), so a name match
 * near the user beats a weaker one far off whichever pack holds it. A place two packs both hold (the strip where
 * regions meet: a town on the Delaware is in both Pennsylvania's and New Jersey's) comes once. Ids are each
 * pack's own; the second pack's and later ones are moved up by [PACK_ID_STRIDE] times their position, so a result
 * or landmark id is unique and [place] knows which pack to ask. The first pack keeps its ids.
 */
class SearchPacks(private val packs: List<SearchIndex>) : PlaceSearch {

    override fun search(raw: String, near: LatLon?, limit: Int): List<SearchResult> =
        merge(packs.map { it.searchScored(raw, near, limit) }, limit)

    override fun place(id: Long): Place? {
        val pack = (id / PACK_ID_STRIDE).toInt()
        return packs.getOrNull(pack)?.place(id % PACK_ID_STRIDE)?.let { it.copy(id = it.id + pack * PACK_ID_STRIDE) }
    }

    override fun landmarks(box: NearbyBox, limit: Int): List<Landmark> =
        landmarksFrom { it.landmarks(box, limit) }

    override fun landmarks(box: NearbyBox, category: String, limit: Int): List<Landmark> =
        landmarksFrom { it.landmarks(box, category, limit) }

    override fun alprCameras(limit: Int): List<Landmark> = landmarksFrom { it.alprCameras(limit) }

    override fun nearby(category: String, lat: Double, lon: Double, radiusMeters: Double, limit: Int): List<SearchResult> =
        packs.flatMapIndexed { i, pack -> pack.nearby(category, lat, lon, radiusMeters, limit).map { moved(it, i) } }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            .let(::distinctPlaces)
            .take(limit)

    override fun close() = packs.forEach { it.close() }

    private fun landmarksFrom(read: (SearchIndex) -> List<Landmark>): List<Landmark> =
        packs.flatMapIndexed { i, pack -> read(pack).map { it.copy(id = it.id + i * PACK_ID_STRIDE) } }

    companion object {
        /** Above every id a pack hands out (Overture places start at 2^42). */
        const val PACK_ID_STRIDE = 1L shl 48

        /** Two results this close with the same name and kind are one place held by two packs. */
        private const val SAME_PLACE_DEGREES = 0.001

        /** Each pack's scored results, best first: ids moved by pack, the same place once, the best [limit]. */
        fun merge(perPack: List<List<ScoredResult>>, limit: Int): List<SearchResult> =
            perPack.flatMapIndexed { i, results -> results.map { it.copy(result = moved(it.result, i)) } }
                .sortedByDescending { it.score }
                .map { it.result }
                .let(::distinctPlaces)
                .take(limit)

        private fun moved(result: SearchResult, pack: Int): SearchResult =
            if (pack == 0) result else result.copy(place = result.place.copy(id = result.place.id + pack * PACK_ID_STRIDE))

        /** Keeps the first of each place the packs share, in order. */
        private fun distinctPlaces(results: List<SearchResult>): List<SearchResult> {
            val kept = ArrayList<SearchResult>()
            for (r in results) {
                val p = r.place
                val dup = kept.any { k ->
                    k.place.kind == p.kind && k.place.name.equals(p.name, ignoreCase = true) &&
                        abs(k.place.lat - p.lat) < SAME_PLACE_DEGREES && abs(k.place.lon - p.lon) < SAME_PLACE_DEGREES
                }
                if (!dup) kept += r
            }
            return kept
        }
    }
}
