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
import java.util.Collections

/**
 * Fetches the traffic feeds the user turned on, through [NetworkGate], and
 * keeps the latest [TrafficSnapshot]. Each feed is cached for its own
 * interval (HANDOFF: 511NY every 2-5 minutes, NYC DOT every 1-2), so the map
 * and navigation can ask as often as they like. Requests carry no location,
 * search or route and always fetch the whole state or city.
 */
class TrafficFeed(
    private val gate: NetworkGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (NetworkFeature, String) -> ByteArray = gate::get,
) {
    private val mutex = Mutex()
    private var incidents: Cached<List<Incident>>? = null
    private var speeds: Cached<List<SpeedLink>>? = null
    private var speedsStale = false
    private var cameras: Cached<List<TrafficCamera>>? = null
    private var signs: Cached<List<MessageSign>>? = null

    // 511NY's collections with the hash each was fetched at: an unchanged hash skips the download.
    private val eventLayers = HashMap<FiveElevenNyCars.EventLayer, Hashed<List<Incident>>>()
    private var signsHashed: Hashed<List<MessageSign>>? = null
    private val _snapshot = MutableStateFlow(TrafficSnapshot.EMPTY)

    /** The latest result, for screens that only want to watch. */
    val snapshot: StateFlow<TrafficSnapshot> = _snapshot.asStateFlow()

    /** True when at least one traffic feed is turned on, so the layer button is worth showing. */
    val isEnabled: Boolean
        get() = gate.isAllowed(NetworkFeature.TRAFFIC_511NY) || gate.isAllowed(NetworkFeature.TRAFFIC_NYC_DOT)

    /** 511NY is on. Its feeds need no key since its October 2026 move (see [FiveElevenNyCars]). */
    val hasIncidents: Boolean
        get() = gate.isAllowed(NetworkFeature.TRAFFIC_511NY)

    /**
     * Fetches whatever is stale among the feeds that are on and returns the
     * combined snapshot. Off feeds contribute nothing; a feed that fails keeps
     * its last good data and adds an error. Suspends on IO.
     *
     * [mapExtras] also fetches 511NY's camera list (daily) and message signs
     * (every few minutes), for the map's traffic layer; without it the last
     * ones fetched are kept but nothing is requested for them.
     */
    suspend fun refresh(force: Boolean = false, mapExtras: Boolean = false): TrafficSnapshot = mutex.withLock {
        val now = clock()
        // The feeds load side by side, possibly on several threads.
        val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
        val extraErrors: MutableList<String> = Collections.synchronizedList(ArrayList())
        coroutineScope {
            val incidentsJob = async {
                if (!hasIncidents) {
                    incidents = null
                    null
                } else if (!force && incidents?.fresh(now, INCIDENTS_TTL_MILLIS) == true) {
                    incidents
                } else {
                    load(now, incidents, errors, "511NY") { loadEvents() }
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
            // Cameras and signs: the same 511NY toggle, the whole state each time.
            val camerasJob = async {
                when {
                    !hasIncidents -> null
                    !mapExtras || (!force && cameras?.fresh(now, CAMERAS_TTL_MILLIS) == true) -> cameras
                    else -> load(now, cameras, extraErrors, "511NY cameras") {
                        FiveElevenNyCars.parseCameras(fetch(NetworkFeature.TRAFFIC_511NY, FiveElevenNyCars.CAMERAS_URL).decodeToString())
                    }
                }
            }
            val signsJob = async {
                when {
                    !hasIncidents -> null
                    !mapExtras || (!force && signs?.fresh(now, SIGNS_TTL_MILLIS) == true) -> signs
                    else -> load(now, signs, extraErrors, "511NY signs") {
                        sameOrFetch(signsHashed, FiveElevenNyCars.SIGNS_HASH_URL) {
                            FiveElevenNyCars.parseSigns(fetch(NetworkFeature.TRAFFIC_511NY, FiveElevenNyCars.SIGNS_URL).decodeToString())
                        }.also { signsHashed = it }.value
                    }
                }
            }
            incidents = incidentsJob.await()
            speeds = speedsJob.await()
            cameras = camerasJob.await()
            signs = signsJob.await()
        }
        // An outage fails all three 511NY requests alike: say it once.
        val mainErrors = errors.toList()
        val shownErrors = mainErrors + extraErrors.toList().filterNot { extra ->
            mainErrors.any { it.substringAfter(": ") == extra.substringAfter(": ") }
        }
        TrafficSnapshot(
            incidents = incidents?.value.orEmpty(),
            speeds = speeds?.value.orEmpty(),
            cameras = cameras?.value.orEmpty(),
            signs = signs?.value.orEmpty(),
            fetchedAt = now,
            errors = shownErrors,
            speedsStale = speedsStale && speeds != null,
        ).also { _snapshot.value = it }
    }

    /**
     * The three event layers 511NY's map shows, merged by id (a closure listed under construction
     * as well stays a closure). Each is downloaded only when its hash changed.
     */
    private fun loadEvents(): List<Incident> {
        val merged = LinkedHashMap<String, Incident>()
        for (layer in EVENT_LAYERS) {
            val layerEvents = sameOrFetch(eventLayers[layer], FiveElevenNyCars.eventsHashUrl(layer)) {
                FiveElevenNyCars.parseEvents(fetch(NetworkFeature.TRAFFIC_511NY, FiveElevenNyCars.eventsUrl(layer)).decodeToString(), layer)
            }
            eventLayers[layer] = layerEvents
            for (incident in layerEvents.value) merged.putIfAbsent(incident.id, incident)
        }
        return merged.values.toList()
    }

    /**
     * [had] again when [hashUrl] still gives its hash, else [download] with the new hash. A hash that
     * can't be read just means downloading; a turned-off feature still stops everything.
     */
    private fun <T> sameOrFetch(had: Hashed<T>?, hashUrl: String, download: () -> T): Hashed<T> {
        val hash = try {
            FiveElevenNyCars.parseHash(fetch(NetworkFeature.TRAFFIC_511NY, hashUrl).decodeToString())
        } catch (e: IOException) {
            null
        }
        if (had != null && hash != null && had.hash == hash) return had
        return Hashed(hash, download())
    }

    private data class Hashed<T>(val hash: String?, val value: T)

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
        /** Closures first, so an event in two layers keeps the closure's kind. */
        private val EVENT_LAYERS = listOf(
            FiveElevenNyCars.EventLayer.CLOSURES,
            FiveElevenNyCars.EventLayer.INCIDENTS,
            FiveElevenNyCars.EventLayer.CONSTRUCTION,
        )

        const val INCIDENTS_TTL_MILLIS = 180_000L
        const val SPEEDS_TTL_MILLIS = 90_000L

        /** Cameras come and go rarely; their stills are fetched one at a time when opened. */
        const val CAMERAS_TTL_MILLIS = 24 * 60 * 60_000L

        /** What signs say changes with the traffic. */
        const val SIGNS_TTL_MILLIS = 5 * 60_000L
    }
}
