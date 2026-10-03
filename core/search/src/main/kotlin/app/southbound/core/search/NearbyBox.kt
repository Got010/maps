package app.southbound.core.search

import kotlin.math.cos

/** A lat/lon rectangle around a point, for "nearby" lookups with plain comparisons. */
data class NearbyBox(val south: Double, val north: Double, val west: Double, val east: Double) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east

    companion object {
        private const val KM_PER_DEGREE = 111.195

        /** The [places] within [radiusMeters] of [center] (the box is square, the radius is not), nearest first, at most [limit]. */
        fun within(places: List<Place>, center: LatLon, radiusMeters: Double, limit: Int): List<SearchResult> =
            places
                .map { SearchResult(it, SearchRanking.distanceMeters(center, it.lat, it.lon)) }
                .filter { it.distanceMeters!! <= radiusMeters }
                .sortedBy { it.distanceMeters }
                .take(limit)

        fun around(center: LatLon, km: Double): NearbyBox {
            val dLat = km / KM_PER_DEGREE
            val dLon = km / (KM_PER_DEGREE * cos(Math.toRadians(center.lat)).coerceAtLeast(0.01))
            return NearbyBox(center.lat - dLat, center.lat + dLat, center.lon - dLon, center.lon + dLon)
        }
    }
}
