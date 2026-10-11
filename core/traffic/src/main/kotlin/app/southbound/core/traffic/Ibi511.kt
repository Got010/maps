package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * The 511 sites IBI Group runs for Pennsylvania (511PA) and Connecticut (CTroads), read the way their own map and
 * list pages read them, with no key and no area in the request:
 * - `/map/mapIcons/<layer>`: every item of a map layer and its position, `{"item2":[{"itemId":"399927",
 *   "location":[41.18,-76.01]}]}` (sent gzipped even unasked);
 * - `/List/GetData/<list>?query={"columns":[],"start":0,"length":100}`: the site's list pages, details without a
 *   position, at most 100 rows a page (`{"recordsTotal":366,"data":[...]}`).
 *
 * An event or a sign is a map icon joined to its list row by id; a camera row has its own position. Found by
 * scripts/feed-probe.py on 2026-10-11: 511PA had 366 events (227 roadwork, 119 closures, 20 incidents), 1,565
 * cameras and 1,170 signs; CTroads 347 cameras, 136 signs and no events that evening, so its event rows are read
 * as 511PA's (the same platform); 511WI (the same platform again) 490 cameras, each with a still and a video
 * stream, 167 signs, 367 roadwork icons and no incidents that morning, its lists in the same layout. Not a
 * documented API (the documented one, /api/v2/get/event, wants a developer key): if a site changes, the probe
 * workflow shows what it sends now.
 */
object Ibi511 {
    /** A map layer of events, the list its details are in, and the kind its items are (null: by their wording). */
    data class EventLayer(val icons: String, val list: String, val kind: IncidentKind?)

    /**
     * [videoHosts]: the end of the host names a camera row's `videoUrl` may name (511WI's cctv1.dot.wi.gov), for a
     * camera the card may show a frame of when its still fails; empty keeps the video out.
     */
    class Site(val source: TrafficSource, val base: String, val eventLayers: List<EventLayer>, val videoHosts: List<String> = emptyList()) {
        fun iconsUrl(layer: String): String = "$base/map/mapIcons/$layer"

        fun listUrl(list: String, start: Int): String =
            "$base/List/GetData/$list?query=" + URLEncoder.encode("""{"columns":[],"start":$start,"length":$PAGE}""", "UTF-8")
    }

    /** Rows a list page holds (the sites send no more however many are asked for). */
    const val PAGE = 100

    /** Pages read of one list at most: 511PA's cameras are 16. */
    const val MAX_PAGES = 30

    /** An event list is read again after this even when its map layers show nothing new (lanes reopen, times change). */
    const val LIST_MAX_AGE_MILLIS = 15 * 60_000L

    const val SIGNS_LAYER = "MessageSigns"
    const val SIGNS_LIST = "MessageSigns"
    const val CAMERAS_LIST = "Cameras"

    val PA = Site(
        TrafficSource.PA_511,
        "https://www.511pa.com",
        listOf(
            EventLayer("MajorRouteClosure", "Closures", IncidentKind.CLOSURE),
            EventLayer("OtherRouteClosure", "Closures", IncidentKind.CLOSURE),
            EventLayer("MajorRouteIncident", "Incidents", null),
            EventLayer("OtherRouteIncident", "Incidents", null),
            // Roadwork has no list of its own: "traffic" holds every event. Not read: the weather events layer
            // (its two items were in no list on 2026-10-11) and special events (none that day to check).
            EventLayer("ActiveRoadwork", "traffic", IncidentKind.ROADWORK),
        ),
    )

    val CT = Site(
        TrafficSource.CT_ROADS,
        "https://ctroads.org",
        listOf(
            EventLayer("Closures", "Closures", IncidentKind.CLOSURE),
            EventLayer("Incidents", "Incidents", null),
            EventLayer("Construction", "Construction", IncidentKind.ROADWORK),
        ),
    )

    val WI = Site(
        TrafficSource.WI_511,
        "https://511wi.gov",
        listOf(
            EventLayer("Incidents", "Incidents", null),
            EventLayer("Closures", "Closures", IncidentKind.CLOSURE),
            // "traffic" holds every event; its "Roadwork Closure" rows are marked isFullClosure, so they come out closures.
            EventLayer("Construction", "traffic", IncidentKind.ROADWORK),
        ),
        videoHosts = listOf(".dot.wi.gov"),
    )

    val SITES: List<Site> = listOf(PA, CT, WI)

    /** The bytes as text, gunzipped when the site sent gzip that wasn't asked for. */
    fun text(bytes: ByteArray): String {
        val plain = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }
        return plain.decodeToString()
    }

    /**
     * A map layer's items, id to position.
     *
     * @throws IllegalArgumentException when it isn't a map layer (an error page, say).
     */
    fun parseIcons(json: String): Map<String, GeoPoint> {
        val root = parse(json) as? Map<*, *>
        val items = root?.get("item2") as? List<*> ?: throw IllegalArgumentException("sent no map layer")
        val out = LinkedHashMap<String, GeoPoint>()
        for (item in items) {
            val m = item as? Map<*, *> ?: continue
            val id = m.text("itemId") ?: continue
            val location = m["location"] as? List<*> ?: continue
            val lat = (location.getOrNull(0) as? Number)?.toDouble() ?: continue
            val lon = (location.getOrNull(1) as? Number)?.toDouble() ?: continue
            point(lat, lon)?.let { out[id] = it }
        }
        return out
    }

    class Page(val total: Int?, val rows: List<Map<*, *>>)

    /** @throws IllegalArgumentException when it isn't a list page. */
    fun parsePage(json: String): Page {
        val root = parse(json) as? Map<*, *>
        val data = root?.get("data") as? List<*> ?: throw IllegalArgumentException("sent no list page")
        return Page((root["recordsTotal"] as? Number)?.toInt(), data.mapNotNull { it as? Map<*, *> })
    }

    /** A row's id, the one its map icon has: `DT_RowId`, else `id`. */
    fun rowId(row: Map<*, *>): String? = row.text("DT_RowId") ?: row.text("id")

    /**
     * An event row placed at its icon's position. A closure layer's items and a row marked `isFullClosure` are
     * closures, and nothing else is: the rows say so outright, while their wording ("The right shoulder is closed.")
     * would make closures of roadwork. Another layer's kind stands; an incident is sorted by what happened, the
     * words before " on " ("CRASH on PA-68 ...", "DISABLED VEHICLE on ...").
     */
    fun event(row: Map<*, *>, at: GeoPoint, layer: EventLayer, site: Site): Incident? {
        val id = rowId(row) ?: return null
        val description = FiveElevenNyCars.plainText(row.text("description")) ?: row.text("type") ?: "Incident"
        val full = row["isFullClosure"] == true
        val lanes = lanes(row.text("laneDescription"), full, description)
        val what = description.substringBefore(" on ", "").takeIf { it.length in 1..40 }
        val kind = when {
            full || layer.kind == IncidentKind.CLOSURE -> IncidentKind.CLOSURE
            layer.kind != null -> layer.kind
            what == null -> IncidentKind.OTHER
            else -> FiveElevenNy.kindOf(null, what, null, what).takeUnless { it == IncidentKind.CLOSURE } ?: IncidentKind.OTHER
        }
        return Incident(
            id = site.source.idPrefix + id,
            kind = kind,
            roadway = roadway(row.text("roadwayName")),
            direction = direction(row.text("direction")),
            description = description,
            location = at,
            // 511PA sends a number or nothing; only words mean something on a card.
            severity = row.text("severity")?.takeIf { s -> s.none(Char::isDigit) },
            lanesStatus = lanes,
            updatedAt = parseUpdated(row.text("lastUpdated")),
            source = site.source,
        )
    }

    /**
     * The lanes an event closes: all of them for a full closure, else the row's own lane words ("Right lane closed";
     * shoulders and "No lanes blocked" don't count), else the description's sentence about lanes.
     */
    fun lanes(laneDescription: String?, fullClosure: Boolean, description: String): String? {
        if (fullClosure) return "All lanes closed"
        val words = laneDescription?.trim()?.trimEnd('.')?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace(LANES_OF, "\$1 of \$2 lanes \$3")
        val own = words?.takeIf { closesLanes(it) }?.replaceFirstChar { it.titlecase(Locale.US) }
        return own ?: FiveElevenNyCars.lanesStatus(description)?.takeIf { closesLanes(it) }
    }

    private val LANE = Regex("""\blanes?\b""")

    // 511PA's "1 lane(s) of 2 blocked."
    private val LANES_OF = Regex("""(\d+) lane\(s\) of (\d+) (blocked|closed)""", RegexOption.IGNORE_CASE)

    private fun closesLanes(words: String): Boolean {
        val l = words.lowercase(Locale.US)
        return LANE.containsMatchIn(l) && ("closed" in l || "blocked" in l) && !l.startsWith("no ")
    }

    /** A camera row: its position is its own (`latLng`'s well-known text); its still the first working image. */
    fun camera(row: Map<*, *>, site: Site): TrafficCamera? {
        val id = rowId(row) ?: return null
        val geography = (row["latLng"] as? Map<*, *>)?.get("geography") as? Map<*, *>
        val at = wktPoint(geography?.text("wellKnownText")) ?: return null
        val images = (row["images"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val working = images.filter { it["disabled"] != true && it["blocked"] != true }
        val still = working.ifEmpty { images }.firstNotNullOfOrNull { it.text("imageUrl") }
        val video = working.filter { it["videoDisabled"] != true }.firstNotNullOfOrNull { it.text("videoUrl") }
            ?.takeIf { url -> site.videoHosts.any { videoHost(url)?.endsWith(it) == true } }
        return TrafficCamera(
            id = site.source.idPrefix + id,
            name = row.text("location") ?: row.text("nickname") ?: "Camera $id",
            lat = at.lat,
            lon = at.lon,
            imageUrl = still?.let { absolute(site, it) },
            roadway = row.text("roadway")?.takeUnless { it.equals("Unknown", ignoreCase = true) },
            direction = direction(row.text("direction")),
            disabled = working.isEmpty(),
            source = site.source,
            videoUrl = video,
        )
    }

    /** The host of an https playlist link, lower case; null for anything else. */
    private fun videoHost(url: String): String? = try {
        val u = java.net.URI(url)
        if (u.scheme == "https" && u.rawQuery == null && u.path.orEmpty().endsWith(".m3u8")) u.host?.lowercase(Locale.US) else null
    } catch (e: java.net.URISyntaxException) {
        null
    }

    /** A sign row placed at its icon's position; `message`, `message2` and `message3` are the pages it flips through. */
    fun sign(row: Map<*, *>, at: GeoPoint, site: Site): MessageSign? {
        val id = rowId(row) ?: return null
        val pages = listOfNotNull(row.text("message"), row.text("message2"), row.text("message3"))
        return MessageSign(
            id = site.source.idPrefix + id,
            name = row.text("name") ?: row.text("description") ?: "Sign $id",
            lat = at.lat,
            lon = at.lon,
            lines = FiveElevenNy.signLines(listOf(pages.joinToString("[np]"))),
            roadway = row.text("roadwayName"),
            direction = direction(row.text("direction")),
            updatedAt = parseUpdated(row.text("lastUpdated")),
            source = site.source,
        )
    }

    /** "n", "W", "BOTHDIR", "Eastbound" -> "Northbound", "Westbound", "Both Directions", "Eastbound"; "Unknown" -> null. */
    fun direction(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.US)) {
        "n", "nb", "north", "northbound" -> "Northbound"
        "s", "sb", "south", "southbound" -> "Southbound"
        "e", "eb", "east", "eastbound" -> "Eastbound"
        "w", "wb", "west", "westbound" -> "Westbound"
        "bothdir", "both", "both directions" -> "Both Directions"
        else -> null
    }

    /**
     * The road as a driver would say it: "(3001) MARKET ST / RIVER RD / MAIN ST" (511PA's local roads, a state
     * route number and every name it has along the way) -> "Market St"; "I-476" and mixed-case names stay.
     */
    fun roadway(name: String?): String? {
        val first = name?.replace(STATE_ROUTE, "")?.substringBefore(" / ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (first != first.uppercase(Locale.US)) return first
        return first.split(' ').joinToString(" ") { word ->
            if (word.any(Char::isDigit) || word in KEEP_UPPER) word else word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }
    }

    private val STATE_ROUTE = Regex("""^\(\d+\)\s*""")
    private val KEEP_UPPER = setOf("US", "PA", "SR", "CT", "NY", "NJ", "MD", "DE", "DC", "I", "N", "S", "E", "W", "NE", "NW", "SE", "SW", "II", "III")

    // "9/30/26, 6:39 PM": both sites write Eastern time.
    private val UPDATED = DateTimeFormatter.ofPattern("M/d/yy, h:mm a", Locale.US)
    private val EASTERN: ZoneId = ZoneId.of("America/New_York")
    private val SPACES = Regex("""\s+""")

    /** A row's `lastUpdated` as POSIX millis; null when unreadable. */
    fun parseUpdated(text: String?): Long? {
        val t = text?.trim()?.replace(SPACES, " ")?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDateTime.parse(t, UPDATED).atZone(EASTERN).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private val WKT_POINT = Regex("""POINT\s*\(\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*\)""")

    /** "POINT (-75.90045 40.30142)": longitude first. */
    private fun wktPoint(wkt: String?): GeoPoint? {
        val m = WKT_POINT.find(wkt ?: return null) ?: return null
        return point(m.groupValues[2].toDouble(), m.groupValues[1].toDouble())
    }

    private fun point(lat: Double, lon: Double): GeoPoint? =
        if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)

    /** The site's own relative links on its host; plain http to it upgraded (Android blocks cleartext); other https kept. */
    private fun absolute(site: Site, url: String): String? {
        val host = site.base.removePrefix("https://")
        return when {
            url.startsWith("/") && !url.startsWith("//") -> site.base + url
            url.startsWith("http://$host", ignoreCase = true) -> "https://" + url.substring("http://".length)
            url.startsWith("https://") -> url
            else -> null
        }
    }

    private fun parse(json: String): Any? = try {
        MiniJson.parse(json)
    } catch (e: MiniJson.JsonException) {
        throw IllegalArgumentException("sent something that isn't JSON")
    }

    /** Text, or a whole number as text (ids are numbers in some rows). */
    private fun Map<*, *>.text(key: String): String? = when (val v = this[key]) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Long, is Int -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> null
    }
}

/**
 * One IBI site's feeds as [TrafficFeed] wants them, every request through [fetch] (the site's own toggle).
 * Events: every event layer's icons each time, and a list's rows again only when one of its layers shows an id
 * that wasn't there when the rows were read, or the rows are older than [Ibi511.LIST_MAX_AGE_MILLIS]: a list can
 * run to several pages (511PA's every event is four, about 0.5 MB) and most of it is roadwork that rarely changes.
 * Not thread-safe: [TrafficFeed] calls it under its lock.
 */
class Ibi511Client(val site: Ibi511.Site, private val fetch: (String) -> ByteArray) : StateTraffic {
    override val source: TrafficSource get() = site.source

    /** Their signs come as list pages with nothing to say they haven't changed, so less often than 511NY's. */
    override val signsTtlMillis: Long get() = TrafficFeed.IBI_SIGNS_TTL_MILLIS

    private class Rows(val byId: Map<String, Map<*, *>>, val asked: Set<String>, val fetchedAt: Long)

    private val lists = HashMap<String, Rows>()

    /**
     * Every event on the site's map with its details; an icon whose row the list doesn't hold is left out.
     *
     * @throws java.io.IOException when a request fails; @throws IllegalArgumentException when an answer isn't
     * what the site's pages read.
     */
    override fun events(now: Long): List<Incident> {
        val icons = site.eventLayers.associateWith { Ibi511.parseIcons(get(site.iconsUrl(it.icons))) }
        val out = LinkedHashMap<String, Incident>()
        for ((list, layers) in site.eventLayers.groupBy { it.list }) {
            val ids = layers.flatMapTo(HashSet()) { icons.getValue(it).keys }
            if (ids.isEmpty()) continue
            val had = lists[list]
            val rows = if (had != null && had.asked.containsAll(ids) && now - had.fetchedAt < Ibi511.LIST_MAX_AGE_MILLIS) {
                had
            } else {
                Rows(rows(list), ids, now).also { lists[list] = it }
            }
            for (layer in layers) {
                for ((id, at) in icons.getValue(layer)) {
                    val row = rows.byId[id] ?: continue
                    Ibi511.event(row, at, layer, site)?.let { out.putIfAbsent(it.id, it) }
                }
            }
        }
        return out.values.toList()
    }

    /** Every camera, all pages of the site's camera list. */
    override fun cameras(): List<TrafficCamera> = rows(Ibi511.CAMERAS_LIST).values.mapNotNull { Ibi511.camera(it, site) }

    /** Every message sign on the site's map, with what it says. */
    override fun signs(): List<MessageSign> {
        val icons = Ibi511.parseIcons(get(site.iconsUrl(Ibi511.SIGNS_LAYER)))
        if (icons.isEmpty()) return emptyList()
        val rows = rows(Ibi511.SIGNS_LIST)
        return icons.mapNotNull { (id, at) -> rows[id]?.let { Ibi511.sign(it, at, site) } }
    }

    private fun get(url: String): String = Ibi511.text(fetch(url))

    /** Every row of a list, page by page, by id. */
    private fun rows(list: String): Map<String, Map<*, *>> {
        val out = LinkedHashMap<String, Map<*, *>>()
        var start = 0
        repeat(Ibi511.MAX_PAGES) {
            val page = Ibi511.parsePage(get(site.listUrl(list, start)))
            for (row in page.rows) Ibi511.rowId(row)?.let { out.putIfAbsent(it, row) }
            start += page.rows.size
            if (page.rows.isEmpty() || page.total == null || start >= page.total) return out
        }
        return out
    }
}
