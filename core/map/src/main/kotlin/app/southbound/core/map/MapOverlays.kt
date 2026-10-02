package app.southbound.core.map

import java.util.Locale

/** A point on the map, in WGS84 degrees. */
data class MapPoint(val lat: Double, val lon: Double)

/** A route drawn on the map; the [selected] one is drawn on top, in colour. */
data class MapLine(val points: List<MapPoint>, val selected: Boolean)

/**
 * What the app draws over the basemap: route lines and pins. Everything is
 * local GeoJSON; nothing is fetched.
 */
data class MapOverlays(
    val routes: List<MapLine> = emptyList(),
    /** The place picked from search or a long press. */
    val pin: MapPoint? = null,
    /** Where a route ends, drawn when it differs from [pin]. */
    val destination: MapPoint? = null,
)

/**
 * A one-off camera move. Each request carries a fresh [serial], so asking to
 * show the same place twice still moves the camera.
 */
sealed interface CameraRequest {
    val serial: Long

    data class Show(val point: MapPoint, val zoom: Double, override val serial: Long = nextSerial()) : CameraRequest

    data class Fit(val points: List<MapPoint>, override val serial: Long = nextSerial()) : CameraRequest

    companion object {
        private var counter = 0L

        @Synchronized
        fun nextSerial(): Long = ++counter
    }
}

/** GeoJSON for the overlay sources, built by hand to keep the dependency surface small. */
internal object OverlayGeoJson {

    fun routes(lines: List<MapLine>): String = featureCollection(
        // Alternatives first so the selected route is drawn last, on top.
        lines.sortedBy { it.selected }.filter { it.points.size >= 2 }.map { line ->
            val coordinates = line.points.joinToString(",") { coordinate(it) }
            """{"type":"Feature","properties":{"selected":${line.selected}},""" +
                """"geometry":{"type":"LineString","coordinates":[$coordinates]}}"""
        },
    )

    fun pins(pin: MapPoint?, destination: MapPoint?): String = featureCollection(
        listOfNotNull(
            destination?.takeIf { it != pin }?.let { point("destination", it) },
            pin?.let { point("pin", it) },
        ),
    )

    private fun point(role: String, p: MapPoint) =
        """{"type":"Feature","properties":{"role":"$role"},"geometry":{"type":"Point","coordinates":${coordinate(p)}}}"""

    private fun coordinate(p: MapPoint) = String.format(Locale.ROOT, "[%.6f,%.6f]", p.lon, p.lat)

    private fun featureCollection(features: List<String>) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}
