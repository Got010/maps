package app.southbound.core.search

/**
 * A point that guidance talks about, from the search pack's `landmark`
 * table: a traffic signal, a stop sign, a roadside place, or something to
 * warn about. [category] is one of the constants below; [name] is set for
 * places (brand or name, e.g. "Shell", "Walgreens") and, in newer packs,
 * for plate readers (who makes or runs them, e.g. "Flock Safety"). [direction]
 * is, for plate readers and speed cameras in packs that have the column, the
 * way the camera faces in degrees clockwise from north (0-359); null when
 * the pack has no such column or OSM doesn't say (see [RouteAlerts.faces]).
 */
data class Landmark(
    val id: Long,
    val category: String,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val direction: Int? = null,
) {
    val point: LatLon get() = LatLon(lat, lon)

    companion object {
        const val TRAFFIC_SIGNALS = "traffic_signals"
        const val STOP = "stop"
        const val FUEL = "fuel"
        const val FAST_FOOD = "fast_food"
        const val BANK = "bank"
        const val PHARMACY = "pharmacy"
        const val SUPERMARKET = "supermarket"
        const val CHURCH = "church"
        const val SCHOOL = "school"
        const val SPEED_CAMERA = "speed_camera"
        const val SCHOOL_ZONE = "school_zone"
        const val ALPR_CAMERA = "alpr_camera"
    }
}
