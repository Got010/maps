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
 * search or route and always fetch the whole state or city. The other states'
 * 511s ([StateTraffic]: 511PA, CTroads and 511WI, [Ibi511]; Maryland's CHART, [MarylandChart]; DelDOT, [DelDot];
 * Illinois' Travel Midwest, [TravelMidwest]) come in beside 511NY, each under its own toggle.
 */
class TrafficFeed(
    private val gate: NetworkGate,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Travel Midwest's files answer only a POST; the same rules as [fetch] (the gate's, tests stand in). */
    private val post: (NetworkFeature, String, ByteArray) -> ByteArray = { feature, url, body -> gate.post(feature, url, body) },
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

    // The other states' 511s, by source: their events, cameras and signs as last fetched.
    private val ibi: List<StateTraffic> = Ibi511.SITES.map { site -> Ibi511Client(site) { url -> fetch(site.source.feature, url) } } +
        listOf(
            MarylandChart.Client { url -> fetch(TrafficSource.MD_CHART.feature, url) },
            DelDot.Client { url -> fetch(TrafficSource.DE_DELDOT.feature, url) },
            TravelMidwest.Client { url, body -> post(TrafficSource.IL_TRAVEL_MIDWEST.feature, url, body) },
        )
    private val ibiEvents = HashMap<TrafficSource, Cached<List<Incident>>>()
    private val ibiCameras = HashMap<TrafficSource, Cached<List<TrafficCamera>>>()
    private val ibiSigns = HashMap<TrafficSource, Cached<List<MessageSign>>>()
    private val _snapshot = MutableStateFlow(TrafficSnapshot.EMPTY)

    /** The latest result, for screens that only want to watch. */
    val snapshot: StateFlow<TrafficSnapshot> = _snapshot.asStateFlow()

    /** True when at least one traffic feed is turned on, so the layer button is worth showing. */
    val isEnabled: Boolean
        get() = hasIncidents || gate.isAllowed(NetworkFeature.TRAFFIC_NYC_DOT)

    /**
     * Some state's 511 is on (511NY, 511PA, CTroads), so there are incidents to draw, steer around and speak. None
     * needs a key (511NY since its October 2026 move, see [FiveElevenNyCars]; the others see [Ibi511]).
     */
    val hasIncidents: Boolean
        get() = TrafficSource.entries.any { gate.isAllowed(it.feature) }

    /**
     * Fetches whatever is stale among the feeds that are on and returns the
     * combined snapshot. Off feeds contribute nothing; a feed that fails keeps
     * its last good data and adds an error. Suspends on IO.
     *
     * [mapExtras] also fetches the state 511s' camera lists (daily) and message
     * signs (every few minutes), for the map's traffic layer; without it the last
     * ones fetched are kept but nothing is requested for them.
     */
    suspend fun refresh(force: Boolean = false, mapExtras: Boolean = false): TrafficSnapshot = mutex.withLock {
        val now = clock()
        // The feeds load side by side, possibly on several threads.
        val errors: MutableList<String> = Collections.synchronizedList(ArrayList())
        val extraErrors: MutableList<String> = Collections.synchronizedList(ArrayList())
        val ny511 = gate.isAllowed(NetworkFeature.TRAFFIC_511NY)
        coroutineScope {
            val incidentsJob = async {
                if (!ny511) {
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
                    !ny511 -> null
                    !mapExtras || (!force && cameras?.fresh(now, CAMERAS_TTL_MILLIS) == true) -> cameras
                    else -> load(now, cameras, extraErrors, "511NY cameras") {
                        FiveElevenNyCars.parseCameras(fetch(NetworkFeature.TRAFFIC_511NY, FiveElevenNyCars.CAMERAS_URL).decodeToString())
                    }
                }
            }
            val signsJob = async {
                when {
                    !ny511 -> null
                    !mapExtras || (!force && signs?.fresh(now, SIGNS_TTL_MILLIS) == true) -> signs
                    else -> load(now, signs, extraErrors, "511NY signs") {
                        sameOrFetch(signsHashed, FiveElevenNyCars.SIGNS_HASH_URL) {
                            FiveElevenNyCars.parseSigns(fetch(NetworkFeature.TRAFFIC_511NY, FiveElevenNyCars.SIGNS_URL).decodeToString())
                        }.also { signsHashed = it }.value
                    }
                }
            }
            // 511PA, CTroads, CHART and DelDOT: the same three, each under its own toggle.
            val ibiJobs = ibi.map { client ->
                val source = client.source
                val on = gate.isAllowed(source.feature)
                val eventsJob = async {
                    val had = ibiEvents[source]
                    when {
                        !on -> null
                        !force && had?.fresh(now, INCIDENTS_TTL_MILLIS) == true -> had
                        else -> load(now, had, errors, source.label) { client.events(now) }
                    }
                }
                val camerasOf = async {
                    val had = ibiCameras[source]
                    when {
                        !on -> null
                        !mapExtras || (!force && had?.fresh(now, CAMERAS_TTL_MILLIS) == true) -> had
                        else -> load(now, had, extraErrors, "${source.label} cameras") { client.cameras() }
                    }
                }
                val signsOf = async {
                    val had = ibiSigns[source]
                    when {
                        !on -> null
                        !mapExtras || (!force && had?.fresh(now, client.signsTtlMillis) == true) -> had
                        else -> load(now, had, extraErrors, "${source.label} signs") { client.signs() }
                    }
                }
                Triple(source, eventsJob, camerasOf to signsOf)
            }
            incidents = incidentsJob.await()
            speeds = speedsJob.await()
            cameras = camerasJob.await()
            signs = signsJob.await()
            for ((source, eventsJob, extras) in ibiJobs) {
                ibiEvents.putOrRemove(source, eventsJob.await())
                ibiCameras.putOrRemove(source, extras.first.await())
                ibiSigns.putOrRemove(source, extras.second.await())
            }
        }
        // An outage fails all of a 511's requests alike: say it once, as the 511's own error.
        val mainErrors = errors.toList()
        val shownErrors = mainErrors + extraErrors.toList().filterNot { extra ->
            mainErrors.any { extra.startsWith(it.substringBefore(": ")) && it.substringAfter(": ") == extra.substringAfter(": ") }
        }
        TrafficSnapshot(
            incidents = incidents?.value.orEmpty() + ibi.flatMap { ibiEvents[it.source]?.value.orEmpty() },
            speeds = speeds?.value.orEmpty(),
            cameras = cameras?.value.orEmpty() + ibi.flatMap { ibiCameras[it.source]?.value.orEmpty() },
            signs = signs?.value.orEmpty() + ibi.flatMap { ibiSigns[it.source]?.value.orEmpty() },
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

    private fun <K, V> HashMap<K, V>.putOrRemove(key: K, value: V?) {
        if (value == null) remove(key) else put(key, value)
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

        /**
         * 511PA's and CTroads' signs come as list pages with nothing to say they haven't changed (511PA's 1,170 are 12
         * pages and the icons, about 0.6 MB), so less often than 511NY's.
         */
        const val IBI_SIGNS_TTL_MILLIS = 10 * 60_000L
    }
}
