package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Ohio's OHGO (ODOT's traveller information API, publicapi.ohgo.com): the incidents, the construction, the cameras
 * and the digital signs of the whole state, each one request (`page-all=true`). The API wants a key, free on
 * registration, which the owner enters in Settings ([app.southbound.core.network.ApiKey.OHGO]) and which travels
 * only to this host, as the `api-key` query parameter the API documents; nothing about the user goes with it.
 * Written against the API's own OpenAPI document (scripts/feed-probe.py, 2026-10-11; every field named below is
 * required there unless said otherwise): a result is `{"lastUpdated": ..., "results": [...]}`, a 401 without a key
 * `{"errorDescription": "API key required."}`.
 */
object Ohgo {
    private const val BASE = "https://publicapi.ohgo.com/api/v1"
    const val INCIDENTS_PATH: String = "$BASE/incidents"
    const val CONSTRUCTION_PATH: String = "$BASE/construction"
    const val CAMERAS_PATH: String = "$BASE/cameras"
    const val SIGNS_PATH: String = "$BASE/digital-signs"

    /** The request for a whole list with [key]. */
    fun url(path: String, key: String): String = "$path?page-all=true&api-key=" + URLEncoder.encode(key.trim(), "UTF-8")

    /**
     * The hosts a camera's snapshot may be on (ODOT's and OHGO's own); anything else in a `smallUrl`/`largeUrl` is not
     * followed. The API's document names no host, so these are ODOT's known ones; a camera on another is listed
     * without a picture.
     */
    private val STILL_HOSTS = listOf(".ohgo.com", ".dot.state.oh.us", ".ohio.gov")

    /**
     * The incidents: `category` (the type), `routeName`, `direction`, `location`, `description` and `roadStatus`
     * (open, closed or partially closed) at a point.
     *
     * @throws IllegalArgumentException when it isn't the API's answer (its error included).
     */
    fun parseIncidents(json: String): List<Incident> = results(json).let { r -> r.rows.mapNotNull { incident(it, r.updated) } }

    /** The construction: `category` (planned or unplanned), `status` (open, restricted or closed), `routeName`, `direction`. */
    fun parseConstruction(json: String): List<Incident> = results(json).let { r -> r.rows.mapNotNull { construction(it, r.updated) } }

    /** The cameras: one per site, the first view's larger snapshot as the still. */
    fun parseCameras(json: String): List<TrafficCamera> = results(json).rows.mapNotNull(::camera)

    /** The digital signs: `messages` (several when the sign flips through them). */
    fun parseSigns(json: String): List<MessageSign> = results(json).let { r -> r.rows.mapNotNull { sign(it, r.updated) } }

    private class Results(val rows: List<Map<*, *>>, val updated: Long?)

    private fun results(json: String): Results {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        } as? Map<*, *> ?: throw IllegalArgumentException("sent no result")
        root.text("errorDescription")?.let { throw IllegalArgumentException("refused: $it") }
        val rows = root["results"] as? List<*> ?: throw IllegalArgumentException("sent no results")
        return Results(rows.mapNotNull { it as? Map<*, *> }, root.text("lastUpdated")?.let(::instant))
    }

    private fun incident(m: Map<*, *>, updated: Long?): Incident? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val category = m.text("category")
        val description = m.text("description") ?: category ?: "Incident"
        val status = m.text("roadStatus")
        val kind = kindOf(category, description, status)
        return Incident(
            id = TrafficSource.OH_OHGO.idPrefix + id,
            kind = kind,
            roadway = m.text("routeName")?.let(::roadIn),
            direction = direction(m.text("direction")),
            description = listOfNotNull(description, m.text("location")?.let { "at $it" }).joinToString(" "),
            location = at,
            severity = null,
            lanesStatus = when {
                kind == IncidentKind.CLOSURE -> "All lanes closed"
                status?.contains("partial", ignoreCase = true) == true || status?.contains("restrict", ignoreCase = true) == true -> "Partially closed"
                else -> null
            },
            updatedAt = updated,
            source = TrafficSource.OH_OHGO,
        )
    }

    /** A closed road is a closure; otherwise the category's words ("Crash", "Disabled Vehicle", "Debris"...). */
    fun kindOf(category: String?, description: String, status: String?): IncidentKind {
        val s = status.orEmpty().lowercase(Locale.US)
        val c = category.orEmpty().lowercase(Locale.US)
        val d = description.lowercase(Locale.US)
        return when {
            s == "closed" || s.startsWith("closed") || c.contains("closure") || c.contains("road closed") -> IncidentKind.CLOSURE
            "crash" in c || "accident" in c || "collision" in c || "disabled" in c || "stall" in c -> IncidentKind.ACCIDENT
            "construction" in c || "work" in c || "maintenance" in c -> IncidentKind.ROADWORK
            "police" in c -> IncidentKind.POLICE
            "event" in c -> IncidentKind.EVENT
            else -> FiveElevenNy.kindOf(null, null, null, d).takeUnless { it == IncidentKind.CLOSURE } ?: IncidentKind.OTHER
        }
    }

    private fun construction(m: Map<*, *>, updated: Long?): Incident? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val status = m.text("status").orEmpty().lowercase(Locale.US)
        val closed = status.startsWith("closed")
        val description = m.text("description") ?: "Construction"
        return Incident(
            id = TrafficSource.OH_OHGO.idPrefix + id,
            kind = if (closed) IncidentKind.CLOSURE else IncidentKind.ROADWORK,
            roadway = m.text("routeName")?.let(::roadIn),
            direction = direction(m.text("direction")),
            description = listOfNotNull(description, m.text("location")?.let { "at $it" }).joinToString(" "),
            location = at,
            severity = m.text("category")?.takeIf { it.equals("unplanned", ignoreCase = true) }?.let { "Unplanned" },
            lanesStatus = when {
                closed -> "All lanes closed"
                status.startsWith("restrict") -> "Restricted"
                else -> null
            },
            updatedAt = updated,
            source = TrafficSource.OH_OHGO,
        )
    }

    private fun camera(m: Map<*, *>): TrafficCamera? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val views = (m["cameraViews"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val stills = views.mapNotNull { v -> (v.text("largeUrl") ?: v.text("smallUrl"))?.takeIf(::isStill) }
        val name = m.text("location") ?: m.text("description") ?: "Camera $id"
        val route = views.firstNotNullOfOrNull { it.text("mainRoute") }
        return TrafficCamera(
            id = TrafficSource.OH_OHGO.idPrefix + id,
            name = name,
            lat = at.lat,
            lon = at.lon,
            imageUrl = stills.firstOrNull(),
            roadway = (route ?: name).let(::roadIn),
            direction = views.singleOrNull()?.text("direction")?.let(::direction),
            disabled = stills.isEmpty(),
            source = TrafficSource.OH_OHGO,
        )
    }

    private fun isStill(url: String): Boolean = try {
        val u = URI(url)
        val host = u.host?.lowercase(Locale.US)
        u.scheme == "https" && host != null && STILL_HOSTS.any { host.endsWith(it) }
    } catch (e: URISyntaxException) {
        false
    }

    private fun sign(m: Map<*, *>, updated: Long?): MessageSign? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val messages = (m["messages"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
        val name = m.text("location") ?: "Sign $id"
        return MessageSign(
            id = TrafficSource.OH_OHGO.idPrefix + id,
            name = name,
            lat = at.lat,
            lon = at.lon,
            lines = FiveElevenNy.signLines(listOf(messages.joinToString("[np]"))),
            roadway = roadIn(name),
            direction = direction(name.substringBefore(" at ")),
            updatedAt = updated,
            source = TrafficSource.OH_OHGO,
        )
    }

    private val ROUTE = Regex("""\b(I|US|SR|OH)[- ]?(\d{1,3}[A-Z]?)\b""")

    /** "I-71" stays; "SR-315" and "OH 315" -> "OH 315"; "US-23" -> "US 23"; a plain name stays. */
    fun roadIn(text: String): String? {
        val m = ROUTE.find(text.uppercase(Locale.US)) ?: return text.trim().takeIf { it.isNotEmpty() && it.length <= 40 }
        val (kind, number) = m.destructured
        return when (kind) {
            "I" -> "I-$number"
            "SR" -> "OH $number"
            else -> "$kind $number"
        }
    }

    /** "Northbound", "NB", "North", "Both Directions"; a camera's "PTZ" or "Various" -> null. */
    fun direction(raw: String?): String? {
        val r = raw?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        if ("both" in r) return "Both Directions"
        return Ibi511.direction(r) ?: Regex("""\b([nsew])b\b|\b(north|south|east|west)(?:bound)?\b""").find(r)?.let { Ibi511.direction(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
    }

    private fun instant(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        try {
            java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        } catch (e2: DateTimeParseException) {
            null
        }
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

    /** The four lists with the user's [key] (null: no key saved, so nothing is asked and the feed says so). */
    class Client(private val fetch: (String) -> ByteArray, private val key: () -> String?) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.OH_OHGO

        private fun get(path: String): String {
            val k = key()?.trim()?.takeIf { it.isNotEmpty() } ?: throw IOException("OHGO needs your key: Settings > Live data > OHGO key")
            return fetch(url(path, k)).decodeToString()
        }

        override fun events(now: Long): List<Incident> = parseIncidents(get(INCIDENTS_PATH)) + parseConstruction(get(CONSTRUCTION_PATH))

        override fun cameras(): List<TrafficCamera> = parseCameras(get(CAMERAS_PATH))

        override fun signs(): List<MessageSign> = parseSigns(get(SIGNS_PATH))
    }
}
