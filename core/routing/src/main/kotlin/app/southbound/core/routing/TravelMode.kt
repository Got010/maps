package app.southbound.core.routing

/** How the user is getting there, with the matching Valhalla costing model. */
enum class TravelMode(val valhallaCosting: String) {
    DRIVE("auto"),
    WALK("pedestrian"),
    BIKE("bicycle"),
}
