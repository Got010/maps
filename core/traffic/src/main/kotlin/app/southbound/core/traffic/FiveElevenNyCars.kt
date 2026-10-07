package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.util.Locale

/**
 * 511NY as it runs since its October 2026 move to the CARS platform (Castle Rock): 511ny.org's
 * own map reads these collections, each a GeoJSON FeatureCollection of the whole state, with no
 * key and no area in the request. The old developer API (511ny.org/api/getevents?key=...) now
 * answers with the new site's HTML page, and ny.ibi511.com with 503; [FiveElevenNy] still reads
 * that layout in case a keyed API comes back.
 *
 * Found by scripts/feed-probe.py in the site's configuration
 * (demo.511ny.org/configs/main.json: each map layer's "api" and "query") and its main script,
 * which fetches `{api}/map-features?{query}` and first asks `{api}/hash?{query}` to skip an
 * unchanged layer. The queries below are the site's own. Not a documented API: if 511NY changes
 * its site, the probe workflow shows what it sends now.
 *
 * Seen on 2026-10-07: incidents 16 events (47 KB), closures 209 (0.8 MB), construction 1058
 * (4.3 MB), signs 409 (0.4 MB), cameras 2717 (1.9 MB).
 */
object FiveElevenNyCars {
    const val API: String = "https://api-511x-nysdot.carsprogram.org"

    /** The event layers the app shows, with the query 511ny.org uses for each. */
    enum class EventLayer(val query: String) {
        INCIDENTS("eventClassifications=incidents"),

        /** Closures under way or starting within five minutes (the site's own window). */
        CLOSURES("eventClassifications=closures&maxBeginDateOffset=300000"),
        CONSTRUCTION("eventClassifications=construction&maxBeginDateOffset=300000"),
    }

    fun eventsUrl(layer: EventLayer): String = "$API/events/map-features?${layer.query}"

    fun eventsHashUrl(layer: EventLayer): String = "$API/events/hash?${layer.query}"

    const val SIGNS_URL: String = "$API/signs/map-features"
    const val SIGNS_HASH_URL: String = "$API/signs/hash"
    const val CAMERAS_URL: String = "$API/cameras/map-features"

    /** `{"hash":"..."}`, which changes whenever the layer's features do; null when unreadable. */
    fun parseHash(json: String): String? =
        ((runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *>)?.get("hash") as? String)?.takeIf { it.isNotBlank() }

    /**
     * Parses one event layer. Inactive events and features without a position are skipped. The
     * layer decides the kind first: everything in [EventLayer.CLOSURES] is a closure, construction
     * is roadwork unless it shuts the road, incidents are sorted by their wording
     * ([FiveElevenNy.kindOf]).
     *
     * @throws IllegalArgumentException when it isn't JSON or isn't a FeatureCollection.
     */
    fun parseEvents(json: String, layer: EventLayer): List<Incident> = features(json, "event").mapNotNull { event(it, layer) }

    /** Parses the message signs: every page's lines, a blank line between pages. */
    fun parseSigns(json: String): List<MessageSign> = features(json, "sign").mapNotNull(::sign)

    /** Parses the cameras; the still is each view's preview image (the view's own url is a video stream). */
    fun parseCameras(json: String): List<TrafficCamera> = features(json, "camera").mapNotNull(::camera)

    private class Feature(val properties: Map<*, *>, val point: GeoPoint)

    private fun features(json: String, what: String): List<Feature> {
        val root = MiniJson.parse(json) as? Map<*, *>
        val list = root?.get("features") as? List<*>
            ?: throw IllegalArgumentException("511NY sent no $what list")
        return list.mapNotNull { f ->
            val m = f as? Map<*, *> ?: return@mapNotNull null
            val props = m["properties"] as? Map<*, *> ?: return@mapNotNull null
            val point = firstPoint(m["geometry"] as? Map<*, *>) ?: return@mapNotNull null
            Feature(props, point)
        }
    }

    /** A Point's position, or a line's first point (where an event starts). */
    private fun firstPoint(geometry: Map<*, *>?): GeoPoint? {
        val coords = geometry?.get("coordinates") as? List<*> ?: return null
        val pair = when (geometry["type"]) {
            "Point" -> coords
            "LineString" -> coords.firstOrNull() as? List<*>
            "MultiLineString" -> (coords.firstOrNull() as? List<*>)?.firstOrNull() as? List<*>
            else -> null
        } ?: return null
        val lon = (pair.getOrNull(0) as? Number)?.toDouble() ?: return null
        val lat = (pair.getOrNull(1) as? Number)?.toDouble() ?: return null
        if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }

    private fun event(f: Feature, layer: EventLayer): Incident? {
        val p = f.properties
        if (p["active"] == false) return null
        val id = p.text("id") ?: return null
        val report = p["eventReport"] as? Map<*, *>
        val words = report?.get("eventDescription") as? Map<*, *>
        // "NY 86 eastbound: Road closed, because of road maintenance activities."
        val tooltip = p.text("tooltip") ?: p.text("title") ?: words?.text("descriptionHeader")
        val details = plainText(p.text("description") ?: words?.text("descriptionFull"))
        val description = listOfNotNull(tooltip, details).joinToString(" ").ifBlank { "Incident" }
        val headline = words?.text("headlinePhrase") // "Disabled Vehicle", "Road Closed"
        val lanes = lanesStatus(details)
        val wordingKind = FiveElevenNy.kindOf(null, headline, lanes, description)
        val kind = when (layer) {
            EventLayer.CLOSURES -> IncidentKind.CLOSURE
            EventLayer.CONSTRUCTION -> if (wordingKind == IncidentKind.CLOSURE) IncidentKind.CLOSURE else IncidentKind.ROADWORK
            EventLayer.INCIDENTS -> wordingKind
        }
        val roadway = p.text("route") ?: (report?.get("location") as? Map<*, *>)?.text("routeDesignator")
        return Incident(
            id = id,
            kind = kind,
            roadway = roadway,
            direction = directionIn(p.text("title") ?: tooltip, roadway),
            description = description,
            location = f.point,
            // "routine", "urgent", "critical"
            severity = p.text("eventType")?.replaceFirstChar { it.titlecase(Locale.US) },
            lanesStatus = lanes,
            updatedAt = (p["updated"] as? Number)?.toLong()?.takeIf { it > 0 },
        )
    }

    private val DIRECTION = Regex("""\b(north|south|east|west)bound\b""", RegexOption.IGNORE_CASE)

    /** "I-190 (Niagara Thruway) southbound: ..." -> "Southbound"; "both directions" too. */
    private fun directionIn(title: String?, roadway: String?): String? {
        val head = title?.substringBefore(':')?.let { if (roadway != null) it.removePrefix(roadway) else it } ?: return null
        if ("both directions" in head.lowercase(Locale.US)) return "Both Directions"
        return DIRECTION.find(head)?.value?.lowercase(Locale.US)?.replaceFirstChar { it.titlecase(Locale.US) }
    }

    /** "All lanes are closed" in the details becomes "All lanes closed", which marks a closure. */
    private fun lanesStatus(details: String?): String? {
        val d = details?.lowercase(Locale.US) ?: return null
        return when {
            "all lanes are closed" in d || "all lanes closed" in d || "the road is closed" in d -> "All lanes closed"
            else -> null
        }
    }

    private val TAG = Regex("<[^>]+>")
    private val SPACE = Regex("""\s+""")

    /** The description's HTML as one line of text. */
    fun plainText(html: String?): String? = html
        ?.replace(TAG, " ")
        ?.replace("&amp;", "&")?.replace("&nbsp;", " ")?.replace("&#39;", "'")?.replace("&quot;", "\"")
        ?.replace("&lt;", "<")?.replace("&gt;", ">")
        ?.replace(SPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun sign(f: Feature): MessageSign? {
        val p = f.properties
        val id = p.text("id") ?: return null
        val pages = (p["pages"] as? List<*>).orEmpty().mapNotNull { page ->
            (page as? Map<*, *>)?.get("lines") as? List<*>
        }.map { lines -> lines.mapNotNull { it as? String }.joinToString("\n") }
        // Pages become page breaks for FiveElevenNy.signLines, which also drops blanks and MULTI tags.
        val roadway = p.text("route")
        val direction = when (p.text("signFacingDirection")?.uppercase(Locale.US)) {
            "N" -> "Northbound"
            "S" -> "Southbound"
            "E" -> "Eastbound"
            "W" -> "Westbound"
            else -> null
        }
        return MessageSign(
            id = id,
            name = p.text("name") ?: p.text("title") ?: p.text("description") ?: "Sign ${p.text("idForDisplay") ?: id}",
            lat = f.point.lat,
            lon = f.point.lon,
            lines = FiveElevenNy.signLines(listOf(pages.joinToString("[np]"))),
            roadway = roadway,
            direction = direction,
            updatedAt = (p["updated"] as? Number)?.toLong()?.takeIf { it > 0 },
        )
    }

    private fun camera(f: Feature): TrafficCamera? {
        val p = f.properties
        if (p["public"] == false) return null
        val id = p.text("id") ?: return null
        val views = (p["views"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val working = views.filter { it["broken"] != true }
        val still = (working + views).firstNotNullOfOrNull { it.text("videoPreviewUrl") ?: it.text("imageUrl") }
        val roadway = p.text("route")?.takeUnless { it.equals("Unknown", ignoreCase = true) }
        return TrafficCamera(
            id = id,
            name = p.text("name") ?: p.text("tooltip") ?: "Camera $id",
            lat = f.point.lat,
            lon = f.point.lon,
            imageUrl = still?.takeIf { it.startsWith("https://") },
            roadway = roadway,
            direction = null,
            disabled = views.isNotEmpty() && working.isEmpty(),
        )
    }

    /** Text, or a whole number as text (camera ids are numbers). */
    private fun Map<*, *>.text(key: String): String? = when (val v = this[key]) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Long, is Int -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> null
    }
}
