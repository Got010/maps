package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.util.Locale

/**
 * Maryland's CHART (MDOT SHA's traffic centre), from the exports its own site reads: one JSON document each for
 * the events, the message signs and the cameras of the whole state, `{"data":[...],"success":true}`, no key and
 * nothing about the user in the request. Seen on 2026-10-11 (scripts/feed-probe.py): 41 events (35 KB), 288 signs
 * (220 KB), 552 cameras (250 KB). Its cameras stream video only ([TrafficCamera.videoUrl]).
 */
object MarylandChart {
    private const val BASE = "https://chartexp1.sha.maryland.gov/CHARTExportClientService"
    const val EVENTS_URL: String = "$BASE/getEventMapDataJSON.do"
    const val SIGNS_URL: String = "$BASE/getDMSMapDataJSON.do"
    const val CAMERAS_URL: String = "$BASE/getCameraMapDataJSON.do"

    /**
     * The events: incidents (crashes are "Property Damage", "Personal Injury", "Fatality"; "Disabled In Roadway",
     * "Debris In Roadway"...) and CHART's own action events (a signal on flash), each at a point. Its lanes
     * ([lanes]) say when every lane is shut, which makes it a closure. The `closed` flag isn't read: it is the
     * event's own lifecycle in CHART (resolved), not the road's, and no probe has caught one set to tell.
     *
     * @throws IllegalArgumentException when it isn't CHART's export.
     */
    fun parseEvents(json: String): List<Incident> = rows(json).mapNotNull(::event)

    fun parseSigns(json: String): List<MessageSign> = rows(json).mapNotNull(::sign)

    fun parseCameras(json: String): List<TrafficCamera> = rows(json).mapNotNull(::camera)

    private fun rows(json: String): List<Map<*, *>> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        } as? Map<*, *>
        val data = root?.get("data") as? List<*> ?: throw IllegalArgumentException("sent no list")
        return data.mapNotNull { it as? Map<*, *> }
    }

    private fun event(m: Map<*, *>): Incident? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val type = m.text("incidentType")
        val description = m.text("description") ?: m.text("name") ?: type ?: "Incident"
        val direction = direction(m.text("direction"))
        val lanes = lanes(m["lanes"] as? List<*>, m.text("direction"))
        return Incident(
            id = TrafficSource.MD_CHART.idPrefix + id,
            kind = kindOf(type, description),
            roadway = routeIn(description),
            direction = direction,
            description = description,
            location = at,
            severity = null,
            lanesStatus = lanes,
            updatedAt = (m["createTime"] as? Number)?.toLong()?.takeIf { it > 0 },
            source = TrafficSource.MD_CHART,
        )
    }

    /** CHART's incident types by their words; an action event ("Other") stays other. */
    fun kindOf(type: String?, description: String): IncidentKind {
        val t = type.orEmpty().lowercase(Locale.US)
        return when {
            "police" in t -> IncidentKind.POLICE
            "collision" in t || "property damage" in t || "personal injury" in t || "fatal" in t || "disabled" in t -> IncidentKind.ACCIDENT
            "roadwork" in t || "road work" in t || "construction" in t || "maintenance" in t -> IncidentKind.ROADWORK
            "special event" in t -> IncidentKind.EVENT
            else -> FiveElevenNy.kindOf(null, null, null, description.lowercase(Locale.US)).takeUnless { it == IncidentKind.CLOSURE }
                ?: IncidentKind.OTHER
        }
    }

    /**
     * The traffic lanes an event closes in its own direction, from CHART's lane list (`laneType` "Traffic Lane",
     * `laneStatus` "closed"; shoulders and ramps don't count): "All lanes closed" when every one is, else
     * "1 of 3 lanes closed"; null when none is. When every one is but traffic is let through on an open lane of the
     * other direction (`laneTrafficFlowDirection` "bi-dir": a flagger on a two-lane road), the road isn't shut.
     */
    fun lanes(list: List<*>?, direction: String?): String? {
        val all = list.orEmpty().mapNotNull { it as? Map<*, *> }.filter { it.text("laneType").equals("Traffic Lane", ignoreCase = true) }
        val dir = direction?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        val own = all.filter { dir == null || it.text("laneDirection")?.lowercase(Locale.US) == dir }.ifEmpty { all }
        val closed = own.count { !it.isOpen() }
        return when {
            own.isEmpty() || closed == 0 -> null
            closed == own.size && all.any { it.isOpen() && it.text("laneTrafficFlowDirection").equals("bi-dir", ignoreCase = true) } ->
                "Lane closed, traffic shares the other direction's lane"
            closed == own.size -> "All lanes closed"
            else -> "$closed of ${own.size} lanes closed"
        }
    }

    private fun Map<*, *>.isOpen(): Boolean = !text("laneStatus").equals("closed", ignoreCase = true)

    private fun sign(m: Map<*, *>): MessageSign? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val description = m.text("description")
        return MessageSign(
            id = TrafficSource.MD_CHART.idPrefix + id,
            name = description ?: m.text("name") ?: "Sign $id",
            lat = at.lat,
            lon = at.lon,
            // MULTI: [nl] a new line, [np] a new page; FiveElevenNy.signLines drops the other tags.
            lines = FiveElevenNy.signLines(listOfNotNull(m.text("msgMulti"))),
            roadway = description?.let(::routeIn),
            direction = description?.let(::directionIn),
            updatedAt = (m["lastCachedDataUpdateTime"] as? Number)?.toLong()?.takeIf { it > 0 },
            source = TrafficSource.MD_CHART,
        )
    }

    private fun camera(m: Map<*, *>): TrafficCamera? {
        val id = m.text("id") ?: return null
        val at = m.point() ?: return null
        val host = m.text("cctvIp")?.lowercase(Locale.US)?.takeIf { it.endsWith(VIDEO_HOSTS) && LABEL.matches(it.removeSuffix(VIDEO_HOSTS)) }
        val name = m.text("name")?.takeUnless { n -> n.all(Char::isDigit) } ?: m.text("description") ?: "Camera"
        val prefix = when (m.text("routePrefix")) {
            "IS" -> "I-"
            "US" -> "US "
            "MD" -> "MD "
            else -> null
        }
        val number = (m["routeNumber"] as? Number)?.toInt()?.takeIf { it > 0 }
        return TrafficCamera(
            id = TrafficSource.MD_CHART.idPrefix + id,
            name = name,
            lat = at.lat,
            lon = at.lon,
            imageUrl = null,
            roadway = if (prefix != null && number != null) "$prefix$number" else null,
            direction = null,
            disabled = m.text("opStatus") == "COMM_FAILURE" || m.text("commMode") == "OFFLINE" || host == null,
            source = TrafficSource.MD_CHART,
            // The stream the site's own player opens: https://strmr5.sha.maryland.gov/rtplive/<id>/playlist.m3u8.
            videoUrl = host?.let { "https://$it/rtplive/$id/playlist.m3u8" },
        )
    }

    /** CHART's stream servers (strmr5.sha.maryland.gov): one label under this; anything else is not followed. */
    private const val VIDEO_HOSTS = ".sha.maryland.gov"
    private val LABEL = Regex("""[a-z0-9-]+""")

    private val ROUTE = Regex("""\b(I|US|MD|DE|DC|VA|PA)[- ]?(\d{1,3}[A-Z]?)\b""")

    /** "Incident @ I-795 SOUTH PRIOR TO EXIT 7", "I-95 South, past Ex 80 MD 543" -> "I-795", "I-95". */
    fun routeIn(text: String): String? {
        val m = ROUTE.find(text.uppercase(Locale.US)) ?: return null
        val (kind, number) = m.destructured
        return if (kind == "I") "I-$number" else "$kind $number"
    }

    private val DIRECTION_WORD = Regex("""\b(NORTH|SOUTH|EAST|WEST)(BOUND)?\b""", RegexOption.IGNORE_CASE)

    private fun directionIn(text: String): String? = DIRECTION_WORD.find(text)?.groupValues?.get(1)?.let(::direction)

    /** "South" -> "Southbound"; "N/A" and the like -> null. */
    fun direction(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.US)) {
        "north", "northbound" -> "Northbound"
        "south", "southbound" -> "Southbound"
        "east", "eastbound" -> "Eastbound"
        "west", "westbound" -> "Westbound"
        "both", "both directions" -> "Both Directions"
        else -> null
    }

    private fun Map<*, *>.point(): GeoPoint? {
        val lat = (this["lat"] as? Number)?.toDouble() ?: return null
        val lon = (this["lon"] as? Number)?.toDouble() ?: return null
        return if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
    }

    private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** The whole state's three exports, through [fetch] (CHART's toggle). */
    class Client(private val fetch: (String) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = TrafficSource.MD_CHART

        override fun events(now: Long): List<Incident> = parseEvents(fetch(EVENTS_URL).decodeToString())

        override fun cameras(): List<TrafficCamera> = parseCameras(fetch(CAMERAS_URL).decodeToString())

        override fun signs(): List<MessageSign> = parseSigns(fetch(SIGNS_URL).decodeToString())
    }
}
