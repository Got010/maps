package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * 511 Virginia (VDOT), from the GeoJSON layers its own map reads, `/services/511/map/layers/map/<layer>`, each the
 * whole state, no key and nothing about the user in the request:
 *
 * - the events, one layer each ([EVENT_LAYERS]): other and major incidents (crashes, disabled vehicles, fires,
 *   congestion), active work zones, weather closures and other weather events, special events and security
 *   activity. Each is a point with `route` ("I-81N", "Rt. 801N (Bedford County)"), `type`, `priority` and VDOT's
 *   own sentence, `message_511`, which says which lanes are shut ("All north lanes are closed. All south lanes are
 *   closed."); the work zones and special events also carry `start` and `end` (POSIX seconds);
 * - the message signs (`dms`, the active ones: 298 that night), each one text;
 * - the cameras (`cams`, 1,713), each with a PNG still on VDOT's snapshot server and its HLS stream.
 *
 * Events at the same point come as one feature whose `items` hold each one's properties. Seen in the feed probe on
 * 2026-10-13 and 14 (scripts/feed-probe.py, rounds `dot-south-*`). VDOT's Waze layers are not read.
 */
object Virginia511 {
    private const val BASE = "https://511.vdot.virginia.gov/services/511/map/layers/map"

    /** The event layers the map's Travel Alerts list, by identifier. */
    val EVENT_LAYERS: List<String> = listOf("incidents", "hpIncidents", "acon", "weatherClosures", "otherWeather", "sEvents", "sActivity")

    fun layerUrl(layer: String): String = "$BASE/$layer"

    const val SIGNS_URL: String = "$BASE/dms"
    const val CAMERAS_URL: String = "$BASE/cams"

    /** VDOT's stills (https://snapshot.vdotcameras.com/thumbs/<camera>.flv.png); anything else is not followed. */
    private const val STILL_HOST = "snapshot.vdotcameras.com"

    /** VDOT's video servers (media-sfs1 to 9); a camera without a still plays a frame of its stream ([Hls]). */
    const val VIDEO_HOST_SUFFIX: String = ".vdotcameras.com"

    /**
     * One event layer's events in effect at [now] (epoch ms): one whose `end` has passed or whose `start` is still
     * ahead (a parade tomorrow) is left out.
     *
     * @throws IllegalArgumentException when it isn't the layer's GeoJSON.
     */
    fun parseEvents(layer: String, json: String, now: Long = System.currentTimeMillis()): List<Incident> =
        features(json).mapNotNull { event(layer, it, now) }

    /** The message signs and what they show; a sign with no text is blank. */
    fun parseSigns(json: String): List<MessageSign> = features(json).mapNotNull(::sign)

    /** The cameras, each with its still (or, without one, its stream). */
    fun parseCameras(json: String): List<TrafficCamera> = features(json).mapNotNull(::camera)

    private class Feature(val id: String?, val properties: Map<*, *>, val at: GeoPoint?)

    private fun features(json: String): List<Feature> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        } as? Map<*, *>
        val list = root?.get("features") as? List<*> ?: throw IllegalArgumentException("sent no features")
        return list.flatMap { f ->
            val m = f as? Map<*, *> ?: return@flatMap emptyList()
            val props = m["properties"] as? Map<*, *> ?: return@flatMap emptyList()
            val at = pointOf(m["geometry"] as? Map<*, *>)
            // Events at the same point come as one feature with their properties in `items`.
            val items = (props["items"] as? List<*>)?.mapNotNull { it as? Map<*, *> }
            if (items != null) {
                items.map { Feature(it.text("id"), it, at) }
            } else {
                listOf(Feature(m.text("id") ?: props.text("id"), props, at))
            }
        }
    }

    private fun event(layer: String, f: Feature, now: Long): Incident? {
        val id = f.id ?: return null
        val at = f.at ?: return null
        val p = f.properties
        val start = p.number("start")?.let { (it * 1000).toLong() }
        val end = p.number("end")?.let { (it * 1000).toLong() }
        if ((start != null && start > now) || (end != null && end in 1..now)) return null
        val message = p.text("message_511")?.replace(SPACES, " ")
        val type = p.text("type")
        val route = p.text("route")
        val shut = closedDirections(message.orEmpty())
        val kind = kindOf(layer, type, message.orEmpty(), shut)
        val routeDirection = route?.let(::directionOfRoute)
        return Incident(
            id = TrafficSource.VA_511.idPrefix + id,
            kind = kind,
            roadway = route?.let(::roadOf),
            direction = when {
                shut.size >= 2 -> "Both Directions"
                shut.size == 1 -> shut.first()
                // A weather closure's route names VDOT's inventory direction, not which way is shut.
                kind == IncidentKind.CLOSURE -> null
                else -> routeDirection
            },
            description = message ?: listOfNotNull(type, route, p.text("location") ?: p.text("location_description")).joinToString(" · "),
            location = at,
            severity = p.text("priority")?.takeUnless { it.equals("none", ignoreCase = true) },
            lanesStatus = if (kind == IncidentKind.CLOSURE) "All lanes closed" else message?.let(::lanesIn),
            updatedAt = p.number("update")?.let { (it * 1000).toLong() }?.takeIf { it > 0 },
            source = TrafficSource.VA_511,
        )
    }

    /**
     * A closure when every lane one way or both is shut ("All north lanes are closed", "The south through lanes are
     * closed", "the road is closed") or VDOT files it as a weather closure of type "Closed"; else crashes, police,
     * road work and events by layer and type; congestion, debris and weather are other.
     */
    fun kindOf(layer: String, type: String?, message: String, shut: Set<String> = closedDirections(message)): IncidentKind {
        val t = type.orEmpty().lowercase(Locale.US)
        val m = message.lowercase(Locale.US)
        return when {
            shut.isNotEmpty() || ROAD_CLOSED.containsMatchIn(m) -> IncidentKind.CLOSURE
            layer == "weatherClosures" && t == "closed" -> IncidentKind.CLOSURE
            "accident" in t || "crash" in t || "fire" in t || "disabled" in t || "overturn" in t -> IncidentKind.ACCIDENT
            "police" in t || "police" in m || layer == "sActivity" && ("security" in t || "law enforcement" in m) -> IncidentKind.POLICE
            layer == "acon" || "construction" in t || "maintenance" in t || "work" in t -> IncidentKind.ROADWORK
            layer == "sEvents" -> IncidentKind.EVENT
            else -> IncidentKind.OTHER
        }
    }

    private val ALL_ONE_WAY = Regex("""\ball (north|south|east|west)(?:bound)? lanes are closed\b""")
    private val THROUGH_ONE_WAY = Regex("""\bthe (north|south|east|west)(?:bound)? through lanes are closed\b""")
    private val ROAD_CLOSED = Regex("""\ball lanes are closed\b|\bthe road is closed\b|\broad closed\b|\bclosed to (?:all )?traffic\b""")
    private val SPACES = Regex("""\s+""")

    /** The directions a message says are shut, "Northbound" and so on; both for "All lanes are closed". */
    fun closedDirections(message: String): Set<String> {
        val m = message.lowercase(Locale.US)
        return (ALL_ONE_WAY.findAll(m) + THROUGH_ONE_WAY.findAll(m)).mapNotNull { directionWord(it.groupValues[1]) }.toSortedSet()
    }

    private val LANES = Regex("""(?:^|\.\s+)((?:The|All)\s[^.]*?\b(?:is|are) closed)\.""")

    /** "The north right lane and right shoulder are closed" or "Lane closures exist" as VDOT words it; else null. */
    fun lanesIn(message: String): String? =
        LANES.find(message)?.groupValues?.get(1)?.trim()
            ?: if ("lane closures exist" in message.lowercase(Locale.US)) "Lane closures" else null

    /** "I-81N" -> "I-81"; "Rt. 801N (Bedford County)" -> "Rt. 801"; "ALT US-58E" -> "ALT US-58"; "RT 50" stays. */
    fun roadOf(route: String): String? {
        val r = route.substringBefore('(').trim().ifEmpty { return null }
        return if (directionOfRoute(r) != null) r.dropLast(1) else r
    }

    /** "I-81N" -> "Northbound"; "Rt. 669N (Scott County)" -> "Northbound"; "RT 50" and "US-1BUS" -> null. */
    fun directionOfRoute(route: String): String? {
        val r = route.substringBefore('(').trim()
        return if (r.length >= 2 && r.last() in "NSEW" && r[r.length - 2].isDigit()) directionWord(r.last().toString()) else null
    }

    private fun directionWord(raw: String): String? = when (raw.trim().lowercase(Locale.US).removeSuffix("bound")) {
        "n", "north" -> "Northbound"
        "s", "south" -> "Southbound"
        "e", "east" -> "Eastbound"
        "w", "west" -> "Westbound"
        else -> null
    }

    private fun sign(f: Feature): MessageSign? {
        val id = f.id ?: return null
        val at = f.at ?: return null
        val p = f.properties
        val route = p.text("route")
        val road = route?.let(::roadOf)
        val direction = p.text("dir")?.let(::directionWord) ?: route?.let(::directionOfRoute)
        val mile = p.number("mrm")?.takeIf { it > 0 }
        val name = listOfNotNull(road, direction).joinToString(" ").ifEmpty { p.text("DMS_name") ?: "Message sign" } +
            (mile?.let { ", mile ${milepost(it)}" } ?: "")
        return MessageSign(
            id = TrafficSource.VA_511.idPrefix + id,
            name = name,
            lat = at.lat,
            lon = at.lon,
            lines = p.text("text")?.replace(SPACES, " ")?.let(::listOf).orEmpty(),
            roadway = road,
            direction = direction,
            source = TrafficSource.VA_511,
        )
    }

    private fun milepost(mile: Double): String = if (mile == Math.floor(mile)) mile.toLong().toString() else "%.1f".format(Locale.US, mile)

    private fun camera(f: Feature): TrafficCamera? {
        val p = f.properties
        val at = f.at ?: return null
        val id = f.id ?: p.text("name") ?: return null
        val still = p.text("image_url")?.takeIf { isOn(it, { host -> host == STILL_HOST }, ".png", ".jpg") }
        val video = p.text("https_url")?.takeIf { isOn(it, { host -> host.endsWith(VIDEO_HOST_SUFFIX) }, ".m3u8") }
        val name = p.text("description") ?: "Camera $id"
        return TrafficCamera(
            id = TrafficSource.VA_511.idPrefix + id,
            name = name,
            lat = at.lat,
            lon = at.lon,
            imageUrl = still,
            roadway = p.text("route")?.let(::roadOf),
            direction = p.text("direction")?.let(::directionWord),
            disabled = p["active"] == false || (still == null && video == null),
            source = TrafficSource.VA_511,
            videoUrl = video.takeIf { still == null },
        )
    }

    /** An https link, no query, on a host [hostOk] takes, whose path ends in one of [exts]. */
    private fun isOn(url: String, hostOk: (String) -> Boolean, vararg exts: String): Boolean = try {
        val u = URI(url.trim())
        val host = u.host?.lowercase(Locale.US)
        val path = u.path.orEmpty().lowercase(Locale.US)
        u.scheme == "https" && host != null && u.rawQuery == null && hostOk(host) && exts.any { path.endsWith(it) }
    } catch (e: URISyntaxException) {
        false
    }

    private fun pointOf(geometry: Map<*, *>?): GeoPoint? {
        if (geometry?.get("type") != "Point") return null
        val c = geometry["coordinates"] as? List<*> ?: return null
        val lon = (c.getOrNull(0) as? Number)?.toDouble() ?: return null
        val lat = (c.getOrNull(1) as? Number)?.toDouble() ?: return null
        return if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
    }

    private fun Map<*, *>.number(key: String): Double? = when (val v = this[key]) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun Map<*, *>.text(key: String): String? = when (val v = this[key]) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Long, is Int -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> null
    }

    /** The event layers, the signs and the cameras, through [fetch] (511 Virginia's toggle). */
    class Client(private val fetch: (String) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.VA_511

        override fun events(now: Long): List<Incident> =
            EVENT_LAYERS.flatMap { layer -> parseEvents(layer, fetch(layerUrl(layer)).decodeToString(), now) }.distinctBy { it.id }

        override fun cameras(): List<TrafficCamera> = parseCameras(fetch(CAMERAS_URL).decodeToString())

        override fun signs(): List<MessageSign> = parseSigns(fetch(SIGNS_URL).decodeToString())
    }
}
