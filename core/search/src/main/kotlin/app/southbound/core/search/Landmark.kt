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

        /**
         * A toll gantry or booth ("Toll gantry", "Toll booth"), a toll enforcement relation's device ("Toll"), or
         * where a road joins a toll road with none of those mapped near ("Toll road"). Tolls are cashless, so each
         * one photographs plates: drawn, counted and avoided as a plate reader ([PLATE_READERS]), and avoided with
         * "Avoid tolls" too. Never spoken. Packs from before October 2026 have none.
         */
        const val TOLL = "toll"

        /** The categories that read plates: the map's plate-reader dots, the "plate readers" count and its avoid. */
        val PLATE_READERS = listOf(ALPR_CAMERA, TOLL)

        /**
         * A state DOT traffic camera (511NY's list, read when the pack is built; see
         * pipeline/search/dot_cameras.py). Never spoken and never a landmark in directions:
         * only counted on route options and squared off by "Avoid DOT cameras".
         */
        const val DOT_CAMERA = "dot_camera"
    }
}
