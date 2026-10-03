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
        val lat = m.number("Latitude") ?: return null
        val lon = m.number("Longitude") ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        val id = m.text("ID") ?: return null
        val type = m.text("EventType")
        val subType = m.text("EventSubType")
        val lanes = m.text("LanesStatus") ?: m.text("LanesAffected")
        val description = m.text("Description") ?: subType ?: type ?: "Incident"
        return Incident(
            id = id,
            kind = kindOf(type, subType, lanes, description),
            roadway = m.text("RoadwayName"),
            direction = m.text("DirectionOfTravel"),
            description = description,
            location = GeoPoint(lat, lon),
            severity = m.text("Severity"),
            lanesStatus = lanes,
            updatedAt = parseDate(m.text("LastUpdated") ?: m.text("Reported")),
        )
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
                d.startsWith("road closed") || d.startsWith("ramp closed") || " is closed" in d -> IncidentKind.CLOSURE
            t.startsWith("accident") || sub.contains("accident") || sub.contains("crash") || sub.contains("disabled") -> IncidentKind.ACCIDENT
            t.startsWith("roadwork") || t.startsWith("construction") || sub.contains("work") || sub.contains("construction") -> IncidentKind.ROADWORK
            t.startsWith("special") || sub.contains("event") -> IncidentKind.EVENT
            else -> IncidentKind.OTHER
        }
    }

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
}
