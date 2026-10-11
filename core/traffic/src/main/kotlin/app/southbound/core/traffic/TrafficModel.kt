package app.southbound.core.traffic

import app.southbound.core.network.NetworkFeature
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** A point in WGS84 degrees. */
data class GeoPoint(val lat: Double, val lon: Double)

enum class IncidentKind {
    /** A road or ramp is shut: routing steers around it. */
    CLOSURE,
    ACCIDENT,
    /** Police activity, a traffic stop or enforcement; a police event that shuts the road is a CLOSURE instead. */
    POLICE,
    ROADWORK,
    EVENT,
    OTHER,
}

/**
 * Whose feed a traffic item comes from: each state's 511, behind its own toggle ([feature]). [label] names it on
 * cards ("511PA traffic camera"); [idPrefix] keeps ids from two feeds apart (511NY's stay as they were).
 */
enum class TrafficSource(val label: String, val feature: NetworkFeature, val idPrefix: String) {
    NY_511("511NY", NetworkFeature.TRAFFIC_511NY, ""),
    PA_511("511PA", NetworkFeature.TRAFFIC_511PA, "pa:"),
    CT_ROADS("CTroads", NetworkFeature.TRAFFIC_CTROADS, "ct:"),
    MD_CHART("CHART", NetworkFeature.TRAFFIC_CHART_MD, "md:"),
    DE_DELDOT("DelDOT", NetworkFeature.TRAFFIC_DELDOT, "de:"),
    WI_511("511WI", NetworkFeature.TRAFFIC_511WI, "wi:"),
    IL_TRAVEL_MIDWEST("Travel Midwest", NetworkFeature.TRAFFIC_TRAVEL_MIDWEST, "il:"),
    ND_DOT("NDDOT", NetworkFeature.TRAFFIC_NDDOT, "nd:"),
    SD_511("SD511", NetworkFeature.TRAFFIC_SD511, "sd:"),
    IN_511("511IN", NetworkFeature.TRAFFIC_511IN, "in:"),
    MN_511("511MN", NetworkFeature.TRAFFIC_511MN, "mn:"),
    IA_511("511IA", NetworkFeature.TRAFFIC_511IA, "ia:"),
    NE_511("Nebraska 511", NetworkFeature.TRAFFIC_511NE, "ne:"),
    KS_KANDRIVE("KanDrive", NetworkFeature.TRAFFIC_KANDRIVE, "ks:"),
    MI_DRIVE("Mi Drive", NetworkFeature.TRAFFIC_MIDRIVE, "mi:"),
    OH_OHGO("OHGO", NetworkFeature.TRAFFIC_OHGO, "oh:"),
}

/** One state 511 event: a crash, closure, work zone or special event, at a point. */
data class Incident(
    val id: String,
    val kind: IncidentKind,
    /** "I-87", "Broadway". */
    val roadway: String?,
    /** "Northbound", "Both Directions". */
    val direction: String?,
    val description: String,
    val location: GeoPoint,
    /** "Minor", "Major" or whatever the feed says; null when it doesn't. */
    val severity: String?,
    /** "All lanes closed", "1 right lane closed". */
    val lanesStatus: String?,
    /** POSIX millis of the feed's last update to this event, or null. */
    val updatedAt: Long?,
    val source: TrafficSource = TrafficSource.NY_511,
) {
    /** A closure proper, or any event that shuts every lane. */
    val isClosure: Boolean
        get() = kind == IncidentKind.CLOSURE || lanesStatus?.lowercase()?.let { "all lanes" in it && "closed" in it } == true

    /** "I-87 Northbound" or the description when the feed gives no road. */
    val title: String
        get() = listOfNotNull(roadway?.takeIf { it.isNotBlank() }, direction?.takeIf { it.isNotBlank() }).joinToString(" ")
            .ifEmpty { description.take(60) }
}

/** One NYC DOT sensor link with its latest reading. */
data class SpeedLink(
    val id: String,
    val name: String?,
    val borough: String?,
    val speedMph: Double,
    /** Travel time over the link, seconds; null when unknown. */
    val travelTimeSeconds: Int?,
    /** The link's shape. */
    val points: List<GeoPoint>,
    /** POSIX millis of the reading, or null. */
    val asOf: Long?,
) {
    val isStale: Boolean get() = asOf == null
}

/**
 * A state 511 traffic camera. [imageUrl] is its still (a JPEG the 511 refreshes
 * every minute or two); it names the camera, never the viewer. A [disabled]
 * camera is still listed, but its image is a placeholder or missing.
 */
data class TrafficCamera(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val imageUrl: String?,
    /** "I-87", "Broadway". */
    val roadway: String?,
    /** "Northbound", "Both Directions". */
    val direction: String?,
    /** Turned off or blocked by its operator; no live image. */
    val disabled: Boolean,
    val source: TrafficSource = TrafficSource.NY_511,
    /**
     * Its live video's HLS playlist, for a camera that has no still (CHART's and DelDOT's): the card shows a frame of
     * the newest segment instead ([Hls]).
     */
    val videoUrl: String? = null,
    /**
     * A JSON page naming the still, for a camera whose list gives none (Mi Drive's, [MiDrive.cameraInfoUrl]): the card
     * fetches it first and takes the link [MiDrive.stillOf] allows.
     */
    val stillVia: String? = null,
) {
    val location: GeoPoint get() = GeoPoint(lat, lon)
    val hasImage: Boolean get() = !disabled && (!imageUrl.isNullOrBlank() || !videoUrl.isNullOrBlank() || !stillVia.isNullOrBlank())
}

/**
 * A state 511 variable message sign by the road. [lines] is what it shows right
 * now, top to bottom; an empty string marks a page break between the pages
 * a sign flips through. Blank when the sign shows nothing.
 */
data class MessageSign(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lines: List<String>,
    val roadway: String?,
    val direction: String?,
    /** POSIX millis of the feed's last update to the message, or null. */
    val updatedAt: Long? = null,
    val source: TrafficSource = TrafficSource.NY_511,
) {
    val location: GeoPoint get() = GeoPoint(lat, lon)
    val isBlank: Boolean get() = lines.all { it.isBlank() }
}

/** Everything traffic knows right now, from the feeds the user turned on. */
data class TrafficSnapshot(
    val incidents: List<Incident> = emptyList(),
    val speeds: List<SpeedLink> = emptyList(),
    /** The state 511s' traffic cameras, each state whole; refreshed daily. */
    val cameras: List<TrafficCamera> = emptyList(),
    /** The state 511s' message signs and what they say; refreshed every few minutes. */
    val signs: List<MessageSign> = emptyList(),
    /** POSIX millis. */
    val fetchedAt: Long = 0,
    /** Feeds that couldn't be fetched, in plain words. */
    val errors: List<String> = emptyList(),
    /** NYC DOT answered, but every reading was older than [NycDotSpeeds.DEFAULT_MAX_AGE_MILLIS]. */
    val speedsStale: Boolean = false,
) {
    val isEmpty: Boolean get() = incidents.isEmpty() && speeds.isEmpty()
    val closures: List<Incident> get() = incidents.filter { it.isClosure }

    companion object {
        val EMPTY = TrafficSnapshot()
    }
}

/** Small-area distances, good to a fraction of a percent over a state. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = Math.PI / 180

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * DEG
        val dLon = (b.lon - a.lon) * DEG * cos((a.lat + b.lat) / 2 * DEG)
        return sqrt(dLat * dLat + dLon * dLon) * EARTH_RADIUS_M
    }

    /** Metres from [p] to the segment [a]-[b]. */
    fun distanceToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = cos(p.lat * DEG)
        val ax = a.lon * k; val ay = a.lat
        val bx = b.lon * k; val by = b.lat
        val px = p.lon * k; val py = p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        val ddx = (px - cx) * DEG; val ddy = (py - cy) * DEG
        return sqrt(ddx * ddx + ddy * ddy) * EARTH_RADIUS_M
    }

    /** Metres from [p] to the polyline [line]; infinity for an empty line. */
    fun distanceToLineMeters(p: GeoPoint, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return distanceMeters(p, line[0])
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.size - 1) {
            // Skip segments whose box is far away; cheap, and routes have thousands of points.
            val a = line[i]; val b = line[i + 1]
            if (abs(p.lat - a.lat) > 0.05 && abs(p.lat - b.lat) > 0.05) continue
            best = minOf(best, distanceToSegmentMeters(p, a, b))
        }
        return best
    }
}
