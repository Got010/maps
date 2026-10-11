package app.southbound.core.traffic

import app.southbound.core.network.MiniJson
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * The Midwest's 511s on Castle Rock's CARS platform (Indiana, Minnesota, Iowa, Nebraska, Kansas; 511NY moved to its
 * newer "511x" API, [FiveElevenNyCars]), read the way their own sites read them, the whole state each time, no key
 * and nothing about the user in the request:
 * - cameras and message signs are whole lists from the state's API host, `https://<st>tg.carsprogram.org/cameras_v1/api/cameras`
 *   and `signs_v1/api/signs` (on 2026-10-11, scripts/feed-probe.py: Indiana 749 cameras and 246 signs, Minnesota
 *   1,534 and 84, Iowa 861 and 506, Nebraska 346 and 14, Kansas 608 and 234; 70 KB to 830 KB each);
 * - events come from the site's GraphQL ([Site.graphqlUrl]), the `mapFeaturesQuery` its map runs ([eventsQuery]; on
 *   2026-10-11 Indiana had 14 incidents and 21 construction events drawn, Minnesota 5 crashes and 12 closures).
 */
object CarsStates {
    /**
     * One state's site: [api] its CARS services host, [site] the public site (whose `/api/graphql` answers the
     * events), [stillHosts] the ends of the host names a camera's still may be on; anything else is not followed.
     */
    class Site(val source: TrafficSource, val api: String, val site: String, val stillHosts: List<String>, val bounds: Bounds) {
        val camerasUrl: String get() = "$api/cameras_v1/api/cameras"
        val signsUrl: String get() = "$api/signs_v1/api/signs"
        val graphqlUrl: String get() = "$site/api/graphql"
    }

    /** A fixed box around the whole state (west, south, east, north), the same in every request. */
    class Bounds(val west: Double, val south: Double, val east: Double, val north: Double)

    val IN = Site(TrafficSource.IN_511, "https://intg.carsprogram.org", "https://511in.org", listOf("public.carsprogram.org"), Bounds(-88.2, 37.7, -84.7, 41.8))
    val MN = Site(TrafficSource.MN_511, "https://mntg.carsprogram.org", "https://511mn.org", listOf("public.carsprogram.org"), Bounds(-97.3, 43.4, -89.4, 49.5))
    val IA = Site(TrafficSource.IA_511, "https://iatg.carsprogram.org", "https://511ia.org", listOf("atmsqf.iowadot.gov"), Bounds(-96.7, 40.3, -90.0, 43.6))
    val NE = Site(TrafficSource.NE_511, "https://netg.carsprogram.org", "https://511.nebraska.gov", listOf("dot511.nebraska.gov"), Bounds(-104.2, 39.9, -95.2, 43.1))
    val KS = Site(TrafficSource.KS_KANDRIVE, "https://kstg.carsprogram.org", "https://www.kandrive.gov", listOf("www.kcscout.net", ".skyvdn.com", "kscam.carsprogram.org"), Bounds(-102.2, 36.9, -94.5, 40.1))
    val SITES: List<Site> = listOf(IN, MN, IA, NE, KS)

    /**
     * The map layers whose events the app shows (the sites' own slugs: incidents and closures, construction, and
     * Minnesota's, Nebraska's and Kansas' "road reports"); "future" (planned work, "STARTS WEDNESDAY") is left out.
     */
    val EVENT_LAYERS: List<String> = listOf("incidents", "closures", "construction", "roadReports")

    /**
     * The map zoom the query is made at: the server groups nearby events into clusters at low zooms (at 7, half of
     * Indiana's were clusters), and a cluster has no events in it to read.
     */
    const val ZOOM = 14

    /**
     * The GraphQL document the site's map posts for its event layers (`mapFeaturesQuery`), over the whole state
     * ([Site.bounds]) at [ZOOM], the same bytes every time: nothing about the user in it.
     */
    fun eventsQuery(site: Site): ByteArray {
        val b = site.bounds
        val query = "query MapFeatures(\$input: MapFeaturesArgs!) { mapFeaturesQuery(input: \$input) { mapFeatures { bbox tooltip uri " +
            "features { id geometry properties } __typename } error { message type } } }"
        val slugs = EVENT_LAYERS.joinToString(",") { "\"$it\"" }
        return ("{\"query\":\"$query\",\"variables\":{\"input\":{\"north\":${b.north},\"south\":${b.south},\"east\":${b.east},\"west\":${b.west}," +
            "\"zoom\":$ZOOM,\"layerSlugs\":[$slugs],\"nonClusterableUris\":[\"dashboard\"]}}}").encodeToByteArray()
    }

    /**
     * The map query's answer, `{"data":{"mapFeaturesQuery":{"mapFeatures":[...]}}}`: each Event a `uri`
     * ("event/CARSx-469755"), a `tooltip` ("I-80 eastbound: Exit ramp closed, because of roadwork.") and its features,
     * the Point among them its place and its icon its kind (`tg_closure_*` a closure, `tg_crash_*` a crash,
     * `tg_warning_*` road work or other by the words; `critical` and `urgent` its severity). An event in two layers
     * reads once, as the graver of the two. Clusters (several events drawn as one at this zoom) have no events to
     * read and are skipped; planned events (`future/` icons) too.
     *
     * @throws IllegalArgumentException when it isn't the query's answer (a GraphQL error included).
     */
    fun parseEvents(json: String, site: Site): List<Incident> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        } as? Map<*, *> ?: throw IllegalArgumentException("sent no answer")
        (root["errors"] as? List<*>)?.firstOrNull()?.let { throw IllegalArgumentException("refused the query: ${(it as? Map<*, *>)?.get("message")}") }
        val query = (root["data"] as? Map<*, *>)?.get("mapFeaturesQuery") as? Map<*, *> ?: throw IllegalArgumentException("sent no map features")
        (query["error"] as? Map<*, *>)?.get("message")?.let { throw IllegalArgumentException("refused the query: $it") }
        val items = query["mapFeatures"] as? List<*> ?: throw IllegalArgumentException("sent no map features")
        val byId = LinkedHashMap<String, Incident>()
        for (item in items.mapNotNull { it as? Map<*, *> }) {
            val incident = event(item, site) ?: continue
            val had = byId[incident.id]
            if (had == null || RANK.indexOf(incident.kind) < RANK.indexOf(had.kind)) byId[incident.id] = incident
        }
        return byId.values.toList()
    }

    private val RANK = listOf(IncidentKind.CLOSURE, IncidentKind.ACCIDENT, IncidentKind.POLICE, IncidentKind.ROADWORK, IncidentKind.EVENT, IncidentKind.OTHER)

    private fun event(m: Map<*, *>, site: Site): Incident? {
        if (m["__typename"] != "Event") return null
        val id = m.text("uri")?.removePrefix("event/")?.takeIf { it.isNotEmpty() } ?: return null
        val features = (m["features"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val pin = features.firstOrNull { (it["geometry"] as? Map<*, *>)?.get("type") == "Point" } ?: return null
        val coords = (pin["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*> ?: return null
        val lon = (coords.getOrNull(0) as? Number)?.toDouble() ?: return null
        val lat = (coords.getOrNull(1) as? Number)?.toDouble() ?: return null
        if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val icon = (((pin["properties"] as? Map<*, *>)?.get("icon") as? Map<*, *>)?.get("url") as? String).orEmpty()
        if ("/future/" in icon) return null
        val tooltip = plain(m.text("tooltip").orEmpty())
        val where = tooltip.substringBefore(":", "").trim()
        val what = tooltip.substringAfter(":", tooltip).trim()
        val kind = kindOf(icon, what)
        return Incident(
            id = site.source.idPrefix + id,
            kind = kind,
            roadway = where.takeIf { it.isNotEmpty() }?.let(::roadIn),
            direction = where.takeIf { it.isNotEmpty() }?.let(::directionIn),
            description = tooltip.ifEmpty { "Event" },
            location = GeoPoint(lat, lon),
            severity = when {
                "_critical" in icon -> "Critical"
                "_urgent" in icon -> "Urgent"
                else -> null
            },
            lanesStatus = if (kind == IncidentKind.CLOSURE) "All lanes closed" else lanesIn(what),
            updatedAt = null,
            source = site.source,
        )
    }

    /** By the icon first (`tg_closure`, `tg_crash`), then the words ("roadwork", "construction"). */
    fun kindOf(icon: String, words: String): IncidentKind {
        val w = words.lowercase(Locale.US)
        return when {
            "tg_closure" in icon -> IncidentKind.CLOSURE
            "tg_crash" in icon || "tg_accident" in icon -> IncidentKind.ACCIDENT
            w.startsWith("road closed") || w.startsWith("bridge closed") -> IncidentKind.CLOSURE
            "crash" in w || "accident" in w || "stalled" in w || "disabled vehicle" in w -> IncidentKind.ACCIDENT
            "roadwork" in w || "road work" in w || "construction" in w || "maintenance" in w || "utility work" in w || "bridge work" in w -> IncidentKind.ROADWORK
            "police" in w -> IncidentKind.POLICE
            else -> IncidentKind.OTHER
        }
    }

    /** "Exit ramp closed, because of roadwork." -> "Exit ramp closed"; "Crash." -> null. */
    fun lanesIn(words: String): String? {
        val first = words.substringBefore(",").substringBefore(".").trim()
        val w = first.lowercase(Locale.US)
        return first.takeIf { ("lane" in w || "ramp" in w || "shoulder" in w) && ("closed" in w || "blocked" in w) }
    }

    private val TAG = Regex("""<[^>]*>""")
    private val SPACES = Regex("""\s+""")

    /** The tooltip's HTML as words: tags go, spaces are squeezed. */
    fun plain(html: String): String = html.replace(TAG, "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace(SPACES, " ").trim()

    /** "I-80 eastbound" -> "I-80"; "US 75 in both directions" -> "US 75"; "Entrance ramp from MN 5" -> "MN 5". */
    fun roadIn(where: String): String? = roadOf(where)?.takeIf { ROUTE.containsMatchIn(it.uppercase(Locale.US)) }

    private val DIRECTION_WORD = Regex("""\b(north|south|east|west)bound\b|\bboth directions\b|\b([NSEW])B\b""", RegexOption.IGNORE_CASE)

    /** "I-80 eastbound" -> "Eastbound"; "US 75 in both directions" -> "Both Directions"; "US 75 NB/SB" -> "Both Directions". */
    fun directionIn(where: String): String? {
        val found = DIRECTION_WORD.findAll(where).map { it.value.lowercase(Locale.US) }.toList()
        if (found.isEmpty()) return null
        if (found.size > 1 || "both" in found[0]) return "Both Directions"
        return Ibi511.direction(found[0].removeSuffix("bound").removeSuffix("b"))
    }

    /**
     * The camera list, `[{id, public, name, location{latitude, longitude, routeId, cityReference}, views[{type, url,
     * videoPreviewUrl}], active}]`: a still is a STILL_IMAGE view's url or a video view's preview, on the state's own
     * servers ([Site.stillHosts]). Cameras marked not public go; one with no still on those hosts is disabled.
     *
     * @throws IllegalArgumentException when it isn't the list.
     */
    fun parseCameras(json: String, site: Site): List<TrafficCamera> = rows(json).mapNotNull { camera(it, site) }

    /**
     * The sign list, `[{id, status, name, display{pages[{lines}]}, location{...}}]`: the pages' lines when the status
     * is DISPLAYING_MESSAGE, nothing when BLANK or in error; a line that is a link (Minnesota's picture signs) goes.
     */
    fun parseSigns(json: String, site: Site): List<MessageSign> = rows(json).mapNotNull { sign(it, site) }

    private fun rows(json: String): List<Map<*, *>> {
        val root = try {
            MiniJson.parse(json)
        } catch (e: MiniJson.JsonException) {
            throw IllegalArgumentException("sent something that isn't JSON")
        }
        val list = root as? List<*> ?: throw IllegalArgumentException("sent no list")
        return list.mapNotNull { it as? Map<*, *> }
    }

    private fun camera(m: Map<*, *>, site: Site): TrafficCamera? {
        if (m["public"] == false) return null
        val id = m.text("id") ?: return null
        val location = m["location"] as? Map<*, *> ?: return null
        val at = location.point() ?: return null
        val views = (m["views"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val stills = views.mapNotNull { v ->
            (if (v.text("type") == "STILL_IMAGE") v.text("url") else v.text("videoPreviewUrl"))?.takeIf { still(it, site) }
        }
        val name = m.text("name") ?: location.text("cityReference") ?: "Camera $id"
        return TrafficCamera(
            id = site.source.idPrefix + id,
            name = name,
            lat = at.lat,
            lon = at.lon,
            imageUrl = stills.firstOrNull(),
            roadway = location.text("routeId")?.let(::roadOf),
            direction = null,
            disabled = m["active"] == false || stills.isEmpty(),
            source = site.source,
        )
    }

    private fun still(url: String, site: Site): Boolean = try {
        val u = URI(url)
        val host = u.host?.lowercase(Locale.US)
        u.scheme == "https" && host != null && u.rawQuery == null && site.stillHosts.any { host == it || it.startsWith(".") && host.endsWith(it) }
    } catch (e: URISyntaxException) {
        false
    }

    private fun sign(m: Map<*, *>, site: Site): MessageSign? {
        val id = m.text("id") ?: return null
        val location = m["location"] as? Map<*, *> ?: return null
        val at = location.point() ?: return null
        val showing = m.text("status").equals("DISPLAYING_MESSAGE", ignoreCase = true)
        val pages = ((m["display"] as? Map<*, *>)?.get("pages") as? List<*>).orEmpty().mapNotNull { p ->
            (p as? Map<*, *>)?.get("lines") as? List<*>
        }.map { lines -> lines.mapNotNull { it as? String }.filterNot { it.trim().startsWith("http://") || it.trim().startsWith("https://") }.joinToString("[nl]") }
        return MessageSign(
            id = site.source.idPrefix + id,
            name = m.text("name") ?: location.text("locationDescription") ?: "Sign $id",
            lat = at.lat,
            lon = at.lon,
            lines = if (showing) FiveElevenNy.signLines(listOf(pages.joinToString("[np]"))) else emptyList(),
            roadway = location.text("routeId")?.let(::roadOf),
            direction = Ibi511.direction(location.text("signFacingDirection")),
            updatedAt = (m["lastUpdated"] as? Number)?.toLong()?.takeIf { it > 0 },
            source = site.source,
        )
    }

    /** "I-265" stays; "MN 36", "US 18", "KS 39" stay; "IN I-74" -> "I-74"; a plain name ("Cleveland Ave") stays as it is. */
    fun roadOf(routeId: String): String? {
        val m = ROUTE.find(routeId.uppercase(Locale.US)) ?: return routeId.trim().takeIf { it.isNotBlank() }
        val (kind, number) = m.destructured
        return if (kind == "I") "I-$number" else "$kind $number"
    }

    private val ROUTE = Regex("""\b(I|US|IN|MN|IA|NE|KS|SR|K)[- ]?(\d{1,4}[A-Z]?)\b""")

    private fun Map<*, *>.point(): GeoPoint? {
        val lat = (this["latitude"] as? Number)?.toDouble() ?: return null
        val lon = (this["longitude"] as? Number)?.toDouble() ?: return null
        return if ((lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0) null else GeoPoint(lat, lon)
    }

    /** Text, or a whole number as text (camera ids are numbers). */
    private fun Map<*, *>.text(key: String): String? = when (val v = this[key]) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Long, is Int -> v.toString()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> null
    }

    /** One state: events by the map query (a POST through [post]), cameras and signs by their lists ([fetch]); all under its toggle. */
    class Client(val site: Site, private val fetch: (String) -> ByteArray, private val post: (String, ByteArray) -> ByteArray) : StateTraffic {
        override val source: TrafficSource get() = site.source

        override fun events(now: Long): List<Incident> = parseEvents(post(site.graphqlUrl, eventsQuery(site)).decodeToString(), site)

        override fun cameras(): List<TrafficCamera> = parseCameras(fetch(site.camerasUrl).decodeToString(), site)

        override fun signs(): List<MessageSign> = parseSigns(fetch(site.signsUrl).decodeToString(), site)
    }
}
