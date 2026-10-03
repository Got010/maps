package app.southbound.core.traffic

import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkFeatureDisabledException
import app.southbound.core.network.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Fetches the traffic feeds the user turned on, through [NetworkGate], and
 * keeps the latest [TrafficSnapshot]. Each feed is cached for its own
 * interval (HANDOFF: 511NY every 2-5 minutes, NYC DOT every 1-2), so the map
 * and navigation can ask as often as they like. Requests carry no location,
 * search or route and always fetch the whole state or city.
 */
class TrafficFeed(
    private val gate: NetworkGate,
    private val key511: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (NetworkFeature, String) -> ByteArray = gate::get,
) {
    private val mutex = Mutex()
    private var incidents: Cached<List<Incident>>? = null
    private var speeds: Cached<List<SpeedLink>>? = null
    private var speedsStale = false
    private val _snapshot = MutableStateFlow(TrafficSnapshot.EMPTY)

    /** The latest result, for screens that only want to watch. */
    val snapshot: StateFlow<TrafficSnapshot> = _snapshot.asStateFlow()

    /** True when at least one traffic feed is turned on, so the layer button is worth showing. */
    val isEnabled: Boolean
        get() = gate.isAllowed(NetworkFeature.TRAFFIC_511NY) || gate.isAllowed(NetworkFeature.TRAFFIC_NYC_DOT)

    /** 511NY is on and has a key. */
    val hasIncidents: Boolean
        get() = gate.isAllowed(NetworkFeature.TRAFFIC_511NY) && !key511().isNullOrBlank()

    /** 511NY is turned on but has no key yet, so nothing is fetched from it. */
    val needsIncidentsKey: Boolean
        get() = gate.isAllowed(NetworkFeature.TRAFFIC_511NY) && key511().isNullOrBlank()

    /**
     * Fetches whatever is stale among the feeds that are on and returns the
     * combined snapshot. Off feeds contribute nothing; a feed that fails keeps
     * its last good data and adds an error. Suspends on IO.
     */
    suspend fun refresh(force: Boolean = false): TrafficSnapshot = mutex.withLock {
        val now = clock()
        val errors = ArrayList<String>()
        coroutineScope {
            val incidentsJob = async {
                if (!hasIncidents) {
                    incidents = null
                    null
                } else if (!force && incidents?.fresh(now, INCIDENTS_TTL_MILLIS) == true) {
                    incidents
                } else {
                    load(now, incidents, errors, "511NY") {
                        FiveElevenNy.parseEvents(fetch(NetworkFeature.TRAFFIC_511NY, FiveElevenNy.eventsUrl(key511()!!)).decodeToString())
                    }
                }
            }
            val speedsJob = async {
                if (!gate.isAllowed(NetworkFeature.TRAFFIC_NYC_DOT)) {
                    speeds = null
                    speedsStale = false
                    null
                } else if (!force && speeds?.fresh(now, SPEEDS_TTL_MILLIS) == true) {
                    speeds
                } else {
                    load(now, speeds, errors, "NYC DOT") {
                        val report = NycDotSpeeds.parseReport(fetch(NetworkFeature.TRAFFIC_NYC_DOT, NycDotSpeeds.URL).decodeToString(), now)
                        speedsStale = report.allStale
                        report.links
                    }
                }
            }
            incidents = incidentsJob.await()
            speeds = speedsJob.await()
        }
        TrafficSnapshot(
            incidents = incidents?.value.orEmpty(),
            speeds = speeds?.value.orEmpty(),
            fetchedAt = now,
            errors = errors,
            speedsStale = speedsStale && speeds != null,
        ).also { _snapshot.value = it }
    }

    /** Fetches [block] off the main thread; on failure keeps [previous] and records why. */
    private suspend fun <T> load(now: Long, previous: Cached<T>?, errors: MutableList<String>, name: String, block: () -> T): Cached<T>? =
        try {
            Cached(withContext(Dispatchers.IO) { block() }, now)
        } catch (e: NetworkFeatureDisabledException) {
            null
        } catch (e: IOException) {
            errors += "$name: ${e.message ?: "couldn't be reached"}"
            previous?.copy(fetchedAt = now)
        } catch (e: IllegalArgumentException) {
            // Not JSON, or an error document such as a refused key.
            errors += "$name: ${e.message ?: "sent something unexpected"}"
            previous?.copy(fetchedAt = now)
        }

    private data class Cached<T>(val value: T, val fetchedAt: Long) {
        fun fresh(now: Long, ttl: Long) = now - fetchedAt < ttl
    }

    companion object {
        const val INCIDENTS_TTL_MILLIS = 180_000L
        const val SPEEDS_TTL_MILLIS = 90_000L
    }
}
