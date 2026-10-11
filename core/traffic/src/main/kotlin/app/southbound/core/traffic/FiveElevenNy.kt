package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 511NY's events feed (https://511ny.org/developers/doc): every incident,
 * closure, work zone and special event in the state in one document. Pulled
 * whole, so the request says nothing about where you are; the key is the
 * user's own, from Settings. 10 calls a minute are allowed; we make one every
 * few minutes.
 */
object FiveElevenNy {
    private const val BASE = "https://511ny.org/api/getevents"

    fun eventsUrl(key: String): String = "$BASE?key=${URLEncoder.encode(key, "UTF-8")}&format=json"

    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

    // "10/02/2026 10:15:00 PM", with or without a leading zero.
    private val DATE = DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a", Locale.US)

    /**
     * Parses the events document: a JSON array of objects with PascalCase
     * fields. Rows without a position are skipped; unknown fields ignored.
     *
     * @throws MiniJson.JsonException when the document isn't JSON.
     * @throws IllegalArgumentException when it is JSON but not an event list.
     */
    fun parseEvents(json: String): List<Incident> {
        val root = MiniJson.parse(json)
        // 511NY answers errors as an object, e.g. a bad key.
        val rows = root as? List<*> ?: throw IllegalArgumentException(errorMessage(root))
        return rows.mapNotNull { row -> (row as? Map<*, *>)?.let(::incident) }
    }

    private fun errorMessage(root: Any?): String {
        val m = root as? Map<*, *>
        val text = (m?.get("Message") ?: m?.get("message") ?: m?.get("error"))?.toString()
        return if (text.isNullOrBlank()) "511NY sent no event list" else "511NY: $text"
    }

    private fun incident(m: Map<*, *>): Incident? {
        // The v2 API sends ID as a number; the older one as a string.
        val id = m.id() ?: return null
        val (lat, lon) = m.position() ?: return null
        val type = m.text("EventType")
        val subType = m.text("EventSubType")
        val lanes = m.text("LanesStatus") ?: m.text("LanesAffected")
        val description = m.text("Description") ?: subType ?: type ?: "Incident"
        val kind = kindOf(type, subType, lanes, description)
        return Incident(
            id = id,
            kind = if (m.flag("IsFullClosure")) IncidentKind.CLOSURE else kind,
            roadway = m.text("RoadwayName") ?: m.text("Roadway"),
            direction = m.direction(),
            description = description,
            location = GeoPoint(lat, lon),
            severity = m.text("Severity"),
            lanesStatus = lanes,
            updatedAt = m.date("LastUpdated") ?: m.date("Reported"),
        )
    }

    /** A date field: Unix time (the v2 API, seconds or milliseconds) or 511NY's text form. */
    private fun Map<*, *>.date(key: String): Long? = when (val v = this[key]) {
        is Number -> epochMillis(v.toDouble())
        is String -> v.trim().toDoubleOrNull()?.let(::epochMillis) ?: parseDate(v)
        else -> null
    }

    // Seconds until the year 33658; anything larger is already milliseconds. 0 and negatives mean "none".
    private fun epochMillis(v: Double): Long? = when {
        v <= 0 || v.isNaN() || v.isInfinite() -> null
        v < 1e12 -> (v * 1000).toLong()
        else -> v.toLong()
    }

    /** The feed's EventType values, with closures also found by their lane status or wording. */
    fun kindOf(type: String?, subType: String?, lanes: String?, description: String): IncidentKind {
        val t = type?.lowercase().orEmpty()
        val sub = subType?.lowercase().orEmpty()
        val lanesLower = lanes?.lowercase().orEmpty()
        val d = description.lowercase()
        return when {
            t.startsWith("closure") || sub.contains("closure") || sub.contains("closed") ||
                ("all lanes" in lanesLower && "closed" in lanesLower) ||
                d.startsWith("road closed") || d.startsWith("ramp closed") || " is closed" in d.replace(LANE_CLOSED, "") -> IncidentKind.CLOSURE
            // Before accidents: the feed files police activity under accidentsAndIncidents.
            isPolice(t) || isPolice(sub) -> IncidentKind.POLICE
            t.startsWith("accident") || sub.contains("accident") || sub.contains("crash") || sub.contains("disabled") -> IncidentKind.ACCIDENT
            t.startsWith("roadwork") || t.startsWith("construction") || sub.contains("work") || sub.contains("construction") -> IncidentKind.ROADWORK
            t.startsWith("special") || sub.contains("event") -> IncidentKind.EVENT
            // Wording alone only decides otherwise-unclassified events, so a crash with "police on scene" stays a crash.
            isPolice(d) -> IncidentKind.POLICE
            else -> IncidentKind.OTHER
        }
    }

    /** "The left lane is closed" shuts a lane, not the road. */
    private val LANE_CLOSED = Regex("""\blanes? (is|are) closed""")

    private val POLICE_WORDS = listOf("police", "law enforcement", "traffic stop", "speed enforcement", "radar")

    /** [lowercase] mentions police, law enforcement, a traffic stop or speed enforcement. */
    private fun isPolice(lowercase: String): Boolean = POLICE_WORDS.any { it in lowercase }

    /** 511NY's local Eastern timestamps to POSIX millis; null when unreadable. */
    fun parseDate(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(text.trim().replace(Regex("\\s+"), " "), DATE).atZone(NEW_YORK).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<*, *>.number(key: String): Double? = when (val v = this[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    // --- Cameras and message signs (Phase 8) ---
    //
    // Same key and same rules as the events feed: each list is fetched whole,
    // so the request says nothing about where the user is.
    //
    // Field names assumed, from 511NY's developer docs (the hosts couldn't be
    // reached when this was written, so the parsers also take the spellings
    // other IBI-run 511 sites use, and skip what they can't read):
    //
    // getcameras, an array of:
    //   ID (string or number), Name, Latitude, Longitude, Url (the still),
    //   VideoUrl (unused), Disabled, Blocked, RoadwayName, DirectionOfTravel.
    //   Also read: Id, Location (as the name), Roadway, Direction, ImageUrl,
    //   and Views[] {Url, Status} (the newer layout: the first view with an
    //   image, preferring a live one; the camera is offline when every view's
    //   Status is "Disabled", "Offline" or "Blocked").
    // getmessagesigns, an array of:
    //   ID, Name, Latitude, Longitude, Messages[] (lines, or one string with
    //   line breaks), RoadwayName / Roadway, DirectionOfTravel / Direction,
    //   LastUpdated. Also read: Message, Message2, Message3 as more lines.
    //   NTCIP MULTI tags in the text ([nl], [np], [jl3]...) become line and
    //   page breaks or are dropped; "NO_MESSAGE" counts as blank.

    private const val CAMERAS_BASE = "https://511ny.org/api/getcameras"
    private const val SIGNS_BASE = "https://511ny.org/api/getmessagesigns"
    private const val SITE = "https://511ny.org"
    private val OFF_STATUSES = setOf("disabled", "offline", "blocked")

    fun camerasUrl(key: String): String = "$CAMERAS_BASE?key=${URLEncoder.encode(key, "UTF-8")}&format=json"

    fun signsUrl(key: String): String = "$SIGNS_BASE?key=${URLEncoder.encode(key, "UTF-8")}&format=json"

    /**
     * Parses getcameras: rows without an id or a position are skipped,
     * missing text fields are null.
     *
     * @throws IllegalArgumentException when it isn't JSON or isn't a list (an error document).
     */
    fun parseCameras(json: String): List<TrafficCamera> = rowsOf(json, "camera").mapNotNull(::camera)

    /**
     * Parses getmessagesigns: rows without an id or a position are skipped;
     * a sign showing nothing has no lines.
     *
     * @throws IllegalArgumentException when it isn't JSON or isn't a list (an error document).
     */
    fun parseSigns(json: String): List<MessageSign> = rowsOf(json, "sign").mapNotNull(::sign)

    private fun rowsOf(json: String, what: String): List<Map<*, *>> {
        val root = MiniJson.parse(json)
        if (root !is List<*>) {
            val m = root as? Map<*, *>
            val text = (m?.get("Message") ?: m?.get("message") ?: m?.get("error"))?.toString()
            throw IllegalArgumentException(if (text.isNullOrBlank()) "511NY sent no $what list" else "511NY: $text")
        }
        return root.mapNotNull { it as? Map<*, *> }
    }

    private fun camera(m: Map<*, *>): TrafficCamera? {
        val id = m.id() ?: return null
        val (lat, lon) = m.position() ?: return null
        val views = (m["Views"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val live = views.filter { it.text("Status")?.lowercase(Locale.ROOT) !in OFF_STATUSES }
        val view = live.firstOrNull { it.text("Url") != null } ?: views.firstOrNull { it.text("Url") != null }
        val image = m.text("Url") ?: m.text("ImageUrl") ?: view?.text("Url")
        // The newer layout says offline per view: offline when every view is.
        val viewOff = views.isNotEmpty() && live.isEmpty()
        val roadway = m.text("RoadwayName") ?: m.text("Roadway")
        val direction = m.direction()
        return TrafficCamera(
            id = id,
            name = m.text("Name") ?: m.text("Location") ?: listOfNotNull(roadway, direction).joinToString(" ").ifEmpty { "Camera $id" },
            lat = lat,
            lon = lon,
            imageUrl = image?.let(::absoluteUrl),
            roadway = roadway,
            direction = direction,
            disabled = m.flag("Disabled") || m.flag("Blocked") || viewOff,
        )
    }

    private fun sign(m: Map<*, *>): MessageSign? {
        val id = m.id() ?: return null
        val (lat, lon) = m.position() ?: return null
        val roadway = m.text("RoadwayName") ?: m.text("Roadway")
        val direction = m.direction()
        val raw = when (val messages = m["Messages"]) {
            is List<*> -> messages.mapNotNull { (it as? String) ?: (it as? Map<*, *>)?.text("Message") }
            is String -> listOf(messages)
            else -> emptyList()
        } + listOfNotNull(m.text("Message"), m.text("Message2"), m.text("Message3"))
        return MessageSign(
            id = id,
            name = m.text("Name") ?: listOfNotNull(roadway, direction).joinToString(" ").ifEmpty { "Sign $id" },
            lat = lat,
            lon = lon,
            lines = signLines(raw),
            roadway = roadway,
            direction = direction,
            updatedAt = m.date("LastUpdated"),
        )
    }

    private val PAGE_BREAK = Regex("""\[np]""", RegexOption.IGNORE_CASE)
    private val LINE_BREAK = Regex("""\[nl\d*]|<br\s*/?>|\r\n|\r|\n""", RegexOption.IGNORE_CASE)

    // The formatting tags of NTCIP 1203 MULTI: justification, fonts, colours, flashing, spacing.
    private val MULTI_TAG = Regex("""\[/?(?:cb|cf|cr|fl|fo|g|hc|jl|jp|ms|mvc|pb|pt|sc|tr|f\d)[0-9a-z,]*]""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")
    private val PLACEHOLDERS = setOf("NO_MESSAGE", "NO MESSAGE", "BLANK", "N/A")

    /**
     * A sign's messages as display lines: MULTI [nl] and HTML <br> start a
     * line, [np] a page (an empty line between pages), other MULTI tags go,
     * HTML entities (&nbsp; &amp;, from the IBI sites) are decoded, spaces are
     * squeezed and placeholders such as NO_MESSAGE drop out. Each entry of
     * [messages] is one or more lines. Text stays as the sign has it.
     */
    fun signLines(messages: List<String>): List<String> {
        val lines = ArrayList<String>()
        for (message in messages) {
            for ((i, page) in message.split(PAGE_BREAK).withIndex()) {
                if (i > 0 && lines.isNotEmpty() && lines.last().isNotEmpty()) lines += ""
                page.split(LINE_BREAK)
                    .map { it.replace(MULTI_TAG, "").let(::decodeEntities).replace(SPACES, " ").trim() }
                    .filter { it.isNotEmpty() && it.uppercase(Locale.ROOT) !in PLACEHOLDERS }
                    .let { lines += it }
            }
        }
        while (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
        return lines
    }

    /** The few HTML entities the sign feeds use; anything else stays as it is. */
    private fun decodeEntities(text: String): String =
        if ('&' !in text) text
        else text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")

    /** Relative links are 511NY's own; plain http to its host is upgraded (Android blocks cleartext). */
    private fun absoluteUrl(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$SITE$url"
        url.startsWith("http://511ny.org", ignoreCase = true) || url.startsWith("http://www.511ny.org", ignoreCase = true) ->
            "https://" + url.substring("http://".length)
        else -> url
    }

    private fun Map<*, *>.direction(): String? =
        (text("DirectionOfTravel") ?: text("Direction"))?.takeUnless { it.equals("Unknown", ignoreCase = true) }

    private fun Map<*, *>.id(): String? = when (val v = this["ID"] ?: this["Id"] ?: this["id"]) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Long, is Int -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> null
    }

    private fun Map<*, *>.position(): Pair<Double, Double>? {
        val lat = number("Latitude") ?: return null
        val lon = number("Longitude") ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun Map<*, *>.flag(key: String): Boolean = when (val v = this[key]) {
        is Boolean -> v
        is String -> v.trim().equals("true", ignoreCase = true) || v.trim() == "1"
        is Number -> v.toInt() != 0
        else -> false
    }
}
