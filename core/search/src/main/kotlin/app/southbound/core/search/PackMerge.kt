package app.southbound.core.search

import kotlin.math.abs
import kotlin.math.floor

/**
 * How [SearchPacks] reads several packs as one, kept apart from it so it is tested without SQLite: each pack's
 * ids moved up by [PACK_ID_STRIDE] times its position, and what two packs both hold (the strip where regions
 * meet) kept once.
 *
 * Only copies from different packs are merged. Two places one pack holds are two places, however alike: two
 * Starbucks a block apart, or two cameras on one pole. Each kept result also stands in for at most one copy from
 * each other pack, so when both packs hold both of those Starbucks, both still come once each.
 */
internal object PackMerge {
    /** Above every id a pack hands out (Overture places start at 2^42). */
    const val PACK_ID_STRIDE = 1L shl 48

    /** Two results this close with the same name and kind are one place held by two packs. */
    private const val SAME_PLACE_DEGREES = 0.001

    /** Two landmarks of one category this close are one landmark held by two packs (the same OSM node). */
    const val SAME_LANDMARK_M = 5.0

    /** Grid cells for finding landmark copies: at least [SAME_LANDMARK_M] across up to 60 degrees north. */
    private const val CELL_DEGREES = 1e-4

    /** The position of the pack an id came from. */
    fun packOf(id: Long): Int = (id / PACK_ID_STRIDE).toInt()

    /** Each pack's scored results, best first: ids moved by pack, the same place once, the best [limit]. */
    fun merge(perPack: List<List<ScoredResult>>, limit: Int): List<SearchResult> =
        perPack.flatMapIndexed { i, results -> results.map { it.copy(result = moved(it.result, i)) } }
            .sortedByDescending { it.score }
            .map { it.result }
            .let(::distinctPlaces)
            .take(limit)

    fun moved(result: SearchResult, pack: Int): SearchResult =
        if (pack == 0) result else result.copy(place = result.place.copy(id = result.place.id + pack * PACK_ID_STRIDE))

    /** Keeps the first of each place the packs share, in order; [results] carry moved ids. */
    fun distinctPlaces(results: List<SearchResult>): List<SearchResult> {
        val kept = ArrayList<SearchResult>()
        // Per kept result, the packs whose copy of it was dropped.
        val absorbed = ArrayList<MutableSet<Int>>()
        for (r in results) {
            val p = r.place
            val pack = packOf(p.id)
            val twin = kept.indices.filter { i ->
                val k = kept[i].place
                packOf(k.id) != pack && pack !in absorbed[i] &&
                    k.kind == p.kind && k.name.equals(p.name, ignoreCase = true) &&
                    abs(k.lat - p.lat) < SAME_PLACE_DEGREES && abs(k.lon - p.lon) < SAME_PLACE_DEGREES
            }.minByOrNull { i -> RouteLine.distanceMeters(LatLon(kept[i].place.lat, kept[i].place.lon), LatLon(p.lat, p.lon)) }
            if (twin != null) {
                absorbed[twin].add(pack)
            } else {
                kept += r
                absorbed.add(HashSet())
            }
        }
        return kept
    }

    /**
     * Each pack's landmarks with ids moved by pack, and a landmark two packs both hold once: the earlier pack's
     * copy. A copy is a landmark of the same category within [SAME_LANDMARK_M] in another pack.
     */
    fun landmarks(perPack: List<List<Landmark>>): List<Landmark> {
        val kept = ArrayList<Landmark>()
        // Per kept landmark, the packs whose copy of it was dropped.
        val absorbed = ArrayList<MutableSet<Int>>()
        // Indices into kept by grid cell, so each landmark is checked only against its neighbours.
        val cells = HashMap<Long, MutableList<Int>>()
        perPack.forEachIndexed { pack, marks ->
            for (mark in marks) {
                val x = floor(mark.lon / CELL_DEGREES).toLong()
                val y = floor(mark.lat / CELL_DEGREES).toLong()
                var twin = -1
                var twinMeters = Double.MAX_VALUE
                if (pack > 0) {
                    for (dy in -1L..1L) for (dx in -1L..1L) {
                        for (i in cells[cell(x + dx, y + dy)].orEmpty()) {
                            val k = kept[i]
                            if (packOf(k.id) == pack || pack in absorbed[i] || k.category != mark.category) continue
                            val meters = RouteLine.distanceMeters(k.point, mark.point)
                            if (meters <= SAME_LANDMARK_M && meters < twinMeters) {
                                twin = i
                                twinMeters = meters
                            }
                        }
                    }
                }
                if (twin >= 0) {
                    absorbed[twin].add(pack)
                } else {
                    cells.getOrPut(cell(x, y)) { ArrayList() }.add(kept.size)
                    kept += mark.copy(id = mark.id + pack * PACK_ID_STRIDE)
                    absorbed.add(HashSet())
                }
            }
        }
        return kept
    }

    private fun cell(x: Long, y: Long): Long = (y shl 32) xor (x and 0xFFFF_FFFFL)
}
