package org.nysmaps.core.network

/**
 * Every feature that may touch the network. Each has its own user toggle, and
 * only pack downloads are on by default. Add a feature here before adding any
 * code that makes a request.
 */
enum class NetworkFeature(val enabledByDefault: Boolean) {
    PACK_DOWNLOADS(enabledByDefault = true),
    TRAFFIC_511NY(enabledByDefault = false),
    TRAFFIC_NYC_DOT(enabledByDefault = false),
    TRAFFIC_TOMTOM(enabledByDefault = false),
    TRANSIT_REALTIME(enabledByDefault = false),
    TRANSIT_BUS_TIME(enabledByDefault = false),
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

/** The one door to the network. Every request runs inside [request]. */
class NetworkGate(private val toggles: NetworkToggles) {

    fun isAllowed(feature: NetworkFeature): Boolean = toggles.isEnabled(feature)

    /** Runs [block] only if [feature] is on, otherwise throws [NetworkFeatureDisabledException]. */
    inline fun <T> request(feature: NetworkFeature, block: () -> T): T {
        if (!isAllowed(feature)) throw NetworkFeatureDisabledException(feature)
        return block()
    }
}
