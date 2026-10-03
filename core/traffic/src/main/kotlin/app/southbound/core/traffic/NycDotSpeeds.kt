package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * NYC DOT's traffic speed sensors, from NYC Open Data (dataset i4gi-tjb9):
 * speed and travel time per sensor link, mostly on highways and major
 * arterials in the five boroughs. The dataset is a time series, so we ask
 * for the newest rows and keep the latest reading per link. No key, no
 * location: the request is the same for everyone.
 */
object NycDotSpeeds {
    /** Enough rows for every link's latest reading, with a few readings' slack. */
    private const val LIMIT = 1200

    const val URL: String = "https://data.cityofnewyork.us/resource/i4gi-tjb9.json" +
        "?%24select=id,speed,travel_time,status,data_as_of,link_id,link_points,link_name,borough" +
        "&%24order=data_as_of%20DESC&%24limit=$LIMIT"

    private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

    // "2026-09-25T10:51:04.000"
    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Parses the Socrata rows (strings throughout) into one [SpeedLink] per
     * link, the newest reading winning. Links with no usable shape or speed
     * are dropped; readings older than [maxAgeMillis] before [now] too, since
     * a sensor that stopped reporting says nothing about traffic now.
     */
    fun parse(json: String, now: Long = System.currentTimeMillis(), maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS): List<SpeedLink> {
        val rows = MiniJson.parse(json) as? List<*> ?: throw IllegalArgumentException("NYC DOT sent no row list")
        val byLink = LinkedHashMap<String, SpeedLink>()
        for (row in rows) {
            val m = row as? Map<*, *> ?: continue
            val link = speedLink(m) ?: continue
            if (link.asOf != null && now - link.asOf > maxAgeMillis) continue
            val have = byLink[link.id]
            if (have == null || (link.asOf ?: 0) > (have.asOf ?: 0)) byLink[link.id] = link
        }
        return byLink.values.toList()
    }

    private fun speedLink(m: Map<*, *>): SpeedLink? {
        val id = m.text("link_id") ?: return null
        val speed = m.text("speed")?.toDoubleOrNull() ?: return null
        if (speed < 0) return null
        val points = parseLinkPoints(m.text("link_points") ?: return null)
        if (points.size < 2) return null
        return SpeedLink(
            id = id,
            name = m.text("link_name"),
            borough = m.text("borough"),
            speedMph = speed,
            travelTimeSeconds = m.text("travel_time")?.toDoubleOrNull()?.toInt(),
            points = points,
            asOf = parseDate(m.text("data_as_of")),
        )
    }

    /**
     * "40.74047,-74.009251 40.74137,-74.00893 ..." into points. The field is
     * hand-maintained and sometimes holds a bare latitude or a stray token;
     * those are skipped.
     */
    fun parseLinkPoints(text: String): List<GeoPoint> =
        text.trim().split(Regex("\\s+")).mapNotNull { token ->
            val parts = token.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in 40.0..41.5 || lon !in -75.0..-73.0) return@mapNotNull null
            GeoPoint(lat, lon)
        }

    fun parseDate(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(text.trim(), DATE).atZone(NEW_YORK).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun Map<*, *>.text(key: String): String? = this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** HANDOFF: the open-data copy has lagged by days; anything older than this is not "live". */
    const val DEFAULT_MAX_AGE_MILLIS: Long = 2 * 60 * 60 * 1000L
}
