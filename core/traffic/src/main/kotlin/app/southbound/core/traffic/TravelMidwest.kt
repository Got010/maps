package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Illinois' Travel Midwest (the Gateway Traveler Information System IDOT and the Illinois Tollway run), from the
 * GeoJSON files its own map reads: the incidents, the road work, the cameras and the message signs, each a POST
 * of `{}` (the site answers nothing else) with no key and nothing about the user in it. Seen on 2026-10-11
 * (scripts/feed-probe.py): incidents 6 KB, road work 540 KB (with the work zones' polylines), 962 KB of cameras,
 * 338 KB of signs. The files carry the neighbours' data the Gateway shares (Iowa's and Indiana's CARS, Wisconsin);
 * only Illinois' own agencies' items ([STATE_PREFIX]) are kept, the other states having 511s of their own.
 */
object TravelMidwest {
    private const val BASE = "https://travelmidwest.com/lmiga"
    const val INCIDENTS_URL: String = "$BASE/incidentMap.json"
    const val CONSTRUCTION_URL: String = "$BASE/constructionMap.json?type=lines"
    const val CAMERAS_URL: String = "$BASE/cameraMap.json"
    const val SIGNS_URL: String = "$BASE/dmsMap.json"

    /** What the site's map posts for each file. */
    val BODY: ByteArray = "{}".encodeToByteArray()

    /** Every item's id starts with its state's code and agency: IL-IDOTD1, IL-ISTHA (the Tollway), IA-CARS, IN-INDOT. */
    const val STATE_PREFIX: String = "IL-"

    /** The snapshots' server, https://cctv.travelmidwest.com/snapshots/<camera>_<view>.jpg; anything else is not followed. */
    private const val STILL_HOST = "cctv.travelmidwest.com"

    private val CENTRAL: ZoneId = ZoneId.of("America/Chicago")
    private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d/yy h:mm a", Locale.US)

    /**
     * The incidents, `{"features":[...]}`: each a crash, stall or other event (`desc`) at a point, with where
     * (`locDesc` "SB IL-40 at Pioneer Pkwy, Peoria"), the lanes (`closure` "Right lane closed, one lane open",
     * `lanes` partial or full) and the agency (`src`).
     *
     * @throws IllegalArgumentException when it isn't Travel Midwest's file.
     */
    fun parseIncidents(json: String): List<Incident> = features(json).mapNotNull { incident(it, roadwork = false) }

    /** The road work, the same shape with the work zone's polylines beside its point; `a` marks one under way. */
    fun parseConstruction(json: String): List<Incident> = features(json).mapNotNull { incident(it, roadwork = true) }

    /** The cameras: one per site, its first snapshot as the still. */
    fun parseCameras(json: String): List<TrafficCamera> = features(json).mapNotNull(::camera)

    /** The message signs, `lines` as shown. */
    fun parseSigns(json: String): List<MessageSign> = features(json).mapNotNull(::sign)

    private class Feature(val id: String, val properties: Map<*, *>, val point: GeoPoint?)

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
            val id = p.text("id")?.takeIf { it.startsWith(STATE_PREFIX) } ?: return@mapNotNull null
            Feature(id, p, point(m["geometry"]))
        }
    }

    /** A Point, or the first Point of a GeometryCollection (the work zones put their polylines beside one). */
    private fun point(geometry: Any?): GeoPoint? {
        val g = geometry as? Map<*, *> ?: return null
        return when (g["type"]) {
            "Point" -> {
                val c = g["coordinates"] as? List<*> ?: return null
                val lon = (c.getOrNull(0) as? Number)?.toDouble() ?: return null
                val lat = (c.getOrNull(1) as? Number)?.toDouble() ?: return null
                if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
            }
            "GeometryCollection" -> (g["geometries"] as? List<*>).orEmpty().firstNotNullOfOrNull(::point)
            else -> null
        }
    }

    private fun incident(f: Feature, roadwork: Boolean): Incident? {
        val at = f.point ?: return null
        val p = f.properties
        val what = p.text("desc")
        val where = p.text("locDesc")
        val lanes = p.text("closure")?.takeUnless { it.equals("None", ignoreCase = true) || it.equals("N/A", ignoreCase = true) }
        val kind = kindOf(roadwork, what, lanes, p.text("lanes"))
        return Incident(
            id = TrafficSource.IL_TRAVEL_MIDWEST.idPrefix + f.id,
            kind = kind,
            roadway = where?.let(::roadIn),
            direction = direction(p.text("locDir")) ?: where?.let(::directionIn),
            description = listOfNotNull(what, where).joinToString(": ").ifEmpty { if (roadwork) "Road work" else "Incident" },
            location = at,
            severity = p.text("sev"),
            lanesStatus = if (kind == IncidentKind.CLOSURE) "All lanes closed" else lanes,
            updatedAt = (p.text("start") ?: p.text("time"))?.let(::whenMillis),
            source = TrafficSource.IL_TRAVEL_MIDWEST,
        )
    }

    /**
     * `lanes` "full", or a `closure` shutting every lane or the road, is a closure; the road work file is road
     * work; an incident is sorted by its words ("Crash", "Stalled vehicle", "Debris").
     */
    fun kindOf(roadwork: Boolean, what: String?, closure: String?, lanes: String?): IncidentKind {
        val c = closure.orEmpty().lowercase(Locale.US)
        val w = what.orEmpty().lowercase(Locale.US)
        return when {
            lanes.equals("full", ignoreCase = true) || ("all lanes" in c && "closed" in c) || c.startsWith("road closed") ||
                c.startsWith("ramp closed") || w.startsWith("road closed") || w.startsWith("ramp closed") -> IncidentKind.CLOSURE
            roadwork -> IncidentKind.ROADWORK
            "crash" in w || "accident" in w || "collision" in w || "stall" in w || "disabled" in w -> IncidentKind.ACCIDENT
            "police" in w || "enforcement" in w -> IncidentKind.POLICE
            "work" in w || "construction" in w || "maintenance" in w -> IncidentKind.ROADWORK
            "event" in w || "parade" in w || "game" in w -> IncidentKind.EVENT
            else -> FiveElevenNy.kindOf(null, null, null, w).takeUnless { it == IncidentKind.CLOSURE } ?: IncidentKind.OTHER
        }
    }

    private fun camera(f: Feature): TrafficCamera? {
        val at = f.point ?: return null
        val p = f.properties
        val stills = (p["remUrls"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim() }.filter(::isStill)
        val dirs = (p["dirs"] as? List<*>).orEmpty().mapNotNull { it as? String }
        val where = p.text("locDesc") ?: "Camera"
        return TrafficCamera(
            id = TrafficSource.IL_TRAVEL_MIDWEST.idPrefix + f.id,
            name = where,
            lat = at.lat,
            lon = at.lon,
            imageUrl = stills.firstOrNull(),
            roadway = roadIn(where),
            direction = dirs.singleOrNull()?.let(::direction) ?: directionIn(where),
            disabled = p["dis"] == true || stills.isEmpty(),
            source = TrafficSource.IL_TRAVEL_MIDWEST,
        )
    }

    private fun isStill(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host == STILL_HOST && u.rawQuery == null && u.path.lowercase(Locale.US).endsWith(".jpg")
    } catch (e: URISyntaxException) {
        false
    }

    private fun sign(f: Feature): MessageSign? {
        val at = f.point ?: return null
        val p = f.properties
        val where = p.text("locDesc")
        // `extOff`: the sign is dark; `stat` other than Operational: its message is not to be trusted.
        val lines = if (p["extOff"] == true || p.text("stat")?.equals("Operational", ignoreCase = true) == false) emptyList()
        else FiveElevenNy.signLines((p["lines"] as? List<*>).orEmpty().mapNotNull { it as? String })
        return MessageSign(
            id = TrafficSource.IL_TRAVEL_MIDWEST.idPrefix + f.id,
            name = where ?: "Sign ${f.id}",
            lat = at.lat,
            lon = at.lon,
            lines = lines,
            roadway = where?.let(::roadIn),
            direction = direction(p.text("locDir")) ?: where?.let(::directionIn),
            updatedAt = (p.text("lstUpd") ?: p.text("lstRecd"))?.let(::whenMillis),
            source = TrafficSource.IL_TRAVEL_MIDWEST,
        )
    }

    private val ROUTE = Regex("""\b(I|US|IL|IN|IA|WI|MO)[- ]?(\d{1,3}[A-Z]?)\b""")

    /** "SB IL-40 at Pioneer Pkwy" -> "IL 40"; "I-355 at Spring Creek Plaza" -> "I-355". */
    fun roadIn(text: String): String? {
        val m = ROUTE.find(text.uppercase(Locale.US)) ?: return null
        val (kind, number) = m.destructured
        return if (kind == "I") "I-$number" else "$kind $number"
    }

    /** "SB", "NWB", "EB" -> "Southbound", "Northwestbound", "Eastbound"; "Both"/"BI" -> "Both Directions". */
    fun direction(raw: String?): String? {
        val d = raw?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        if (d == "BOTH" || d == "BI" || d == "BOTH DIRECTIONS") return "Both Directions"
        val letters = d.removeSuffix("B").removeSuffix("BOUND")
        if (letters.isEmpty() || letters.length > 2 || letters.any { it !in "NSEW" }) return Ibi511.direction(raw)
        val words = letters.map {
            when (it) {
                'N' -> "north"
                'S' -> "south"
                'E' -> "east"
                else -> "west"
            }
        }
        return words.joinToString("").replaceFirstChar { it.titlecase(Locale.US) } + "bound"
    }

    private val DIRECTION_WORD = Regex("""\b([NSEW]{1,2})B\b""")

    private fun directionIn(text: String): String? = DIRECTION_WORD.find(text.uppercase(Locale.US))?.groupValues?.get(1)?.let { direction(it + "B") }

    /** "9/20/26 9:53 AM" (Central time) or "2026-10-11T08:22:08-05:00" -> POSIX millis; null for anything else. */
    fun whenMillis(text: String): Long? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return try {
            LocalDateTime.parse(t, WHEN).atZone(CENTRAL).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(t).toInstant().toEpochMilli()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** The four files, each a POST of [BODY] through [post] (Travel Midwest's toggle). */
    class Client(private val post: (String, ByteArray) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.IL_TRAVEL_MIDWEST

        override fun events(now: Long): List<Incident> =
            parseIncidents(post(INCIDENTS_URL, BODY).decodeToString()) + parseConstruction(post(CONSTRUCTION_URL, BODY).decodeToString())

        override fun cameras(): List<TrafficCamera> = parseCameras(post(CAMERAS_URL, BODY).decodeToString())

        override fun signs(): List<MessageSign> = parseSigns(post(SIGNS_URL, BODY).decodeToString())
    }
}
