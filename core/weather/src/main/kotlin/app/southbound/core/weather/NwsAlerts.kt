package app.southbound.core.weather

import app.southbound.core.network.MiniJson
import app.southbound.core.traffic.GeoPoint
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** How bad the National Weather Service says an alert is (its CAP `severity`). */
enum class AlertSeverity { EXTREME, SEVERE, MODERATE, MINOR, UNKNOWN }

/** One ring of an area, its first point repeated at the end or not. */
typealias Ring = List<GeoPoint>

/**
 * One active National Weather Service alert: a warning, watch, advisory or statement.
 * [polygons] is the storm-based area when the alert draws one; otherwise the alert covers
 * the zones in [zoneUrls] (counties or forecast zones), whose shapes [NwsZones] reads.
 */
data class WeatherAlert(
    val id: String,
    /** "Winter Storm Warning", "Flood Advisory". */
    val event: String,
    val headline: String?,
    val severity: AlertSeverity,
    /** "Immediate", "Expected", "Future". */
    val urgency: String?,
    /** "Albany; Schenectady". */
    val areaDesc: String,
    val description: String?,
    val instruction: String?,
    /** POSIX millis it starts (onset, else effective), or null. */
    val onset: Long?,
    /** POSIX millis it ends (ends, else expires), or null. */
    val ends: Long?,
    val senderName: String?,
    val polygons: List<Ring>,
    val zoneUrls: List<String>,
)

/**
 * Reads `https://api.weather.gov/alerts/active?area=NY,NJ,...,KS` (the nineteen covered states): every active alert in
 * every state Southbound covers ([STATES]), a GeoJSON FeatureCollection, no key and nothing about
 * where the driver is (the same request for every phone). Seen on 2026-10-07 (scripts/feed-probe.py):
 * New York had none (221 bytes); the whole country had 222, some with a Polygon, others with only
 * `affectedZones`.
 */
object NwsAlerts {
    /** The states Southbound covers (owner, October 2026: the same weather everywhere). */
    val STATES: List<String> = listOf("NY", "NJ", "CT", "PA", "MD", "DE", "DC", "OH", "IN", "IL", "MI", "WI", "MN", "IA", "MO", "ND", "SD", "NE", "KS")

    const val URL: String = "https://api.weather.gov/alerts/active?area=NY,NJ,CT,PA,MD,DE,DC,OH,IN,IL,MI,WI,MN,IA,MO,ND,SD,NE,KS"

    /** Zone shapes are read from this host only, whatever an alert names. */
    const val ZONE_PREFIX: String = "https://api.weather.gov/zones/"

    /**
     * Parses the alerts. Only real ("Actual") alerts and updates are kept, not tests,
     * exercises or cancellations; an alert with neither a polygon nor a zone is dropped.
     *
     * @throws IllegalArgumentException when it isn't a FeatureCollection.
     */
    fun parse(json: String): List<WeatherAlert> {
        val root = MiniJson.parse(json) as? Map<*, *>
        val features = root?.get("features") as? List<*> ?: throw IllegalArgumentException("NWS sent no alert list")
        return features.mapNotNull { (it as? Map<*, *>)?.let(::alert) }.distinctBy { it.id }
    }

    private fun alert(f: Map<*, *>): WeatherAlert? {
        val p = f["properties"] as? Map<*, *> ?: return null
        if (p.text("status")?.equals("Actual", ignoreCase = true) == false) return null
        if (p.text("messageType")?.equals("Cancel", ignoreCase = true) == true) return null
        val id = p.text("id") ?: f.text("id") ?: return null
        val event = p.text("event") ?: return null
        val polygons = Geometry.rings(f["geometry"] as? Map<*, *>)
        val zones = (p["affectedZones"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.takeIf { z -> z.startsWith(ZONE_PREFIX) } }
        if (polygons.isEmpty() && zones.isEmpty()) return null
        return WeatherAlert(
            id = id,
            event = event,
            headline = p.text("headline"),
            severity = when (p.text("severity")?.lowercase()) {
                "extreme" -> AlertSeverity.EXTREME
                "severe" -> AlertSeverity.SEVERE
                "moderate" -> AlertSeverity.MODERATE
                "minor" -> AlertSeverity.MINOR
                else -> AlertSeverity.UNKNOWN
            },
            urgency = p.text("urgency"),
            areaDesc = p.text("areaDesc") ?: event,
            description = p.text("description"),
            instruction = p.text("instruction"),
            onset = time(p.text("onset")) ?: time(p.text("effective")),
            ends = time(p.text("ends")) ?: time(p.text("expires")),
            senderName = p.text("senderName"),
            polygons = polygons,
            zoneUrls = zones,
        )
    }

    /** "2026-10-07T16:18:00-04:00" to POSIX millis; null when absent or unreadable. */
    fun time(text: String?): Long? = text?.let {
        try {
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
}

/** A zone's shape: `https://api.weather.gov/zones/forecast/NYZ052` (a GeoJSON Feature). */
object NwsZones {
    /** The zone's outer rings; empty when it has no shape. @throws IllegalArgumentException when it isn't JSON. */
    fun parse(json: String): List<Ring> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("NWS sent no zone")
        return Geometry.rings(root["geometry"] as? Map<*, *>)
    }
}

/** GeoJSON Polygon and MultiPolygon outer rings (holes are ignored: an alert area rarely has one). */
internal object Geometry {
    fun rings(geometry: Map<*, *>?): List<Ring> {
        val coords = geometry?.get("coordinates") as? List<*> ?: return emptyList()
        val polygons: List<*> = when (geometry["type"]) {
            "Polygon" -> listOf(coords)
            "MultiPolygon" -> coords
            "GeometryCollection" -> return (geometry["geometries"] as? List<*>).orEmpty().flatMap { rings(it as? Map<*, *>) }
            else -> return emptyList()
        }
        return polygons.mapNotNull { poly ->
            val outer = (poly as? List<*>)?.firstOrNull() as? List<*> ?: return@mapNotNull null
            outer.mapNotNull { pair ->
                val xy = pair as? List<*> ?: return@mapNotNull null
                val lon = (xy.getOrNull(0) as? Number)?.toDouble() ?: return@mapNotNull null
                val lat = (xy.getOrNull(1) as? Number)?.toDouble() ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
            }.takeIf { it.size >= 3 }
        }
    }
}
