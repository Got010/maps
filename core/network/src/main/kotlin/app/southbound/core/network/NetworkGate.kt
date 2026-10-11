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
     * routes in [url]. Blocking: call off the main thread.
     */
    fun get(feature: NetworkFeature, url: String, timeoutMillis: Int = 15_000): ByteArray = request(feature) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "Southbound"
    }
}
