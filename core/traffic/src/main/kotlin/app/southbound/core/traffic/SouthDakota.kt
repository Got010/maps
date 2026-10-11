package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * South Dakota's SD511 (Iteris), from the GeoJSON files its own map reads off its CDN: road work, incidents,
 * restrictions, obstructions and scheduled events as points, and the camera sites (each with several views), no key
 * and nothing about the user in the request. Seen on 2026-10-11 (scripts/feed-probe.py): 81 road work, 27
 * restrictions, 1 obstruction (an enforced road closure), no incidents or events that morning, 40 camera sites plus
 * 135 weather stations with cameras (234 KB). No message signs on the site.
 */
object SouthDakota {
    private const val BASE = "https://sd.cdn.iteris-atis.com/geojson/icons/metadata"
    const val ROAD_WORK_URL: String = "$BASE/icons.road-work.geojson"
    const val INCIDENTS_URL: String = "$BASE/icons.incidents-accidents.geojson"
    const val RESTRICTIONS_URL: String = "$BASE/icons.restriction.geojson"
    const val OBSTRUCTIONS_URL: String = "$BASE/icons.obstructions.geojson"
    const val EVENTS_URL: String = "$BASE/icons.scheduled-events.geojson"
    const val CAMERAS_URL: String = "$BASE/icons.cameras.geojson"
    const val WEATHER_CAMERAS_URL: String = "$BASE/icons.rwis.geojson"

    /** The stills, https://sd.cdn.iteris-atis.com/camera_images/<site>/<view>/latest.jpg; anything else is not followed. */
    private const val STILL_HOST = "sd.cdn.iteris-atis.com"

    /**
     * One of the event files: each feature a `headline` ("Road work: bridge repair", "Incident/event: ENFORCED ROAD
     * CLOSURE"), a `report` (HTML, `<br>` between its parts), `route`, `dir` and `location_description`, at a point
     * whose coordinates may be strings.
     *
     * @throws IllegalArgumentException when it isn't a FeatureCollection.
     */
    fun parseEvents(json: String): List<Incident> = features(json).mapNotNull(::event)

    /** The camera sites (and the weather stations' cameras): one camera per site, its first view's still. */
    fun parseCameras(json: String): List<TrafficCamera> = features(json).mapNotNull(::camera)

    private class Feature(val id: String?, val properties: Map<*, *>, val point: GeoPoint?)

    private fun features(json: String): List<Feature> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        } as? Map<*, *>
        val list = root?.get("features") as? List<*> ?: throw IllegalArgumentException("sent no features")
        return list.mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            val p = m["properties"] as? Map<*, *> ?: return@mapNotNull null
            Feature((m["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: p.text("event_id"), p, point(m["geometry"]))
        }
    }

    private fun point(geometry: Any?): GeoPoint? {
        val g = geometry as? Map<*, *> ?: return null
        if (g["type"] != "Point") return null
        val c = g["coordinates"] as? List<*> ?: return null
        val lon = c.getOrNull(0).number() ?: return null
        val lat = c.getOrNull(1).number() ?: return null
        return if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
    }

    private fun Any?.number(): Double? = when (this) {
        is Number -> toDouble()
        is String -> trim().toDoubleOrNull()
        else -> null
    }

    private fun event(f: Feature): Incident? {
        val id = f.id ?: return null
        val at = f.point ?: return null
        val p = f.properties
        val headline = p.text("headline")
        val report = p.text("report")?.let(::plain)
        val where = p.text("location_description")
        val kind = kindOf(headline, report)
        return Incident(
            id = TrafficSource.SD_511.idPrefix + id,
            kind = kind,
            roadway = p.text("route")?.let(::roadIn) ?: where?.let(::roadIn),
            direction = direction(p.text("dir")),
            description = listOfNotNull(headline, where, report).joinToString(": ").ifEmpty { "Event" },
            location = at,
            severity = null,
            lanesStatus = lanesIn(report, kind),
            // Seconds since 1970 (1783350960); a feed that moves to millis still reads.
            updatedAt = (p["start_time"] as? Number)?.toLong()?.takeIf { it > 0 }?.let { if (it < 100_000_000_000L) it * 1000 else it },
            source = TrafficSource.SD_511,
        )
    }

    /**
     * "Incident/event: ENFORCED ROAD CLOSURE" and a report saying the road is closed shut the road; "Road work" and
     * "Restrictions" headlines are road work; "Incident" by its words ("crash", "stalled").
     */
    fun kindOf(headline: String?, report: String?): IncidentKind {
        val h = headline.orEmpty().lowercase(Locale.US)
        val r = report.orEmpty().lowercase(Locale.US)
        return when {
            "road closure" in h || "road closed" in h || "road closed" in r && "detour" in r || "is closed to traffic" in r -> IncidentKind.CLOSURE
            h.startsWith("road work") || h.startsWith("restriction") || "construction" in h -> IncidentKind.ROADWORK
            "crash" in h || "accident" in h || "crash" in r.substringBefore(".") -> IncidentKind.ACCIDENT
            "stall" in h || "disabled" in h -> IncidentKind.ACCIDENT
            h.startsWith("incident") -> FiveElevenNy.kindOf(null, null, null, r).takeUnless { it == IncidentKind.CLOSURE } ?: IncidentKind.OTHER
            "event" in h -> IncidentKind.EVENT
            else -> IncidentKind.OTHER
        }
    }

    /** "lanes reduced to 1" -> "Reduced to one lane"; a closure always "All lanes closed"; else nothing. */
    fun lanesIn(report: String?, kind: IncidentKind): String? {
        if (kind == IncidentKind.CLOSURE) return "All lanes closed"
        val r = report.orEmpty().lowercase(Locale.US)
        return when {
            "lanes reduced to 1" in r || "reduced to 1 lane" in r || "one lane" in r -> "Reduced to one lane"
            "lane closed" in r || "lane closure" in r -> "Lane closed"
            else -> null
        }
    }

    private fun camera(f: Feature): TrafficCamera? {
        val id = f.id ?: return null
        val at = f.point ?: return null
        val p = f.properties
        val views = (p["cameras"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val stills = views.mapNotNull { it.text("image")?.takeIf(::isStill) }
        val name = p.text("name") ?: views.firstNotNullOfOrNull { it.text("description") } ?: "Camera $id"
        val route = p.text("route") ?: p.text("description")
        return TrafficCamera(
            id = TrafficSource.SD_511.idPrefix + id,
            name = name,
            lat = at.lat,
            lon = at.lon,
            imageUrl = stills.firstOrNull(),
            roadway = route?.let(::roadIn),
            direction = null,
            disabled = stills.isEmpty(),
            source = TrafficSource.SD_511,
        )
    }

    private fun isStill(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host == STILL_HOST && u.rawQuery == null && u.path.lowercase(Locale.US).endsWith(".jpg")
    } catch (e: URISyntaxException) {
        false
    }

    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val SPACES = Regex("""\s+""")

    /** The report's HTML as one line: a `<br>` becomes "; ", other tags go, spaces are squeezed. */
    fun plain(html: String): String =
        html.replace(BREAK, "; ").replace(TAG, "").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace(SPACES, " ").replace(" ;", ";").trim().trimEnd(';').trim()

    private val ROUTE = Regex("""\b(I|US|SD|ND|NE|MN|IA|WY|MT)[- ]?(\d{1,4}[A-Z]?)\b""")

    /** "US-281" -> "US 281"; "I-29" stays; "SD-25" -> "SD 25". */
    fun roadIn(text: String): String? {
        val m = ROUTE.find(text.uppercase(Locale.US)) ?: return null
        val (kind, number) = m.destructured
        return if (kind == "I") "I-$number" else "$kind $number"
    }

    /** "Both Directions" stays; "Northbound"/"NB" -> "Northbound". */
    fun direction(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.US)) {
        "both directions", "both" -> "Both Directions"
        else -> Ibi511.direction(raw)
    }

    private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** The five event files and both camera files, through [fetch] (SD511's toggle). No signs. */
    class Client(private val fetch: (String) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.SD_511

        override fun events(now: Long): List<Incident> =
            listOf(ROAD_WORK_URL, INCIDENTS_URL, RESTRICTIONS_URL, OBSTRUCTIONS_URL, EVENTS_URL)
                .flatMap { parseEvents(fetch(it).decodeToString()) }
                .distinctBy { it.id }

        override fun cameras(): List<TrafficCamera> =
            (parseCameras(fetch(CAMERAS_URL).decodeToString()) + parseCameras(fetch(WEATHER_CAMERAS_URL).decodeToString())).distinctBy { it.id }

        override fun signs(): List<MessageSign> = emptyList()
    }
}
