package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Kentucky's KYTC, from the ArcGIS feature services of the cabinet's own organisation (services2.arcgis.com, the
 * layers behind its GoKY map), queried whole (`where=1=1`), no key and nothing about the user in the request:
 *
 * - the last 24 hours' incident reports (`kytc_incidents_reports_24hr`): work zones, crashes, hazards and blocking
 *   incidents, each at a point. A report still in effect has its `EndDate` moved up to the layer's latest capture
 *   every few minutes; one that has ended keeps its last, so only those within [ACTIVE_WINDOW_MILLIS] of the newest
 *   `EndDate` in the answer are read (on 2026-10-14, 257 of 385 rows);
 * - the road closures (`KYTC_Road_Closures`): lines with a start and an end, read at their middle while in effect
 *   (10 of 35 that night); a report with the same event id as a closure is that closure, and goes;
 * - the cameras (`trafficCamerasCur_Prd`): 256, 247 of them Kentucky's (the rest are Indiana's around Louisville,
 *   which 511IN covers), with stills on TRIMARC's server.
 *
 * KYTC's message sign layer (`dmsSigns_2020`) is not read: it hadn't changed since August 2025 when the feed probe
 * looked on 2026-10-14, so its messages are long out of date. Seen in the feed probe on 2026-10-14.
 */
object Kentucky {
    private const val BASE = "https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services"
    private const val QUERY = "query?where=1%3D1&outFields=*&returnGeometry=false&resultRecordCount=2000&f=json"
    const val INCIDENTS_URL: String = "$BASE/kytc_incidents_reports_24hr/FeatureServer/0/$QUERY"
    const val CLOSURES_URL: String =
        "$BASE/KYTC_Road_Closures/FeatureServer/0/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&resultRecordCount=2000&f=json"
    const val CAMERAS_URL: String = "$BASE/trafficCamerasCur_Prd/FeatureServer/0/$QUERY"

    /** A report whose `EndDate` lags the newest by more than this has ended (the layer refreshes every few minutes). */
    const val ACTIVE_WINDOW_MILLIS: Long = 30 * 60_000L

    /** TRIMARC's stills, https://www.trimarc.org/images/.../CCTV_05_65_1267.jpg; anything else is not followed. */
    private const val STILL_HOST = "www.trimarc.org"

    /**
     * The incident reports still in effect, and the closures in effect at [now] (epoch ms), the closures first. A
     * report with a closure's event id is left out: the closure stands for it.
     *
     * @throws IllegalArgumentException when either isn't the service's answer.
     */
    fun parseEvents(incidentsJson: String, closuresJson: String, now: Long = System.currentTimeMillis()): List<Incident> {
        val closures = parseClosures(closuresJson, now)
        val closed = closures.map { it.id.removePrefix(TrafficSource.KY_KYTC.idPrefix) }.toSet()
        return closures + parseIncidents(incidentsJson).filter { it.id.removePrefix(TrafficSource.KY_KYTC.idPrefix) !in closed }
    }

    /** The incident reports still in effect: those within [ACTIVE_WINDOW_MILLIS] of the newest `EndDate`. */
    fun parseIncidents(json: String): List<Incident> {
        val rows = rows(json)
        val newest = rows.mapNotNull { it.attributes.number("EndDate") }.maxOrNull() ?: return emptyList()
        return rows.filter { (it.attributes.number("EndDate") ?: 0.0) >= newest - ACTIVE_WINDOW_MILLIS }.mapNotNull { incident(it.attributes) }
    }

    /** The closures in effect at [now]: started, and not past their `endtime` (none means until further notice). */
    fun parseClosures(json: String, now: Long = System.currentTimeMillis()): List<Incident> = rows(json).mapNotNull { r ->
        val a = r.attributes
        val start = a.number("starttime")?.toLong()
        val end = a.number("endtime")?.toLong()
        if ((start != null && start > now) || (end != null && end <= now)) return@mapNotNull null
        closure(a, r.geometry)
    }

    /** Kentucky's cameras: those KYTC marks as the state's, each with its TRIMARC still. */
    fun parseCameras(json: String): List<TrafficCamera> = rows(json).mapNotNull { camera(it.attributes) }

    private class Row(val attributes: Map<*, *>, val geometry: Map<*, *>?)

    private fun rows(json: String): List<Row> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        } as? Map<*, *>
        if (root?.get("error") != null) throw IllegalArgumentException("sent an error: ${(root["error"] as? Map<*, *>)?.get("message")}")
        val features = root?.get("features") as? List<*> ?: throw IllegalArgumentException("sent no features")
        return features.mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            Row(m["attributes"] as? Map<*, *> ?: return@mapNotNull null, m["geometry"] as? Map<*, *>)
        }
    }

    private fun incident(a: Map<*, *>): Incident? {
        val id = a.text("EventId") ?: a.number("OBJECTID")?.toLong()?.toString() ?: return null
        val lat = a.number("Lat") ?: return null
        val lon = a.number("Long") ?: return null
        if (!valid(lat, lon)) return null
        val description = a.text("Description")?.replace(SPACES, " ") ?: a.text("Source_Type") ?: "Incident"
        val kind = kindOf(a.text("KYTC_Type"), a.text("Source_Type"), description)
        return Incident(
            id = TrafficSource.KY_KYTC.idPrefix + id,
            kind = kind,
            roadway = roadOf(a.text("RoadName"), description),
            direction = directionIn(description),
            description = description,
            location = GeoPoint(lat, lon),
            severity = null,
            lanesStatus = if (kind == IncidentKind.CLOSURE) "All lanes closed" else null,
            updatedAt = a.number("updateTS")?.toLong()?.takeIf { it > 0 },
            source = TrafficSource.KY_KYTC,
        )
    }

    private fun closure(a: Map<*, *>, geometry: Map<*, *>?): Incident? {
        val id = a.text("id") ?: a.number("OBJECTID")?.toLong()?.toString() ?: return null
        val at = middleOf(geometry) ?: return null
        val description = a.text("description")?.replace(SPACES, " ") ?: "Road closed"
        return Incident(
            id = TrafficSource.KY_KYTC.idPrefix + id,
            kind = IncidentKind.CLOSURE,
            roadway = roadOf(a.text("street"), description),
            direction = when (a.text("direction")?.uppercase(Locale.US)) {
                "BOTH_DIRECTIONS" -> "Both Directions"
                else -> directionIn(description)
            },
            description = description,
            location = at,
            severity = null,
            lanesStatus = "All lanes closed",
            updatedAt = null,
            source = TrafficSource.KY_KYTC,
        )
    }

    private fun camera(a: Map<*, *>): TrafficCamera? {
        if (a.text("state")?.equals("Kentucky", ignoreCase = true) != true) return null
        val lat = a.number("latitude") ?: return null
        val lon = a.number("longitude") ?: return null
        if (!valid(lat, lon)) return null
        val still = a.text("snapshot")?.let(::stillOf)
        val id = a.text("id") ?: a.text("name") ?: still?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: a.number("OBJECTID")?.toLong()?.toString() ?: return null
        val name = a.text("description") ?: a.text("name") ?: "Camera $id"
        return TrafficCamera(
            id = TrafficSource.KY_KYTC.idPrefix + id,
            name = name,
            lat = lat,
            lon = lon,
            imageUrl = still,
            roadway = a.text("highway")?.let { roadIn(it) ?: it } ?: roadIn(name),
            direction = a.text("direction")?.let(::directionWord),
            disabled = a.text("status")?.equals("Offline", ignoreCase = true) == true || still == null,
            source = TrafficSource.KY_KYTC,
        )
    }

    /** A TRIMARC still over https (the list still has a few http links to the same files), or null for any other host. */
    fun stillOf(url: String): String? = try {
        val u = URI(url.trim())
        if ((u.scheme == "https" || u.scheme == "http") && u.host == STILL_HOST && u.rawQuery == null &&
            u.path.lowercase(Locale.US).endsWith(".jpg")
        ) {
            URI("https", u.host, u.path, null).toASCIIString()
        } else {
            null
        }
    } catch (e: URISyntaxException) {
        null
    }

    /**
     * A closure by its words, whatever KYTC filed it under ("Roadway blocked due to emergency repairs", "... is closed
     * to motorists"); a blocked lane or shoulder is not one. Then crashes, police activity and road work by KYTC's
     * own types; disabled vehicles, debris and the rest are other.
     */
    fun kindOf(kytcType: String?, sourceType: String?, description: String): IncidentKind {
        val type = kytcType.orEmpty().lowercase(Locale.US)
        val source = sourceType.orEmpty().lowercase(Locale.US)
        return when {
            CLOSED.containsMatchIn(description) -> IncidentKind.CLOSURE
            "crash" in type || "crash" in source -> IncidentKind.ACCIDENT
            "police" in source -> IncidentKind.POLICE
            "work zone" in type || "road work" in source || "construction" in source || "maintenance" in source -> IncidentKind.ROADWORK
            else -> IncidentKind.OTHER
        }
    }

    private val CLOSED = Regex(
        """\b(?:road|roadway|bridge|ramp|street)\s+(?:is\s+)?(?:closed|blocked)\b(?!\s+(?:lane|shoulder))|""" +
            """\b(?:is|are)\s+(?:closed|blocked)\b[^.]{0,40}?\bto\s+(?:all\s+)?(?:traffic|motorists|vehicles)|""" +
            """\bboth\s+directions\s+(?:are\s+)?(?:closed|blocked)\b|\broad\s+closed\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ROUTE = Regex("""\b(I|US|KY)[- ]?(\d{1,4})(?:[- ]?[A-Z]\b)?""")
    private val BOUND = Regex("""\b(north|south|east|west)bound\b""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    /** "I 64" -> "I-64"; "KY-56" -> "KY 56"; "US 60-X/Bridge Street" -> "US 60". */
    fun roadIn(text: String): String? {
        val m = ROUTE.find(text.uppercase(Locale.US)) ?: return null
        val (kind, number) = m.destructured
        return if (kind == "I") "I-$number" else "$kind $number"
    }

    /**
     * The route the road name names ("I-71 NB REST AREA" -> "I-71"), else the road name in words ("Bristow Rd"; the
     * routes a description goes on to name are often the detour's), else the description's first route.
     */
    private fun roadOf(roadName: String?, description: String): String? =
        roadName?.let(::roadIn) ?: roadName?.let(::words) ?: roadIn(description)

    /** "S JEFFERSON ST" -> "S Jefferson St". */
    private fun words(name: String): String =
        name.lowercase(Locale.US).split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase(Locale.US) } }

    /** "Both directions ..." or the first "Northbound" a description names. */
    fun directionIn(description: String): String? = when {
        "both directions" in description.lowercase(Locale.US) -> "Both Directions"
        else -> BOUND.find(description)?.let { directionWord(it.groupValues[1]) }
    }

    private fun directionWord(raw: String): String? = when (raw.trim().lowercase(Locale.US).removeSuffix("bound")) {
        "n", "north" -> "Northbound"
        "s", "south" -> "Southbound"
        "e", "east" -> "Eastbound"
        "w", "west" -> "Westbound"
        else -> null
    }

    /** The point halfway along a closure's line (its first path), or null without one. */
    private fun middleOf(geometry: Map<*, *>?): GeoPoint? {
        val path = ((geometry?.get("paths") as? List<*>)?.firstOrNull() as? List<*>)?.mapNotNull { p ->
            val xy = p as? List<*> ?: return@mapNotNull null
            val x = (xy.getOrNull(0) as? Number)?.toDouble() ?: return@mapNotNull null
            val y = (xy.getOrNull(1) as? Number)?.toDouble() ?: return@mapNotNull null
            if (valid(y, x)) GeoPoint(y, x) else null
        }.orEmpty()
        if (path.isEmpty()) return null
        if (path.size == 1) return path[0]
        val lengths = path.zipWithNext { a, b -> Math.hypot(b.lat - a.lat, (b.lon - a.lon) * Math.cos(Math.toRadians(a.lat))) }
        var left = lengths.sum() / 2
        for ((i, length) in lengths.withIndex()) {
            if (left <= length && length > 0) {
                val t = left / length
                val a = path[i]
                val b = path[i + 1]
                return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            left -= length
        }
        return path.last()
    }

    private fun valid(lat: Double, lon: Double): Boolean = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    private fun Map<*, *>.number(key: String): Double? = (this[key] as? Number)?.toDouble()

    private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** The incident reports and closures, and the cameras, through [fetch] (KYTC's toggle). No signs (see above). */
    class Client(private val fetch: (String) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.KY_KYTC

        override fun events(now: Long): List<Incident> =
            parseEvents(fetch(INCIDENTS_URL).decodeToString(), fetch(CLOSURES_URL).decodeToString(), now)

        override fun cameras(): List<TrafficCamera> = parseCameras(fetch(CAMERAS_URL).decodeToString())

        override fun signs(): List<MessageSign> = emptyList()
    }
}
