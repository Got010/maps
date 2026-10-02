package app.southbound.core.search

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A candidate row read from the search pack, before ranking. */
data class Candidate(
    val place: Place,
    val importance: Int,
    val nameKey: String,
    val searchText: String,
)

data class SearchResult(val place: Place, val distanceMeters: Double?)

/**
 * Orders candidates the way people expect: a name match beats an address or
 * category match, closer beats farther, and notable places (boroughs,
 * stations, landmarks) float up.
 */
object SearchRanking {

    /** Score lost per kilometre from the user. */
    const val POINTS_PER_KM = 8.0

    fun rank(query: SearchQuery, candidates: List<Candidate>, near: LatLon?, limit: Int): List<SearchResult> =
        candidates
            .asSequence()
            .filter { query.matchesText(it.searchText) }
            .map { candidate ->
                val distance = near?.let { distanceMeters(it, candidate.place.lat, candidate.place.lon) }
                val score = textScore(query, candidate) + candidate.importance -
                    (distance ?: 0.0) / 1000.0 * POINTS_PER_KM
                Triple(candidate, distance, score)
            }
            .sortedByDescending { it.third }
            .distinctBy { (candidate, _, _) -> candidate.place.id }
            .take(limit)
            .map { (candidate, distance, _) -> SearchResult(candidate.place, distance) }
            .toList()

    internal fun textScore(query: SearchQuery, candidate: Candidate): Double {
        val name = candidate.nameKey
        val q = query.normalized
        val nameWords = " $name"
        return when {
            name == q -> 60.0
            name.startsWith(q) -> 40.0
            query.tokens.all { nameWords.contains(" $it") } -> 25.0
            else -> 0.0
        }
    }

    /** Great-circle distance; accurate to well under a metre at city scale. */
    fun distanceMeters(from: LatLon, lat: Double, lon: Double): Double {
        val dLat = Math.toRadians(lat - from.lat)
        val dLon = Math.toRadians(lon - from.lon)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(from.lat)) * cos(Math.toRadians(lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private const val EARTH_RADIUS_M = 6_371_008.8
}
