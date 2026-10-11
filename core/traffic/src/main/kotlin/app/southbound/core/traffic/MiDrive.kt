package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Michigan's Mi Drive (MDOT), from the lists its own map page reads, `/MiDrive/<layer>/AllForMap`: the incidents
 * (a title and an HTML card with the lanes, the event type and the message), the work zones (a title such as
 * "SB US-31 BR: Single-Lane Closure", the zone's line) and the cameras (position and title; the still's link is in
 * the camera's own detail page, [cameraInfoUrl], which the camera card fetches when opened). No key, nothing about
 * the user in the request. Seen on 2026-10-11 (scripts/feed-probe.py): 1 incident, 588 work zones (653 KB with
 * their lines), 804 cameras (220 KB), 565 message signs whose text is only in one detail page each, so signs are
 * not read.
 */
object MiDrive {
    private const val BASE = "https://mdotjboss.state.mi.us/MiDrive"
    const val INCIDENTS_URL: String = "$BASE/incidents/AllForMap"
    const val CONSTRUCTION_URL: String = "$BASE/construction/AllForMap"
    const val CAMERAS_URL: String = "$BASE/camera/AllForMap"

    /** A camera's detail, `{"link": "https://micamerasimages.net/thumbs/<camera>.flv.jpg?item=1", ...}`. */
    fun cameraInfoUrl(id: String): String = "$BASE/camera/getCameraInformation/$id"

    /** The stills' server; anything else in a camera's `link` is not followed. */
    private const val STILL_HOST = "micamerasimages.net"

    private val EASTERN: ZoneId = ZoneId.of("America/Detroit")

    /**
     * The incidents, `[{id, title, latitude, longitude, icon, message}]`: "Crash on EB M-43" with lanes and words in
     * the `message` HTML; one whose title starts "Cleared" is over and skipped.
     *
     * @throws IllegalArgumentException when it isn't the list.
     */
    fun parseIncidents(json: String): List<Incident> = rows(json).mapNotNull(::incident)

    /** The work zones, `[{id, title, latitude, longitude, icon, coordinatePoints, active}]`, at their point. */
    fun parseConstruction(json: String): List<Incident> = rows(json).mapNotNull(::workZone)

    /** The cameras, `[{id, title, latitude, longitude, icon, link, orientation}]`; the still comes by [cameraInfoUrl]. */
    fun parseCameras(json: String): List<TrafficCamera> = rows(json).mapNotNull(::camera)

    /** The still's link in a camera's detail, when on [STILL_HOST] over https; null otherwise. */
    fun stillOf(infoJson: String): String? {
        val m = runCatching { MiniJson.parse(infoJson) }.getOrNull() as? Map<*, *> ?: return null
        return m.text("link")?.takeIf(::isStill)
    }

    private fun rows(json: String): List<Map<*, *>> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        }
        val list = root as? List<*> ?: throw IllegalArgumentException("sent no list")
        return list.mapNotNull { it as? Map<*, *> }
    }

    private fun incident(m: Map<*, *>): Incident? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val title = m.text("title") ?: "Incident"
        if (title.startsWith("Cleared", ignoreCase = true)) return null
        val fields = fields(m.text("message").orEmpty())
        val type = fields["Event Type"] ?: title.substringBefore(" on ")
        val lanes = fields["Lanes Blocked"]?.takeUnless { it.equals("None", ignoreCase = true) }?.let { "$it blocked" }
        val message = fields["Event Message"]
        val location = fields["Location"] ?: title.substringAfter(" on ", "").trim().ifEmpty { null }
        val kind = kindOf(type, message ?: title, lanes)
        return Incident(
            id = TrafficSource.MI_DRIVE.idPrefix + id,
            kind = kind,
            roadway = (location ?: title).let(::roadIn),
            direction = (location ?: title).let(::directionIn),
            description = listOfNotNull(message ?: title, fields["County"]?.let { "$it County" }).joinToString(" "),
            location = at,
            severity = null,
            lanesStatus = if (kind == IncidentKind.CLOSURE) "All lanes closed" else lanes,
            updatedAt = fields["Reported"]?.let(::todayAt),
            source = TrafficSource.MI_DRIVE,
        )
    }

    /** "Crash" a crash; "Road Closed" or every lane blocked a closure; "Construction" road work; the rest other. */
    fun kindOf(type: String?, words: String, lanes: String?): IncidentKind {
        val t = type.orEmpty().lowercase(Locale.US)
        val w = words.lowercase(Locale.US)
        val l = lanes.orEmpty().lowercase(Locale.US)
        return when {
            "closed" in t || "all lanes" in l || w.contains("road closed") || w.contains(" is closed") && "lane" !in w -> IncidentKind.CLOSURE
            "crash" in t || "accident" in t || "disabled" in t || "stall" in t -> IncidentKind.ACCIDENT
            "construction" in t || "work" in t || "maintenance" in t -> IncidentKind.ROADWORK
            "police" in t -> IncidentKind.POLICE
            else -> FiveElevenNy.kindOf(null, null, null, w).takeUnless { it == IncidentKind.CLOSURE } ?: IncidentKind.OTHER
        }
    }

    private fun workZone(m: Map<*, *>): Incident? {
        val id = m.text("id") ?: return null
        if (m["active"] == false) return null
        val at = m.point() ?: return null
        val title = m.text("title") ?: "Road work"
        val where = title.substringBefore(":").trim()
        val what = title.substringAfter(":", "").trim()
        val closed = what.equals("Total Closure", ignoreCase = true)
        return Incident(
            id = TrafficSource.MI_DRIVE.idPrefix + id,
            kind = if (closed) IncidentKind.CLOSURE else IncidentKind.ROADWORK,
            roadway = roadIn(where),
            direction = directionIn(where),
            description = title,
            location = at,
            severity = null,
            lanesStatus = if (closed) "All lanes closed" else what.takeIf { it.endsWith("Closure", ignoreCase = true) || it.equals("Lane Shift", ignoreCase = true) },
            updatedAt = null,
            source = TrafficSource.MI_DRIVE,
        )
    }

    private fun camera(m: Map<*, *>): TrafficCamera? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val title = m.text("title") ?: "Camera $id"
        return TrafficCamera(
            id = TrafficSource.MI_DRIVE.idPrefix + id,
            name = title,
            lat = at.lat,
            lon = at.lon,
            imageUrl = m.text("link")?.takeIf(::isStill),
            roadway = roadIn(title),
            direction = directionIn(title),
            disabled = false,
            source = TrafficSource.MI_DRIVE,
            stillVia = cameraInfoUrl(id),
        )
    }

    private fun isStill(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host == STILL_HOST && u.path.lowercase(Locale.US).endsWith(".jpg")
    } catch (e: URISyntaxException) {
        false
    }

    private val FIELD = Regex("""<strong>\s*([^<:]+?):?\s*</strong>\s*([^<]*)""")

    /** The card's `<strong>Label:</strong> value` pairs, "Lanes Blocked" -> "Right Center Lane, Right Lane". */
    fun fields(html: String): Map<String, String> =
        FIELD.findAll(html).associate { it.groupValues[1].trim().trimEnd(':') to it.groupValues[2].replace(SPACES, " ").trim() }.filterValues { it.isNotEmpty() }

    private val SPACES = Regex("""\s+""")
    private val ROUTE = Regex("""\b(I|US|M|BL|BR)-(\d{1,3})(\s+(?:BL|BR))?\b""")

    /** "EB M-43 at Maple Hill Dr" -> "M-43"; "SB US-31 BR" -> "US-31 BR"; "NB I-75 @ W of Springwells" -> "I-75". */
    fun roadIn(text: String): String? {
        val m = ROUTE.find(text.uppercase(Locale.US)) ?: return null
        return "${m.groupValues[1]}-${m.groupValues[2]}${m.groupValues[3].replace(SPACES, " ")}"
    }

    private val DIRECTION_WORD = Regex("""\b([NSEW])B\b""")

    /** "EB M-43" -> "Eastbound". */
    fun directionIn(text: String): String? = DIRECTION_WORD.find(text.uppercase(Locale.US))?.let { Ibi511.direction(it.groupValues[1]) }

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** "10:06 AM" (today, Michigan time) -> POSIX millis; a time later than now is yesterday's. */
    fun todayAt(text: String, now: Long = System.currentTimeMillis()): Long? = try {
        val time = LocalTime.parse(text.trim().uppercase(Locale.US), CLOCK)
        val today = java.time.Instant.ofEpochMilli(now).atZone(EASTERN).toLocalDate()
        var at = LocalDateTime.of(today, time).atZone(EASTERN).toInstant().toEpochMilli()
        if (at > now + 60_000) at = LocalDateTime.of(today.minusDays(1), time).atZone(EASTERN).toInstant().toEpochMilli()
        at
    } catch (e: DateTimeParseException) {
        null
    }

    private fun Map<*, *>.point(): GeoPoint? {
        val lat = (this["latitude"] as? Number)?.toDouble() ?: return null
        val lon = (this["longitude"] as? Number)?.toDouble() ?: return null
        return if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
    }

    private fun Map<*, *>.text(key: String): String? = when (val v = this[key]) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Long, is Int -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> null
    }


    /** The three lists, through [fetch] (Mi Drive's toggle). No signs. */
    class Client(private val fetch: (String) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.MI_DRIVE

        override fun events(now: Long): List<Incident> =
            parseIncidents(fetch(INCIDENTS_URL).decodeToString()) + parseConstruction(fetch(CONSTRUCTION_URL).decodeToString())

        override fun cameras(): List<TrafficCamera> = parseCameras(fetch(CAMERAS_URL).decodeToString())

        override fun signs(): List<MessageSign> = emptyList()
    }
}
