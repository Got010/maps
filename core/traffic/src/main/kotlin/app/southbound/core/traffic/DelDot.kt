package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Delaware's DelDOT, from the files its Transportation Management Center's own site reads: the advisories (road
 * work, closures, special travel alerts), the message signs and the cameras of the whole state, no key and nothing
 * about the user in the request. Seen on 2026-10-11 (scripts/feed-probe.py): 17 advisories (16 KB), 26 signs
 * (9 KB), 362 cameras (185 KB). Its cameras stream video only ([TrafficCamera.videoUrl]).
 */
object DelDot {
    private const val BASE = "https://tmc.deldot.gov/json"
    const val ADVISORIES_URL: String = "$BASE/advisory.json"
    const val SIGNS_URL: String = "$BASE/vmsg-vms.json"
    const val CAMERAS_URL: String = "$BASE/videocamera.json"

    /**
     * The advisories, `{"advisories":[...]}`: each a `type` (code C construction, RC road closure, EM emergency road
     * closure, ST special travel alert) and a `where` with its point and words ("PULASKI HIGHWAY WESTBOUND BETWEEN
     * ...: LANES CLOSED DUE TO ROAD WORK.").
     *
     * @throws IllegalArgumentException when it isn't DelDOT's file.
     */
    fun parseAdvisories(json: String): List<Incident> = list(json, "advisories").mapNotNull(::advisory)

    /** The message signs, `{"signTypes":[{"signs":[...]}]}`. */
    fun parseSigns(json: String): List<MessageSign> =
        list(json, "signTypes").flatMap { (it["signs"] as? List<*>).orEmpty().mapNotNull { s -> (s as? Map<*, *>)?.let(::sign) } }

    /** The cameras, `{"videoCameras":[...]}`. */
    fun parseCameras(json: String): List<TrafficCamera> = list(json, "videoCameras").mapNotNull(::camera)

    private fun list(json: String, key: String): List<Map<*, *>> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        } as? Map<*, *>
        val rows = root?.get(key) as? List<*> ?: throw IllegalArgumentException("sent no list")
        return rows.mapNotNull { it as? Map<*, *> }
    }

    private fun advisory(m: Map<*, *>): Incident? {
        val id = m.text("id") ?: return null
        val where = m["where"] as? Map<*, *> ?: return null
        val at = where.point() ?: return null
        val code = (m["type"] as? Map<*, *>)?.text("code")?.uppercase(Locale.US)
        val location = where.text("location")
        val description = listOfNotNull(location, m.text("impact")).joinToString(" ").ifEmpty { (m["type"] as? Map<*, *>)?.text("name") ?: "Advisory" }
        val kind = kindOf(code, description)
        return Incident(
            id = TrafficSource.DE_DELDOT.idPrefix + id,
            kind = kind,
            roadway = location?.let(::roadIn) ?: (where["address"] as? Map<*, *>)?.text("address1")?.replace(HOUSE_NUMBER, "")?.ifEmpty { null },
            direction = location?.let(::directionIn),
            description = description,
            location = at,
            severity = null,
            lanesStatus = if (kind == IncidentKind.CLOSURE) "All lanes closed" else null,
            updatedAt = (m.text("timestampMS")?.toLongOrNull() ?: m.text("timestamp")?.let(::instant))?.takeIf { it > 0 },
            source = TrafficSource.DE_DELDOT,
        )
    }

    /** RC and EM shut the road; C is road work; ST a special travel alert, by its words. */
    fun kindOf(code: String?, description: String): IncidentKind = when (code) {
        "RC", "EM" -> IncidentKind.CLOSURE
        "C" -> IncidentKind.ROADWORK
        else -> FiveElevenNy.kindOf(null, null, null, description.lowercase(Locale.US)).takeUnless { it == IncidentKind.CLOSURE }
            ?: IncidentKind.OTHER
    }

    private fun sign(m: Map<*, *>): MessageSign? {
        val id = m.text("permit") ?: m.text("systemId") ?: (m["systemId"] as? Number)?.toLong()?.toString() ?: return null
        val at = m.point() ?: return null
        val title = m.text("title")
        val lines = if (m["enable"] == false) emptyList() else signLines(m.text("message"))
        return MessageSign(
            id = TrafficSource.DE_DELDOT.idPrefix + id,
            name = title ?: "Sign $id",
            lat = at.lat,
            lon = at.lon,
            lines = lines,
            roadway = title?.let(::roadIn),
            direction = title?.let(::directionIn),
            updatedAt = (m["messageLastUpdated"] as? Number)?.toLong()?.takeIf { it > 0 },
            source = TrafficSource.DE_DELDOT,
        )
    }

    /**
     * "FASTER<br/>IS<br/>FATAL<br/>---------<br/>SLOWER<br/>IS<br/>SAFE" -> FASTER, IS, FATAL, "", SLOWER, IS, SAFE:
     * a line of dashes is DelDOT's page break, which [MessageSign.lines] marks with an empty string.
     */
    fun signLines(message: String?): List<String> {
        if (message.isNullOrBlank()) return emptyList()
        val lines = ArrayList<String>()
        for (part in message.split(BREAK)) {
            val line = part.replace(TAG, "").replace("&nbsp;", " ").replace("&amp;", "&").replace(SPACES, " ").trim()
            when {
                // No page break at the top, nor two in a row; blank lines within a page are dropped.
                line.length >= 3 && line.all { it == '-' } -> if (lines.lastOrNull()?.isNotEmpty() == true) lines += ""
                line.isNotEmpty() -> lines += line
            }
        }
        while (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
        return lines
    }

    private val BREAK = Regex("""<br\s*/?>|\n""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val SPACES = Regex("""\s+""")

    private fun camera(m: Map<*, *>): TrafficCamera? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val video = (m["urls"] as? Map<*, *>)?.text("m3u8s")?.takeIf(::isVideo)
        val title = m.text("title") ?: "Camera"
        return TrafficCamera(
            id = TrafficSource.DE_DELDOT.idPrefix + id,
            name = title,
            lat = at.lat,
            lon = at.lon,
            imageUrl = null,
            roadway = roadIn(title),
            direction = directionIn(title),
            disabled = m["enabled"] == false || m.text("status")?.equals("Active", ignoreCase = true) == false || video == null,
            source = TrafficSource.DE_DELDOT,
            videoUrl = video,
        )
    }

    /** DelDOT's stream server, https://video.deldot.gov:443/live/KCAM001.stream/playlist.m3u8; anything else is not followed. */
    private const val VIDEO_HOST = "video.deldot.gov"

    private fun isVideo(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host == VIDEO_HOST && u.port in setOf(-1, 443) && u.rawQuery == null && u.path.endsWith(".m3u8")
    } catch (e: URISyntaxException) {
        false
    }

    /** "2109 Silverside Road" -> "Silverside Road". */
    private val HOUSE_NUMBER = Regex("""^\d+[A-Z]?\s+""")

    private val ROUTE = Regex("""\b(I|US|DE|SR|MD|PA|NJ)[- ]?(\d{1,3}[A-Z]?)\b""")

    /** "DE 1 @ DE 9" -> "DE 1"; "I-95 NB AT ..." -> "I-95"; SR (Delaware's own name for its routes) -> "DE". */
    fun roadIn(text: String): String? {
        val m = ROUTE.find(text.uppercase(Locale.US)) ?: return null
        val (kind, number) = m.destructured
        return when (kind) {
            "I" -> "I-$number"
            "SR" -> "DE $number"
            else -> "$kind $number"
        }
    }

    private val DIRECTION_WORD = Regex("""\b(NORTH|SOUTH|EAST|WEST)BOUND\b|\b([NSEW])B\b""", RegexOption.IGNORE_CASE)

    /** "WESTBOUND" or "NB" -> "Westbound", "Northbound". */
    fun directionIn(text: String): String? {
        val m = DIRECTION_WORD.find(text) ?: return null
        val word = m.groupValues[1].ifEmpty {
            when (m.groupValues[2].uppercase(Locale.US)) {
                "N" -> "north"
                "S" -> "south"
                "E" -> "east"
                else -> "west"
            }
        }
        return word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) } + "bound"
    }

    private fun instant(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun Map<*, *>.point(): GeoPoint? {
        val lat = (this["lat"] as? Number)?.toDouble() ?: return null
        val lon = (this["lon"] as? Number)?.toDouble() ?: return null
        return if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
    }

    private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** The whole state's three files, through [fetch] (DelDOT's toggle). */
    class Client(private val fetch: (String) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.DE_DELDOT

        override fun events(now: Long): List<Incident> = parseAdvisories(fetch(ADVISORIES_URL).decodeToString())

        override fun cameras(): List<TrafficCamera> = parseCameras(fetch(CAMERAS_URL).decodeToString())

        override fun signs(): List<MessageSign> = parseSigns(fetch(SIGNS_URL).decodeToString())
    }
}
