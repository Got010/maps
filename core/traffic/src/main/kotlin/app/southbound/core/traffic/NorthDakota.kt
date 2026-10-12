package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * North Dakota's NDDOT, from the ArcGIS map service its Travel Information Map reads (`rcrs_dynamic`): the alerts
 * (layer 7), the work zones (21 as points, 22 as lines, each with its centre), and the still cameras (5), queried
 * whole (`where=1=1`, attributes only), no key and nothing about the user in the request. Seen on 2026-10-11
 * (scripts/feed-probe.py): 5 alerts, 55 + 28 work zones, 189 cameras (237 KB with their shapes), no message signs
 * in the service (the video camera and travel alert layers were empty).
 */
object NorthDakota {
    private const val BASE = "https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer"
    private const val QUERY = "query?where=1%3D1&outFields=*&returnGeometry=false&f=json"
    const val ALERTS_URL: String = "$BASE/7/$QUERY"
    const val WORK_ZONE_POINTS_URL: String = "$BASE/21/$QUERY"
    const val WORK_ZONE_LINES_URL: String = "$BASE/22/$QUERY"
    const val CAMERAS_URL: String = "$BASE/5/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=json"

    /** The stills' server, https://www.dot.nd.gov/travel-info/cameras/<camera>.jpg; anything else is not followed. */
    private const val STILL_HOST = "www.dot.nd.gov"

    /**
     * The alerts in effect at [now] (epoch ms): each a `ConditionDesc` ("Warning", "Road Surface in Poor Condition")
     * with its `Comment` on a highway (`HwyDesc` "ND 89", `Direction` "E") at `CenterLat`/`CenterLong`. The layer
     * keeps alerts past their `EffectiveUntil` (the 2026-10-08 sample's first two had ended in June and July): those
     * are left out, or an ended closure would be spoken and routed around.
     *
     * @throws IllegalArgumentException when it isn't the service's answer.
     */
    fun parseAlerts(json: String, now: Long = System.currentTimeMillis()): List<Incident> =
        rows(json).filter { r -> r.attributes.number("EffectiveUntil")?.toLong()?.let { it <= 0 || it > now } ?: true }.mapNotNull(::alert)

    /** The work zones, points and lines alike: `WorkType`, `LaneReduction`, `Comments`, at the zone's centre. */
    fun parseWorkZones(json: String): List<Incident> = rows(json).mapNotNull(::workZone)

    /** The still cameras: up to six views (`Link`, `Link2`...), the first one's JPEG as the still. */
    fun parseCameras(json: String): List<TrafficCamera> = rows(json).mapNotNull(::camera)

    private class Row(val attributes: Map<*, *>, val geometry: Map<*, *>?)

    private fun rows(json: String): List<Row> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        } as? Map<*, *>
        if (root?.get("error") != null) throw IllegalArgumentException("sent an error: ${(root["error"] as? Map<*, *>)?.get("message")}")
        val features = root?.get("features") as? List<*> ?: throw IllegalArgumentException("sent no features")
        return features.mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            Row(m["attributes"] as? Map<*, *> ?: return@mapNotNull null, m["geometry"] as? Map<*, *>)
        }
    }

    private fun alert(r: Row): Incident? {
        val a = r.attributes
        val id = a.number("SegmentID")?.toLong()?.toString() ?: return null
        val at = a.centre() ?: return null
        val condition = a.text("ConditionDesc")
        val comment = a.text("Comment")
        val description = listOfNotNull(condition, comment).joinToString(": ").ifEmpty { "Alert" }
        return Incident(
            id = TrafficSource.ND_DOT.idPrefix + "alert-$id",
            kind = kindOf(condition, comment),
            roadway = a.text("HwyDesc")?.let(::roadIn),
            direction = direction(a.text("Direction")),
            description = description,
            location = at,
            severity = a.text("DelayTimeDesc")?.takeUnless { it.equals("No Delays", ignoreCase = true) },
            lanesStatus = a.text("TravelSpeedsDesc")?.takeIf { it.equals("Reduced", ignoreCase = true) }?.let { "Reduced speeds" },
            updatedAt = a.number("ModifyTime")?.toLong()?.takeIf { it > 0 },
            source = TrafficSource.ND_DOT,
        )
    }

    /** "Road Closed" shuts the road; crashes and road work by their words; the rest ("Warning", poor surface) other. */
    fun kindOf(condition: String?, comment: String?): IncidentKind {
        val c = condition.orEmpty().lowercase(Locale.US)
        val text = comment.orEmpty().lowercase(Locale.US)
        return when {
            "closed" in c || text.startsWith("road closed") || "road is closed" in text -> IncidentKind.CLOSURE
            "crash" in c || "accident" in c || text.startsWith("crash") -> IncidentKind.ACCIDENT
            "work" in c || "construction" in c || "work" in text && "zone" in text -> IncidentKind.ROADWORK
            else -> IncidentKind.OTHER
        }
    }

    private fun workZone(r: Row): Incident? {
        val a = r.attributes
        val id = a.text("WorkZoneID") ?: return null
        if (a.text("Active")?.equals("active", ignoreCase = true) == false) return null
        val at = a.centre() ?: return null
        val lanes = a.text("LaneReduction")?.takeUnless { it.equals("No Lane Closure", ignoreCase = true) }
        val closed = lanes?.lowercase(Locale.US)?.let { "road closed" in it || "all lanes" in it && "closed" in it } == true
        val what = a.text("WorkType") ?: "Road work"
        val where = a.text("ProjectLocation")?.replace(SPACES, " ")
        return Incident(
            id = TrafficSource.ND_DOT.idPrefix + id,
            kind = if (closed) IncidentKind.CLOSURE else IncidentKind.ROADWORK,
            roadway = a.text("HwyDesc")?.let(::roadIn),
            direction = direction(a.text("Direction")),
            description = listOfNotNull(what, where, a.text("Comments")?.replace(SPACES, " ")).joinToString(": "),
            location = at,
            severity = a.text("DelayDesc")?.takeUnless { it.equals("No Delays", ignoreCase = true) },
            lanesStatus = if (closed) "All lanes closed" else lanes,
            updatedAt = a.number("LastUpdated")?.toLong()?.takeIf { it > 0 },
            source = TrafficSource.ND_DOT,
        )
    }

    private fun camera(r: Row): TrafficCamera? {
        val a = r.attributes
        val id = a.number("OBJECTID")?.toLong()?.toString() ?: return null
        val x = r.geometry?.number("x") ?: return null
        val y = r.geometry.number("y") ?: return null
        if (y !in -90.0..90.0 || x !in -180.0..180.0 || (x == 0.0 && y == 0.0)) return null
        val views = listOf("", "2", "3", "4", "5", "6").mapNotNull { n -> listOfNotNull(a.text("Link$n"), a.text("FullPath$n")).firstOrNull(::isStill) }
        val name = a.text("Description")?.removeSuffix(" - NDDOT")?.trim() ?: "Camera $id"
        return TrafficCamera(
            id = TrafficSource.ND_DOT.idPrefix + id,
            name = name,
            lat = y,
            lon = x,
            imageUrl = views.firstOrNull(),
            roadway = roadIn(name),
            direction = null,
            disabled = a.text("Active")?.equals("Y", ignoreCase = true) == false || views.isEmpty(),
            source = TrafficSource.ND_DOT,
        )
    }

    private fun isStill(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host == STILL_HOST && u.rawQuery == null && u.path.lowercase(Locale.US).endsWith(".jpg")
    } catch (e: URISyntaxException) {
        false
    }

    private val ROUTE = Regex("""\b(I|US|ND|SD|MN|MT)[- ]?(\d{1,4}[A-Z]?)\b""")
    private val SPACES = Regex("""\s+""")

    /** "I 94" -> "I-94"; "ND 1804" stays; "Ray - West (US 2 MP 51.3)" -> "US 2". */
    fun roadIn(text: String): String? {
        val m = ROUTE.find(text.uppercase(Locale.US)) ?: return null
        val (kind, number) = m.destructured
        return if (kind == "I") "I-$number" else "$kind $number"
    }

    /** "E" -> "Eastbound"; "B" (both) -> "Both Directions". */
    fun direction(raw: String?): String? = when (raw?.trim()?.uppercase(Locale.US)) {
        "N" -> "Northbound"
        "S" -> "Southbound"
        "E" -> "Eastbound"
        "W" -> "Westbound"
        "B", "BOTH" -> "Both Directions"
        else -> null
    }

    private fun Map<*, *>.centre(): GeoPoint? {
        val lat = number("CenterLat") ?: number("FromLat") ?: return null
        val lon = number("CenterLong") ?: number("FromLong") ?: return null
        return if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
    }

    private fun Map<*, *>.number(key: String): Double? = (this[key] as? Number)?.toDouble()

    private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** The alerts, both work zone layers and the cameras, through [fetch] (NDDOT's toggle). No signs. */
    class Client(private val fetch: (String) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.ND_DOT

        override fun events(now: Long): List<Incident> =
            parseAlerts(fetch(ALERTS_URL).decodeToString(), now) + parseWorkZones(fetch(WORK_ZONE_POINTS_URL).decodeToString()) +
                parseWorkZones(fetch(WORK_ZONE_LINES_URL).decodeToString())

        override fun cameras(): List<TrafficCamera> = parseCameras(fetch(CAMERAS_URL).decodeToString())

        override fun signs(): List<MessageSign> = emptyList()
    }
}
