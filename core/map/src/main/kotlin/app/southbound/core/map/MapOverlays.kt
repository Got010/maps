package app.southbound.core.map

import app.southbound.core.network.MiniJson
import java.util.Locale

/** A point on the map, in WGS84 degrees. */
data class MapPoint(val lat: Double, val lon: Double)

/**
 * A route drawn on the map. [rank] is the route's place among the
 * alternatives (0 = fastest) and picks its colour from [RouteColors].
 */
data class MapLine(val points: List<MapPoint>, val role: Role, val rank: Int = 0) {
    enum class Role {
        /** The chosen route: a thick line in its colour over a casing. */
        LEADER,

        /** Another alternative: a thin line in its colour, drawn over the leader. */
        THREAD,

        /** A transit walk leg: thin grey dashes. */
        WALK,
    }
}

/** A road segment coloured by its live speed. */
data class SpeedLine(val points: List<MapPoint>, val speedMph: Double)

/** An incident pin; [kind] is the IncidentKind name, [id] comes back from a tap. */
data class IncidentPin(val id: String, val point: MapPoint, val kind: String, val closure: Boolean)

/**
 * A licence-plate reader (Flock and others) from the search pack, drawn at
 * all times. [id] comes back from a tap; [name] is who makes or runs it,
 * when mapped.
 */
data class CameraPin(val id: String, val point: MapPoint, val name: String? = null)

/**
 * A state 511 traffic camera, drawn with the traffic layer. [id] comes back from
 * a tap; [offline] cameras are drawn grey.
 */
data class TrafficCameraPin(val id: String, val point: MapPoint, val offline: Boolean = false)

/** A state 511 variable message sign, drawn with the traffic layer. [id] comes back from a tap. */
data class MessageSignPin(val id: String, val point: MapPoint)

/**
 * A weather alert's area, drawn tinted by [severity] (the alert's AlertSeverity name). [id]
 * comes back from a tap. Each ring is an outer ring of the area.
 */
data class WeatherArea(val id: String, val rings: List<List<MapPoint>>, val severity: String)

/**
 * A radar picture to draw between [west], [south], [east] and [north] (it is in Web Mercator,
 * so drawing it between its corners lines it up). [png] is the image; [fetchedAt] tells one
 * picture from the next.
 */
class RadarPicture(val png: ByteArray, val fetchedAt: Long, val west: Double, val south: Double, val east: Double, val north: Double)

/**
 * A weather station's current conditions on the map: a badge with the icon for [sky] (core/weather's Sky name)
 * and [tempF]. [id] comes back from a tap.
 */
data class WeatherStationPin(val id: String, val point: MapPoint, val sky: String, val night: Boolean, val tempF: Int?) {
    /** The map image for this badge, so each look is drawn and added to the style once. */
    val imageName: String
        get() = "southbound-wx-$sky-${if (night) "n" else "d"}-${tempF ?: "x"}"
}

/** The weather layer: radar under everything, alert areas over it, and each station's temperature on top. */
data class WeatherOverlay(
    val radar: RadarPicture? = null,
    val areas: List<WeatherArea> = emptyList(),
    val stations: List<WeatherStationPin> = emptyList(),
) {
    companion object {
        val NONE = WeatherOverlay()
    }
}

/** The live traffic layer: speeds under the routes, incidents over them. */
data class TrafficOverlay(
    val speeds: List<SpeedLine> = emptyList(),
    val incidents: List<IncidentPin> = emptyList(),
) {
    companion object {
        val NONE = TrafficOverlay()
    }
}

/**
 * What the app draws over the basemap: route lines, pins and, when turned
 * on, live traffic. Everything is local GeoJSON once fetched; the map itself
 * requests nothing.
 */
data class MapOverlays(
    val routes: List<MapLine> = emptyList(),
    /** The place picked from search or a long press. */
    val pin: MapPoint? = null,
    /** Where a route ends, drawn when it differs from [pin]. */
    val destination: MapPoint? = null,
    val traffic: TrafficOverlay = TrafficOverlay.NONE,
    /** Stretches of the selected route coloured by live speed; drawn on top of the route line. */
    val slowdowns: List<SpeedLine> = emptyList(),
    /** Plate readers, shown in every state (browsing, planning, driving) from zoom 11. */
    val cameras: List<CameraPin> = emptyList(),
    /** 511NY traffic cameras (tap for the still), with the traffic layer, from zoom 12. */
    val trafficCameras: List<TrafficCameraPin> = emptyList(),
    /** 511NY message signs (tap for what they say), with the traffic layer, from zoom 11. */
    val messageSigns: List<MessageSignPin> = emptyList(),
    /** Radar and weather alert areas, under the routes. */
    val weather: WeatherOverlay = WeatherOverlay.NONE,
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

/**
 * GeoJSON for the overlay sources, built by hand to keep the dependency surface small. A feature with a coordinate
 * that isn't a number (NaN or infinite) is left out: it would make the whole collection unreadable, and the map would
 * keep drawing the layer's old data.
 */
internal object OverlayGeoJson {

    /**
     * Each line carries its rank, role and colours, so the layers only filter
     * on role and read the colour: no theme logic in the style. Within a role,
     * higher ranks come first so the faster routes draw on top.
     */
    fun routes(lines: List<MapLine>, darkTheme: Boolean): String = featureCollection(
        lines.filter { it.points.size >= 2 && it.points.all { p -> p.isFinite } }
            .sortedWith(compareBy<MapLine>({ it.role.ordinal }, { -it.rank }))
            .map { line ->
                val coordinates = line.points.joinToString(",") { coordinate(it) }
                val color = if (line.role == MapLine.Role.WALK) RouteColors.WALK else RouteColors.hex(line.rank, darkTheme)
                val role = line.role.name.lowercase(Locale.ROOT)
                """{"type":"Feature","properties":{"rank":${line.rank},"role":"$role","color":"$color",""" +
                    """"casing":"${RouteColors.casingHex(darkTheme)}"},""" +
                    """"geometry":{"type":"LineString","coordinates":[$coordinates]}}"""
            },
    )

    fun pins(pin: MapPoint?, destination: MapPoint?): String = featureCollection(
        listOfNotNull(
            destination?.takeIf { it != pin && it.isFinite }?.let { point("destination", it) },
            pin?.takeIf { it.isFinite }?.let { point("pin", it) },
        ),
    )

    fun speeds(lines: List<SpeedLine>): String = featureCollection(
        lines.filter { it.points.size >= 2 && it.speedMph.isFinite() && it.points.all { p -> p.isFinite } }.map { line ->
            val coordinates = line.points.joinToString(",") { coordinate(it) }
            String.format(Locale.ROOT, """{"type":"Feature","properties":{"speed":%.1f},""", line.speedMph) +
                """"geometry":{"type":"LineString","coordinates":[$coordinates]}}"""
        },
    )

    fun incidents(pins: List<IncidentPin>): String = featureCollection(
        // Closures last, so they draw on top of lesser incidents.
        pins.filter { it.point.isFinite }.sortedBy { it.closure }.map { pin ->
            """{"type":"Feature","properties":{"id":${quote(pin.id)},"kind":${quote(pin.kind)},"closure":${pin.closure}},""" +
                """"geometry":{"type":"Point","coordinates":${coordinate(pin.point)}}}"""
        },
    )

    fun cameras(pins: List<CameraPin>): String = featureCollection(
        pins.filter { it.point.isFinite }.map { pin ->
            val name = pin.name?.let { ""","name":${quote(it)}""" } ?: ""
            """{"type":"Feature","properties":{"id":${quote(pin.id)}$name},""" +
                """"geometry":{"type":"Point","coordinates":${coordinate(pin.point)}}}"""
        },
    )

    fun trafficCameras(pins: List<TrafficCameraPin>): String = featureCollection(
        // Live cameras last, so they draw over offline ones.
        pins.filter { it.point.isFinite }.sortedBy { !it.offline }.map { pin ->
            """{"type":"Feature","properties":{"id":${quote(pin.id)},"kind":"camera","offline":${pin.offline}},""" +
                """"geometry":{"type":"Point","coordinates":${coordinate(pin.point)}}}"""
        },
    )

    fun messageSigns(pins: List<MessageSignPin>): String = featureCollection(
        pins.filter { it.point.isFinite }.map { pin ->
            """{"type":"Feature","properties":{"id":${quote(pin.id)},"kind":"sign"},""" +
                """"geometry":{"type":"Point","coordinates":${coordinate(pin.point)}}}"""
        },
    )

    fun weatherAreas(areas: List<WeatherArea>): String = featureCollection(
        // The least severe first, so the worst alert draws on top.
        areas.sortedByDescending { SEVERITY_ORDER.indexOf(it.severity) }.flatMap { area ->
            area.rings.filter { it.size >= 3 && it.all { p -> p.isFinite } }.map { ring ->
                val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
                """{"type":"Feature","properties":{"id":${quote(area.id)},"severity":${quote(area.severity)}},""" +
                    """"geometry":{"type":"Polygon","coordinates":[[${closed.joinToString(",") { coordinate(it) }}]]}}"""
            }
        },
    )

    fun weatherStations(pins: List<WeatherStationPin>): String = featureCollection(
        pins.filter { it.point.isFinite }.map { pin ->
            """{"type":"Feature","properties":{"id":${quote(pin.id)},"kind":"weather","icon":${quote(pin.imageName)}},""" +
                """"geometry":{"type":"Point","coordinates":${coordinate(pin.point)}}}"""
        },
    )

    private val SEVERITY_ORDER = listOf("EXTREME", "SEVERE", "MODERATE", "MINOR", "UNKNOWN")

    // Every control character escaped as JSON asks: a CR or a TAB in an OSM operator's name would otherwise make the
    // whole collection unreadable.
    private fun quote(s: String): String = MiniJson.stringify(s)

    private val MapPoint.isFinite: Boolean
        get() = lat.isFinite() && lon.isFinite()

    private fun point(role: String, p: MapPoint) =
        """{"type":"Feature","properties":{"role":"$role"},"geometry":{"type":"Point","coordinates":${coordinate(p)}}}"""

    // Only for finite points: %.6f writes NaN, which isn't JSON.
    private fun coordinate(p: MapPoint) = String.format(Locale.ROOT, "[%.6f,%.6f]", p.lon, p.lat)

    private fun featureCollection(features: List<String>) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}
