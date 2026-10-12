package app.southbound.core.search

/**
 * Several search packs read as one: New York's (search-nys) and the OSM-only packs of the other states' regions
 * (search-ct, search-pae, ...), one per region a phone has.
 *
 * Results are merged by their scores, which every pack computes the same way ([SearchRanking]), so a name match
 * near the user beats a weaker one far off whichever pack holds it. A place or landmark two packs both hold (the
 * strip where regions meet: a town on the Delaware is in both Pennsylvania's and New Jersey's) comes once; two
 * alike places in one pack stay two ([PackMerge]). Ids are each pack's own; the second pack's and later ones are
 * moved up by [PACK_ID_STRIDE] times their position, so a result or landmark id is unique and [place] knows which
 * pack to ask. The first pack keeps its ids.
 */
class SearchPacks(private val packs: List<SearchIndex>) : PlaceSearch {

    override fun search(raw: String, near: LatLon?, limit: Int): List<SearchResult> =
        merge(packs.map { it.searchScored(raw, near, limit) }, limit)

    override fun place(id: Long): Place? {
        val pack = PackMerge.packOf(id)
        return packs.getOrNull(pack)?.place(id % PACK_ID_STRIDE)?.let { it.copy(id = it.id + pack * PACK_ID_STRIDE) }
    }

    override fun landmarks(box: NearbyBox, limit: Int): List<Landmark> =
        PackMerge.landmarks(packs.map { it.landmarks(box, limit) })

    override fun landmarks(box: NearbyBox, category: String, limit: Int): List<Landmark> =
        PackMerge.landmarks(packs.map { it.landmarks(box, category, limit) })

    override fun alprCameras(limit: Int): List<Landmark> = PackMerge.landmarks(packs.map { it.alprCameras(limit) })

    override fun nearby(category: String, lat: Double, lon: Double, radiusMeters: Double, limit: Int): List<SearchResult> =
        packs.flatMapIndexed { i, pack -> pack.nearby(category, lat, lon, radiusMeters, limit).map { PackMerge.moved(it, i) } }
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            .let(PackMerge::distinctPlaces)
            .take(limit)

    override fun close() = packs.forEach { it.close() }

    companion object {
        /** Above every id a pack hands out (Overture places start at 2^42). */
        const val PACK_ID_STRIDE = PackMerge.PACK_ID_STRIDE

        /** Each pack's scored results, best first: ids moved by pack, the same place once, the best [limit]. */
        fun merge(perPack: List<List<ScoredResult>>, limit: Int): List<SearchResult> = PackMerge.merge(perPack, limit)
    }
}
