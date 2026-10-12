package app.southbound.core.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Every feature that may touch the network. Each has its own user toggle, and
 * only pack downloads are on by default. Add a feature here before adding any
 * code that makes a request.
 */
enum class NetworkFeature(val enabledByDefault: Boolean) {
    /** Map data packs from Southbound's GitHub releases. */
    PACK_DOWNLOADS(enabledByDefault = true),

    /** New versions of the app itself, from the same releases. */
    APP_UPDATES(enabledByDefault = false),
    TRAFFIC_511NY(enabledByDefault = false),
    TRAFFIC_NYC_DOT(enabledByDefault = false),
    TRAFFIC_TOMTOM(enabledByDefault = false),
    TRANSIT_REALTIME(enabledByDefault = false),
    TRANSIT_BUS_TIME(enabledByDefault = false),

    /** The weekly average gas price for the state the user picked, from EIA. */
    GAS_PRICES(enabledByDefault = false),

    /** National Weather Service alerts, NOAA radar, current weather and the forecast pack, for every covered state. */
    WEATHER(enabledByDefault = false),

    /** Pennsylvania's 511 (511PA, PennDOT): incidents, closures, roadwork, cameras and message signs, the whole state. */
    TRAFFIC_511PA(enabledByDefault = false),

    /** Connecticut's 511 (CTroads, CTDOT): the same for Connecticut. */
    TRAFFIC_CTROADS(enabledByDefault = false),

    /** Maryland's CHART (MDOT SHA): incidents, closures, message signs and cameras, the whole state. */
    TRAFFIC_CHART_MD(enabledByDefault = false),

    /** Delaware's DelDOT: advisories and closures, message signs and cameras, the whole state. */
    TRAFFIC_DELDOT(enabledByDefault = false),

    /** Wisconsin's 511 (511WI, WisDOT): incidents, closures, roadwork, cameras and message signs, the whole state. */
    TRAFFIC_511WI(enabledByDefault = false),

    /** Illinois' Travel Midwest (IDOT and the Tollway's Gateway): incidents, roadwork, cameras and message signs, the whole state. */
    TRAFFIC_TRAVEL_MIDWEST(enabledByDefault = false),

    /** North Dakota's NDDOT travel map (ArcGIS): alerts, work zones and cameras, the whole state. */
    TRAFFIC_NDDOT(enabledByDefault = false),

    /** South Dakota's SD511 (Iteris): road work, incidents, restrictions and cameras, the whole state. */
    TRAFFIC_SD511(enabledByDefault = false),

    /** Indiana's 511 (CARS platform): events, cameras and message signs, the whole state. */
    TRAFFIC_511IN(enabledByDefault = false),

    /** Minnesota's 511 (CARS platform). */
    TRAFFIC_511MN(enabledByDefault = false),

    /** Iowa's 511 (CARS platform). */
    TRAFFIC_511IA(enabledByDefault = false),

    /** Nebraska 511 (CARS platform). */
    TRAFFIC_511NE(enabledByDefault = false),

    /** Kansas' KanDrive (CARS platform). */
    TRAFFIC_KANDRIVE(enabledByDefault = false),

    /** Michigan's Mi Drive (MDOT): incidents, work zones and cameras, the whole state. */
    TRAFFIC_MIDRIVE(enabledByDefault = false),

    /** Ohio's OHGO (ODOT), with the user's own key: incidents, construction, cameras and message signs, the whole state. */
    TRAFFIC_OHGO(enabledByDefault = false),
}

fun interface NetworkToggles {
    fun isEnabled(feature: NetworkFeature): Boolean
}

/** Toggles held in memory. Settings will back these with DataStore. */
class InMemoryNetworkToggles(
    overrides: Map<NetworkFeature, Boolean> = emptyMap(),
) : NetworkToggles {
    private val enabled = NetworkFeature.entries
        .associateWith { overrides[it] ?: it.enabledByDefault }
        .toMutableMap()

    override fun isEnabled(feature: NetworkFeature): Boolean = enabled.getValue(feature)

    fun set(feature: NetworkFeature, value: Boolean) {
        enabled[feature] = value
    }
}

class NetworkFeatureDisabledException(val feature: NetworkFeature) :
    IllegalStateException("Network feature $feature is turned off")

/** The one door to the network. Every request runs inside [request] or [get]. */
class NetworkGate(private val toggles: NetworkToggles) {

    fun isAllowed(feature: NetworkFeature): Boolean = toggles.isEnabled(feature)

    /** Runs [block] only if [feature] is on, otherwise throws [NetworkFeatureDisabledException]. */
    inline fun <T> request(feature: NetworkFeature, block: () -> T): T {
        if (!isAllowed(feature)) throw NetworkFeatureDisabledException(feature)
        return block()
    }

    /**
     * A plain HTTPS GET for [feature]. Sends no cookies, no referrer and a
     * generic user agent; callers must not put location, search text or
     * routes in [url]. Redirects are followed by hand, HTTPS only and at most
     * [MAX_REDIRECTS] of them, and the answer may be at most [maxBytes].
     * Blocking: call off the main thread.
     */
    fun get(feature: NetworkFeature, url: String, timeoutMillis: Int = 15_000, maxBytes: Int = MAX_BYTES): ByteArray = request(feature) {
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            if (!target.protocol.equals("https", ignoreCase = true)) throw IOException("Refused to connect to ${target.host}: not HTTPS")
            val connection = target.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $code from ${target.host} without a target")
                    target = URL(target, location)
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code from ${target.host}")
                return@request connection.inputStream.use { readCapped(it, maxBytes, target.host) }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects from ${URL(url).host}")
    }

    /**
     * A plain HTTPS POST for [feature] with a fixed [body] (Travel Midwest answers its map feeds to a POST of `{}`
     * and nothing else): the same rules as [get], and the body must say nothing about the user either. Blocking.
     */
    fun post(feature: NetworkFeature, url: String, body: ByteArray, contentType: String = "application/json", timeoutMillis: Int = 15_000): ByteArray =
        request(feature) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Content-Type", contentType)
                connection.setRequestProperty("Accept", "application/json")
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
                connection.inputStream.use { readCapped(it, MAX_BYTES, URL(url).host) }
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val USER_AGENT = "Southbound"

        /** The largest answer a feed may send: well above the biggest statewide feed, far below what would hurt the phone. */
        const val MAX_BYTES = 32 shl 20
        const val MAX_REDIRECTS = 5

        /** [input] whole, or an IOException once it passes [max] bytes. */
        fun readCapped(input: java.io.InputStream, max: Int, host: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > max) throw IOException("The answer from $host is larger than ${max shr 20} MB")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
