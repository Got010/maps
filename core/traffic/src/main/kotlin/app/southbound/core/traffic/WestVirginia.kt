package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * West Virginia's WV511 (WVDOH), from the services its own map reads (`/wsvc/gmap.asmx/...` on its site), the
 * whole state each time, no key and nothing about the user in the request:
 *
 * - the incidents as KML ([INCIDENTS_URL]: `buildEventsKMLi_Filtered` with every severity the map's checkboxes
 *   ask for, 1 to 4: road closures, lane closures, others and detours; 5 that night), each a point with the kind as
 *   its name ("Vehicle Crash", "Road Washed Out") and a sentence that says when the road is shut ("The northbound
 *   road is closed."); the severity's icon, `icon_feed_s1`, is the road-closure one;
 * - the current roadwork as KML ([ROADWORK_URL], `buildEventsKMLc`: 74), "Bridge Deck Repairs on US-50W from Mile
 *   Marker 5.3 to Mile Marker 6.51", its hours and the lanes;
 * - the message signs showing something as KML ([SIGNS_URL], `buildDMSKML?isActive=1`; the 81 others say "No
 *   Message");
 * - the cameras ([CAMERAS_URL], `buildCamerasJSONjs`: a script whose `camera_data` is JSON, 133 cameras). None has
 *   a still; each streams HLS from one of WVDOH's video servers, named in its player page ([playerUrl]), which the
 *   card reads and then shows a frame of the stream ([Hls]).
 *
 * Seen in the feed probe on 2026-10-13 and 14 (scripts/feed-probe.py, rounds `dot-south-*`).
 */
object WestVirginia {
    private const val BASE = "https://www.wv511.org/wsvc/gmap.asmx"
    const val INCIDENTS_URL: String = "$BASE/buildEventsKMLi_Filtered?CategoryIDs=&SeverityIDs=1%2C2%2C3%2C4%2C&is511Only=0"
    const val ROADWORK_URL: String = "$BASE/buildEventsKMLc"
    const val SIGNS_URL: String = "$BASE/buildDMSKML?isActive=1"
    const val CAMERAS_URL: String = "$BASE/buildCamerasJSONjs"

    /**
     * A camera's player page, which names its live video: `https://vtc2.roadsummary.com/rtplive/CAM049/playlist.m3u8`.
     * The cameras stream from three servers (vtc1 to vtc3) and the list doesn't say which, so the card reads this
     * page first ([TrafficCamera.videoVia]).
     */
    fun playerUrl(cameraId: String): String = "https://www.wv511.org/flowplayeri.aspx?CAMID=$cameraId"

    /** WVDOH's video servers; a link elsewhere in the player page is not followed. */
    const val VIDEO_HOST_SUFFIX: String = ".roadsummary.com"

    private val STREAM = Regex("""https://([A-Za-z0-9.-]+)(/rtplive/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\.m3u8)(?![?\w])""")

    /** The player page's playlist link on WVDOH's video servers, or null when it names none. */
    fun streamOf(page: String): String? = STREAM.findAll(page)
        .firstOrNull { it.groupValues[1].lowercase(Locale.US).endsWith(VIDEO_HOST_SUFFIX) }
        ?.let { "https://${it.groupValues[1].lowercase(Locale.US)}${it.groupValues[2]}" }

    private val CAMERA_ID = Regex("""[A-Za-z0-9_-]{1,40}""")
    private val EASTERN: ZoneId = ZoneId.of("America/New_York")

    /**
     * The incidents: a road-closure icon or a sentence that shuts the road is a closure; a crash an accident.
     *
     * @throws IllegalArgumentException when it isn't WV511's KML.
     */
    fun parseIncidents(kml: String): List<Incident> = placemarks(kml).mapNotNull { event(it, roadwork = false) }

    /** The current roadwork: road work, or a closure when its words shut the road. */
    fun parseRoadwork(kml: String): List<Incident> = placemarks(kml).mapNotNull { event(it, roadwork = true) }

    /** The signs and their pages; "No Message" is blank. */
    fun parseSigns(kml: String): List<MessageSign> = placemarks(kml).mapNotNull(::sign)

    /** The cameras in the script's `camera_data`, each with its stream. */
    fun parseCameras(script: String): List<TrafficCamera> {
        val start = script.indexOf("camera_data").takeIf { it >= 0 } ?: throw IllegalArgumentException("sent no camera_data")
        val json = objectAt(script, script.indexOf('{', start)) ?: throw IllegalArgumentException("sent a camera_data that doesn't end")
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent a camera_data that isn't JSON")
        } as? Map<*, *>
        val cams = root?.get("cams") as? List<*> ?: throw IllegalArgumentException("sent no cameras")
        return cams.mapNotNull { (it as? Map<*, *>)?.let(::camera) }
    }

    private class Placemark(val id: String?, val name: String?, val description: String, val style: String?, val at: GeoPoint?)

    private val PLACEMARK = Regex("""<Placemark\b([^>]*)>(.*?)</Placemark>""", RegexOption.DOT_MATCHES_ALL)
    private val ID_ATTR = Regex("""\bid\s*=\s*"([^"]*)"""")
    private val COORDINATES = Regex("""<coordinates>\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)""")
    private val STYLE = Regex("""<styleUrl>\s*#?([^<\s]+)\s*</styleUrl>""")

    private fun placemarks(kml: String): List<Placemark> {
        if ("<kml" !in kml) throw IllegalArgumentException("sent something that isn't KML")
        return PLACEMARK.findAll(kml).map { m ->
            val body = m.groupValues[2]
            val at = COORDINATES.find(body)?.let { c ->
                val lon = c.groupValues[1].toDoubleOrNull()
                val lat = c.groupValues[2].toDoubleOrNull()
                if (lat == null || lon == null || (lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
            }
            Placemark(
                id = ID_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
                name = element(body, "name")?.let(::plain)?.takeIf { it.isNotEmpty() },
                description = element(body, "description").orEmpty(),
                style = STYLE.find(body)?.groupValues?.get(1),
                at = at,
            )
        }.toList()
    }

    private val ELEMENTS = listOf("name", "description").associateWith { Regex("""<$it>(.*?)</$it>""", RegexOption.DOT_MATCHES_ALL) }

    /** An element's text, its CDATA unwrapped; entities are left for [plain]. */
    private fun element(body: String, tag: String): String? {
        val m = ELEMENTS.getValue(tag).find(body) ?: return null
        val inner = m.groupValues[1].trim()
        return if (inner.startsWith("<![CDATA[")) inner.removePrefix("<![CDATA[").substringBeforeLast("]]>") else inner
    }

    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val SPACES = Regex("""\s+""")

    /** HTML to one line of text. */
    private fun plain(html: String): String =
        decode(TAG.replace(BREAK.replace(html.replace(COMMENT, " "), " "), " ")).replace(SPACES, " ").trim()

    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    private fun decode(s: String): String = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    private val UPDATED = Regex("""Last Updated:\s*([A-Z][a-z]{2} \d{1,2}, \d{4} \d{1,2}:\d{2} [AP]M)""")
    private val TAIL = Regex("""\s*(?:Last Updated:|Expires:).*$""", RegexOption.DOT_MATCHES_ALL)

    private fun event(p: Placemark, roadwork: Boolean): Incident? {
        val id = p.id ?: return null
        val at = p.at ?: return null
        val text = plain(p.description)
        val description = TAIL.replace(text, "").trim().ifEmpty { p.name ?: if (roadwork) "Road work" else "Incident" }
        val shut = closedDirections(description)
        val kind = kindOf(p.name, p.style, description, roadwork)
        val route = routeIn(description)
        return Incident(
            id = TrafficSource.WV_511.idPrefix + id,
            kind = kind,
            roadway = route?.let(::roadOf),
            direction = when {
                shut.size >= 2 -> "Both Directions"
                shut.size == 1 -> shut.first()
                kind == IncidentKind.CLOSURE -> null
                else -> route?.let(::directionOfRoute)
            },
            description = description,
            location = at,
            severity = null,
            lanesStatus = when {
                kind == IncidentKind.CLOSURE -> "All lanes closed"
                "lane closure" in description.lowercase(Locale.US) -> "Lane closures"
                else -> null
            },
            updatedAt = UPDATED.find(text)?.groupValues?.get(1)?.let(::easternTime),
            source = TrafficSource.WV_511,
        )
    }

    private val ROAD_SHUT = Regex(
        """\b(?:north|south|east|west)bound road is closed\b|\b(?:the )?road is closed\b|\broad closed\b|""" +
            """\bclosed to (?:all )?traffic\b|\bfull closure\b|\bbridge (?:is )?closed\b|\ball lanes (?:are )?closed\b""",
    )
    private val ONE_WAY_SHUT = Regex("""\b(north|south|east|west)bound road is closed\b""")

    /**
     * A closure by the road-closure icon (`icon_feed_s1`, `icon_feed_c1`) or by its words; else a crash, road work
     * (any roadwork, or an incident filed as maintenance or construction) or other (slides, washouts, trees).
     */
    fun kindOf(name: String?, style: String?, description: String, roadwork: Boolean): IncidentKind {
        val n = name.orEmpty().lowercase(Locale.US)
        val d = description.lowercase(Locale.US)
        return when {
            style == "icon_feed_s1" || style == "icon_feed_c1" || ROAD_SHUT.containsMatchIn(d) -> IncidentKind.CLOSURE
            roadwork -> IncidentKind.ROADWORK
            "crash" in n || "accident" in n || "disabled" in n || "fire" in n -> IncidentKind.ACCIDENT
            "police" in n -> IncidentKind.POLICE
            "construction" in n || "maintenance" in n || "work" in n || "paving" in n -> IncidentKind.ROADWORK
            else -> IncidentKind.OTHER
        }
    }

    /** "The northbound road is closed. The southbound road is closed." -> Northbound and Southbound. */
    fun closedDirections(description: String): Set<String> =
        ONE_WAY_SHUT.findAll(description.lowercase(Locale.US)).mapNotNull { directionWord(it.groupValues[1]) }.toSortedSet()

    private val ROUTE_AFTER_ON = Regex("""\bon\s+([A-Z]{1,4}[- ]?\d+[A-Z]?(?:/\d+)?)""")

    /** "Road Washed Out on CR-22/8 (Middle Ridge Rd) in Corley" -> "CR-22/8"; "on US-119S from Mile Marker" -> "US-119S". */
    fun routeIn(description: String): String? = ROUTE_AFTER_ON.find(description)?.groupValues?.get(1)

    /** "US-119S" -> "US-119"; "I-77N" -> "I-77"; "CR-22/8" stays. */
    fun roadOf(route: String): String = if (directionOfRoute(route) != null) route.dropLast(1) else route

    /** "US-50W" -> "Westbound". */
    fun directionOfRoute(route: String): String? =
        if (route.length >= 2 && route.last() in "NSEW" && route[route.length - 2].isDigit()) directionWord(route.last().toString()) else null

    private fun directionWord(raw: String): String? = when (raw.trim().lowercase(Locale.US).removeSuffix("bound")) {
        "n", "nb", "north" -> "Northbound"
        "s", "sb", "south" -> "Southbound"
        "e", "eb", "east" -> "Eastbound"
        "w", "wb", "west" -> "Westbound"
        else -> null
    }

    private val UPDATED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)

    /** "Oct 11, 2026 3:14 PM" (West Virginia time) -> POSIX millis. */
    fun easternTime(text: String): Long? = try {
        LocalDateTime.parse(text.trim(), UPDATED_FORMAT).atZone(EASTERN).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    private val PAGE = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val COUNTY = Regex("""^\[[A-Z]{2,4}]\s*""")
    private val SIGN_ROUTE = Regex("""\b([A-Z]{1,3})-(\d{1,3})\b""")
    private val SIGN_DIRECTION = Regex("""\b([NSEW]B)\b""")
    private val SPAN = Regex("""<span\b.*?</span>""", RegexOption.DOT_MATCHES_ALL)

    private fun sign(p: Placemark): MessageSign? {
        val id = p.id ?: return null
        val at = p.at ?: return null
        val name = p.name?.replace(COUNTY, "")?.trim()?.ifEmpty { null } ?: "Message sign"
        val pages = PAGE.findAll(p.description).map { it.groupValues[1] }.toList().ifEmpty { listOf(p.description) }
            .map { page -> page.split(BREAK).map(::plain).filter { it.isNotEmpty() } }
            .filter { lines -> lines.isNotEmpty() && !(lines.size == 1 && lines[0].equals("No Message", ignoreCase = true)) }
        return MessageSign(
            id = TrafficSource.WV_511.idPrefix + id,
            name = name,
            lat = at.lat,
            lon = at.lon,
            lines = pages.flatMapIndexed { i, lines -> if (i == 0) lines else listOf("") + lines },
            roadway = SIGN_ROUTE.find(name)?.value,
            direction = SIGN_DIRECTION.find(name)?.groupValues?.get(1)?.let(::directionWord),
            source = TrafficSource.WV_511,
        )
    }

    private fun camera(m: Map<*, *>): TrafficCamera? {
        val id = (m["md5"] as? String)?.trim()?.takeIf { CAMERA_ID.matches(it) } ?: return null
        val lat = (m["start_lat"] as? String)?.toDoubleOrNull() ?: (m["start_lat"] as? Number)?.toDouble() ?: return null
        val lon = (m["start_lng"] as? String)?.toDoubleOrNull() ?: (m["start_lng"] as? Number)?.toDouble() ?: return null
        if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val html = m["description"] as? String ?: ""
        // "<div id="camDescription">[BER]I-81 @ 0.5<span ...>West Virginia DOT</span></div>"
        val described = plain(html.replace(SPAN, " ")).replace(COUNTY, "")
        val title = (m["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val streams = "STREAMING:1" in html
        return TrafficCamera(
            id = TrafficSource.WV_511.idPrefix + id,
            name = described.ifEmpty { title ?: "Camera $id" },
            lat = lat,
            lon = lon,
            imageUrl = null,
            roadway = title,
            direction = null,
            disabled = !streams,
            source = TrafficSource.WV_511,
            videoVia = if (streams) playerUrl(id) else null,
        )
    }

    /** The JSON object that opens at [start], to its matching brace (strings and escapes skipped); null if none. */
    private fun objectAt(text: String, start: Int): String? {
        if (start < 0) return null
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
            i++
        }
        return null
    }

    /** The incidents and roadwork, the signs and the cameras, through [fetch] (WV511's toggle). */
    class Client(private val fetch: (String) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.WV_511

        override fun events(now: Long): List<Incident> =
            parseIncidents(fetch(INCIDENTS_URL).decodeToString()) + parseRoadwork(fetch(ROADWORK_URL).decodeToString())

        override fun cameras(): List<TrafficCamera> = parseCameras(fetch(CAMERAS_URL).decodeToString())

        override fun signs(): List<MessageSign> = parseSigns(fetch(SIGNS_URL).decodeToString())
    }
}
