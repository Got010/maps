package app.southbound.core.states

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the earth in WGS84 degrees. The module's own type, so it depends on no other module's. */
data class StatePoint(val lat: Double, val lon: Double)

internal object Geo {
    const val METERS_PER_DEGREE = 111_320.0
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Metres from the point (at [lat], [lon]) to the segment from [aLat], [aLon] to [bLat], [bLon], on a flat
     * projection around the point: plenty for the kilometre that matters here.
     */
    fun segmentDistanceMeters(lat: Double, lon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val k = cos(Math.toRadians(lat)) * METERS_PER_DEGREE
        val ax = (aLon - lon) * k
        val ay = (aLat - lat) * METERS_PER_DEGREE
        val dx = (bLon - aLon) * k
        val dy = (bLat - aLat) * METERS_PER_DEGREE
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }
}
