package app.southbound.feature.explore

/** One row of the Map options list. The enum's order is the list's order, top to bottom. */
internal enum class MapOption { RECENTER, TRAFFIC, WEATHER, WIFI, ROAD, THIS_STATE, TRIP_LOG }

/** The colour of the small dot on the Map options button. */
internal enum class MapOptionsDot {
    /** No layer is showing. */
    NONE,

    /** A layer is showing: the primary colour. */
    LAYER,

    /** The Wi-Fi layer is showing with a type filter active: the Wi-Fi gold. */
    WIFI_FILTERED,
}

/**
 * What the Map options button and its list show, decided from the screen's own state and nothing else. A row is shown
 * under the condition the floating button it replaced had: a fix for Re-center, the Live-data toggle for Traffic and
 * Weather, the installed pack for Free Wi-Fi and for Road. "Trip log" is the last row, whenever the caller offers it (the
 * map screen does, outside guidance: the log never opens during a trip).
 */
internal data class MapOptionsState(
    val hasLocation: Boolean,
    /** The map follows the puck; false once it has been panned, rotated or tilted away. */
    val following: Boolean,
    val trafficAvailable: Boolean,
    val trafficVisible: Boolean,
    val weatherAvailable: Boolean,
    val weatherVisible: Boolean,
    val wifiAvailable: Boolean,
    val wifiVisible: Boolean,
    /** A Wi-Fi type filter other than the default is set. */
    val wifiFiltered: Boolean,
    /** State-line cards are on and the state the fix is in is known: the "This state" row opens its card. */
    val stateAvailable: Boolean = false,
    val roadAvailable: Boolean = false,
    val roadVisible: Boolean = false,
    /** A Road kinds choice other than the default is set. */
    val roadFiltered: Boolean = false,
    /** The trip log can be opened (not during guidance). It is no layer: it never lights the button's dot. */
    val tripLogAvailable: Boolean = false,
) {
    /** The rows to show, top to bottom. */
    val rows: List<MapOption> = buildList {
        if (hasLocation) add(MapOption.RECENTER)
        if (trafficAvailable) add(MapOption.TRAFFIC)
        if (weatherAvailable) add(MapOption.WEATHER)
        if (wifiAvailable) add(MapOption.WIFI)
        if (roadAvailable) add(MapOption.ROAD)
        if (stateAvailable) add(MapOption.THIS_STATE)
        if (tripLogAvailable) add(MapOption.TRIP_LOG)
    }

    /** True when the Re-center row says "Re-center" (the map is away from the puck) rather than "My location". */
    val panned: Boolean get() = !following

    /** A layer the user can see on the map right now; a layer whose row is missing does not count. */
    val anyLayerVisible: Boolean
        get() = (trafficAvailable && trafficVisible) || (weatherAvailable && weatherVisible) || (wifiAvailable && wifiVisible) || (roadAvailable && roadVisible)

    /** The Wi-Fi row's filter dot: only with the pack installed. The button's dot is gold only while the layer shows too. */
    val wifiRowFiltered: Boolean get() = wifiAvailable && wifiFiltered

    /** The Road row's filter dot: only with the pack installed. It does not colour the button's dot (that stays the primary colour). */
    val roadRowFiltered: Boolean get() = roadAvailable && roadFiltered

    val dot: MapOptionsDot
        get() = when {
            !anyLayerVisible -> MapOptionsDot.NONE
            wifiVisible && wifiRowFiltered -> MapOptionsDot.WIFI_FILTERED
            else -> MapOptionsDot.LAYER
        }
}
