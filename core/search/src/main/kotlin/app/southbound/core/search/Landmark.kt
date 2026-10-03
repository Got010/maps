package app.southbound.core.search

/**
 * A point that guidance talks about, from the search pack's `landmark`
 * table: a traffic signal, a stop sign, a roadside place, or something to
 * warn about. [category] is one of the constants below; [name] is set only
 * for places (brand or name, e.g. "Shell", "Walgreens").
 */
data class Landmark(
    val id: Long,
    val category: String,
    val name: String?,
    val lat: Double,
    val lon: Double,
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
