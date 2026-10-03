package app.southbound.core.search

import kotlin.math.cos

/** A lat/lon rectangle around a point, for "nearby" lookups with plain comparisons. */
data class NearbyBox(val south: Double, val north: Double, val west: Double, val east: Double) {
    fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east

    companion object {
        private const val KM_PER_DEGREE = 111.195

        fun around(center: LatLon, km: Double): NearbyBox {
            val dLat = km / KM_PER_DEGREE
            val dLon = km / (KM_PER_DEGREE * cos(Math.toRadians(center.lat)).coerceAtLeast(0.01))
            return NearbyBox(center.lat - dLat, center.lat + dLat, center.lon - dLon, center.lon + dLon)
        }
    }
}
