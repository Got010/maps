package app.southbound.core.search

import java.io.Closeable

/**
 * Search, landmarks and nearby places: one search pack ([SearchIndex]) or several merged ([SearchPacks]), for a
 * phone with regions in more than one state.
 */
interface PlaceSearch : Closeable {
    /**
     * Up to [limit] results for [raw], best first. With [near], closer results rank higher and carry their
     * distance. A query that starts with a house number also looks the address up.
     */
    fun search(raw: String, near: LatLon? = null, limit: Int = 20): List<SearchResult>

    /** The full row for a place by the id a result carried, or null (an address result, or no such place). */
    fun place(id: Long): Place?

    /** Landmarks (lights, stop signs, cameras, ...) in [box]. */
    fun landmarks(box: NearbyBox, limit: Int = LANDMARK_LIMIT): List<Landmark>

    /** Landmarks of one [category] in [box]. */
    fun landmarks(box: NearbyBox, category: String, limit: Int = LANDMARK_LIMIT): List<Landmark>

    /** Every licence-plate reader in the pack, for the always-on camera layer. */
    fun alprCameras(limit: Int = ALPR_LIMIT): List<Landmark>

    /** Places of one [category] within [radiusMeters] of ([lat], [lon]), nearest first. */
    fun nearby(category: String, lat: Double, lon: Double, radiusMeters: Double, limit: Int = 5): List<SearchResult>

    companion object {
        /** Per box; a 2 km piece of route in Midtown has a few hundred. */
        const val LANDMARK_LIMIT = 5_000

        /** Plate readers for the whole pack; far more than NYS has mapped today. */
        const val ALPR_LIMIT = 50_000
    }
}
