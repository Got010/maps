package app.southbound.core.weather

import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkFeatureDisabledException
import app.southbound.core.network.NetworkGate
import app.southbound.core.traffic.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * The radar picture of every covered state, a transparent PNG in Web Mercator spanning
 * [Radar.BOUNDS], so the map draws it between those corners. [fetchedAt] is POSIX millis.
 */
class RadarImage(val png: ByteArray, val fetchedAt: Long)

/** The latest weather: alerts for the covered states, the shapes of their areas and, when asked for, radar and current conditions. */
data class WeatherSnapshot(
    /** The alerts that haven't ended yet ([WeatherAlert.ends]), from the last good list. */
    val alerts: List<WeatherAlert> = emptyList(),
    /** Each alert's id to its area: its own polygons, else its zones' shapes once read. */
    val areas: Map<String, List<Ring>> = emptyMap(),
    val radar: RadarImage? = null,
    /** The latest observation at each station in the covered states, when asked for, at most [Metar.MAX_AGE_MILLIS] old. */
    val conditions: List<CurrentConditions> = emptyList(),
    /** The 7-day forecast pack, when asked for and not too old ([Forecasts.MAX_AGE_MILLIS]). */
    val forecast: ForecastPack? = null,
    /** POSIX millis of the last good alert list, or null. */
    val alertsAt: Long? = null,
    val errors: List<String> = emptyList(),
) {
    /** The alerts whose area contains [point]: "here" or a destination. */
    fun alertsAt(point: GeoPoint, now: Long): List<WeatherAlert> = alerts.filter { a ->
        (a.ends == null || a.ends >= now) && RouteWeather.contains(areas[a.id].orEmpty().ifEmpty { a.polygons }, point)
    }

    companion object {
        val EMPTY = WeatherSnapshot()
    }
}

/**
 * NOAA's radar mosaic as one picture of every covered state (seen on 2026-10-07: New York alone a 60-70 KB PNG,
 * cached 2 min upstream).
 */
object Radar {
    /**
     * West, south, east, north: every covered state, Kansas to Long Island and Missouri's boot heel to the Northwest
     * Angle, with a margin, the same for every phone (the east alone was -80.6, 37.8, -71.7, 45.1 until the Midwest
     * was offered in October 2026). One picture of all of it: a phone asks for the same bytes wherever its map is.
     */
    val BOUNDS = doubleArrayOf(-104.2, 35.9, -71.6, 49.5)

    /**
     * About 1.5 km a pixel in Web Mercator units (1.1 km on the ground at 45 degrees north; the east's picture had
     * 870 m), since the box is three times as wide: MRMS itself is a kilometre, and the picture stays within a
     * megabyte or two of PNG every three minutes while the layer is shown.
     */
    const val WIDTH = 2400
    const val HEIGHT = 1360

    /**
     * MRMS quality-controlled base reflectivity from NCEP's map server, as a WMS GetMap of
     * [BOUNDS] in EPSG:3857 (so it lines up with the map drawn between the corners).
     */
    val URL: String = run {
        val (x0, y0) = mercator(BOUNDS[0], BOUNDS[1])
        val (x1, y1) = mercator(BOUNDS[2], BOUNDS[3])
        "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows?service=WMS&version=1.3.0&request=GetMap" +
            "&layers=conus_bref_qcd&styles=&crs=EPSG:3857&bbox=${x0.toLong()},${y0.toLong()},${x1.toLong()},${y1.toLong()}" +
            "&width=$WIDTH&height=$HEIGHT&format=image/png&transparent=true"
    }

    private fun mercator(lon: Double, lat: Double): Pair<Double, Double> =
        lon * 20037508.34 / 180 to ln(tan((90 + lat) * PI / 360)) * 6378137

    /** A PNG starts with these eight bytes; anything else (an error page) is not drawn. */
    fun isPng(bytes: ByteArray): Boolean = bytes.size > 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
}

/**
 * Where the forecast pack comes from: the "weather" pre-release on Southbound's GitHub (the app wires
 * GitHubReleases in, under the weather toggle). An interface so the feed is tested without a network.
 */
interface ForecastSource {
    /** The pack's asset id now (it changes with each upload), or null when there is no pack yet. */
    fun latestId(): Long?

    /** The pack file (gzip'd JSON) with that id. */
    fun download(id: Long): ByteArray
}

/**
 * Fetches the weather the user turned on (Settings > Live data > Weather), through
 * [NetworkGate]: the covered states' alerts, the shapes of the zones they name, and the radar
 * picture while the weather layer is shown. Every request is the same for every phone;
 * none carries a location, search or route.
 */
class WeatherFeed(
    private val gate: NetworkGate,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The forecast pack's source; null leaves the forecast out. */
    private val forecastSource: ForecastSource? = null,
    /** Where the last forecast pack is kept, so it reads offline and after a restart; null keeps it in memory only. */
    private val forecastDir: File? = null,
    private val fetch: (NetworkFeature, String) -> ByteArray = gate::get,
) {
    // Guards the fields below. It is held to see what is stale and to store what came back, never across a request:
    // one refresh is some sixty requests, minutes on a poor connection, and nothing should wait on them.
    private val mutex = Mutex()
    private var alerts: List<WeatherAlert>? = null
    private var alertsAt = 0L
    private var radar: RadarImage? = null
    private var conditions: List<CurrentConditions>? = null
    private var conditionsAt = 0L

    // Each state's last good stations, so one state's failed request keeps its old ones.
    private val conditionsByUrl = HashMap<String, List<CurrentConditions>>()
    private var forecast: ForecastPack? = null
    private var forecastId: Long? = null

    // When the release was last asked about the pack, whatever came of it; null until it has been.
    private var forecastCheckedAt: Long? = null

    // The release's file that didn't read as a pack: not downloaded again until the release has another.
    private var forecastFailedId: Long? = null
    private var forecastLoaded = false

    // A zone's shape rarely changes: once read, kept for the life of the process.
    private val zoneShapes = HashMap<String, List<Ring>>()

    // What a refresh under way is fetching. A refresh that starts meanwhile leaves those to it, so nothing is asked twice.
    private val busy = HashSet<Part>()
    private val _snapshot = MutableStateFlow(WeatherSnapshot.EMPTY)

    val snapshot: StateFlow<WeatherSnapshot> = _snapshot.asStateFlow()

    val isEnabled: Boolean
        get() = gate.isAllowed(NetworkFeature.WEATHER)

    /**
     * Fetches what is stale and returns the snapshot. [withRadar] also fetches the radar
     * picture (for the map's weather layer) and [withConditions] the stations' current
     * conditions (the layer's temperatures, a place card); without them the last ones are
     * kept but nothing is requested for them. A failure keeps the last good data and adds an error.
     * What another refresh is still fetching is left to it; its answers reach [snapshot] when it is done.
     */
    suspend fun refresh(
        force: Boolean = false,
        withRadar: Boolean = false,
        withConditions: Boolean = false,
        withForecast: Boolean = false,
    ): WeatherSnapshot {
        val plan = mutex.withLock {
            if (!isEnabled) {
                clear()
                return@withLock null
            }
            plan(clock(), force, withRadar, withConditions, withForecast)
        } ?: return WeatherSnapshot.EMPTY
        val got = Fetched()
        // Stored even when a request throws or the refresh is cancelled part way: what came back is kept, and the
        // parts are freed for the next refresh.
        val outcome = runCatching { withContext(Dispatchers.IO) { fetchParts(plan, got) } }
        val snapshot = withContext(NonCancellable) { mutex.withLock { store(plan, got) } }
        outcome.getOrThrow()
        return snapshot
    }

    /** Weather turned off: what was fetched goes, and nothing is shown. Under the lock. */
    private fun clear(): WeatherSnapshot {
        alerts = null
        radar = null
        conditions = null
        conditionsByUrl.clear()
        return WeatherSnapshot.EMPTY.also { _snapshot.value = it }
    }

    /** Under the lock: what this refresh fetches (what is stale and not being fetched already), claimed for it. */
    private fun plan(now: Long, force: Boolean, withRadar: Boolean, withConditions: Boolean, withForecast: Boolean): Plan {
        val parts = HashSet<Part>()
        // The alerts when stale, and the zone shapes still missing every time.
        parts += Part.ALERTS
        if (withConditions && (force || conditions == null || now - conditionsAt >= CONDITIONS_TTL_MILLIS)) parts += Part.CONDITIONS
        val shown = radar
        if (withRadar && (force || shown == null || now - shown.fetchedAt >= RADAR_TTL_MILLIS)) parts += Part.RADAR
        if (withForecast) parts += Part.FORECAST
        parts -= busy
        busy += parts
        return Plan(
            now = now,
            force = force,
            parts = parts,
            alertsDue = Part.ALERTS in parts && (force || alerts == null || now - alertsAt >= ALERTS_TTL_MILLIS),
            alerts = alerts,
            knownZones = HashSet(zoneShapes.keys),
            forecastLoaded = forecastLoaded,
            forecastId = forecastId,
            forecastCheckedAt = forecastCheckedAt,
            forecastFailedId = forecastFailedId,
        )
    }

    /** The requests, without the lock: every answer and error goes into [got]. */
    private fun fetchParts(plan: Plan, got: Fetched) {
        if (plan.alertsDue) {
            try {
                got.alerts = NwsAlerts.parse(fetch(NetworkFeature.WEATHER, NwsAlerts.URL).decodeToString())
            } catch (e: NetworkFeatureDisabledException) {
                throw e
            } catch (e: IOException) {
                got.errors += "Weather alerts: ${e.message}"
            } catch (e: IllegalArgumentException) {
                got.errors += "Weather alerts: ${e.message}"
            }
        }
        if (Part.ALERTS in plan.parts) loadZones(got.alerts ?: plan.alerts.orEmpty(), plan.knownZones, got)
        if (Part.FORECAST in plan.parts) loadForecast(plan, got)
        if (Part.CONDITIONS in plan.parts) {
            var failed: String? = null
            for (url in Metar.URLS) {
                try {
                    got.conditions[url] = Metar.parse(fetch(NetworkFeature.WEATHER, url).decodeToString())
                } catch (e: NetworkFeatureDisabledException) {
                    throw e
                } catch (e: IOException) {
                    failed = failed ?: e.message.orEmpty()
                } catch (e: IllegalArgumentException) {
                    failed = failed ?: e.message.orEmpty()
                }
            }
            if (failed != null) got.errors += "Current weather: $failed"
        }
        if (Part.RADAR in plan.parts) {
            try {
                val png = fetch(NetworkFeature.WEATHER, Radar.URL)
                if (Radar.isPng(png)) got.radar = RadarImage(png, plan.now) else got.errors += "Radar: not an image"
            } catch (e: NetworkFeatureDisabledException) {
                throw e
            } catch (e: IOException) {
                got.errors += "Radar: ${e.message}"
            }
        }
    }

    /** Under the lock: keeps what came back, frees the parts and publishes the snapshot. */
    private fun store(plan: Plan, got: Fetched): WeatherSnapshot {
        busy -= plan.parts
        // Turned off while the requests ran: nothing they brought is kept.
        if (!isEnabled) return clear()
        val now = plan.now
        got.alerts?.let {
            alerts = it
            alertsAt = now
        }
        zoneShapes += got.zones
        // All failed: asked again next time, as before. Some: the rest keep their last stations until the next round.
        if (got.conditions.isNotEmpty()) {
            conditionsByUrl += got.conditions
            conditions = Metar.URLS.flatMap { conditionsByUrl[it].orEmpty() }.distinctBy { it.station }
            conditionsAt = now
        }
        got.radar?.let { radar = it }
        if (got.forecastLoaded) forecastLoaded = true
        got.forecast?.let {
            forecast = it
            forecastId = got.forecastId
        }
        got.forecastCheckedAt?.let { forecastCheckedAt = it }
        got.forecastFailedId?.let { forecastFailedId = it }
        // Offline the last alerts and observations stay: an alert past its end and an observation hours old are
        // left out rather than drawn as if they were current.
        val list = alerts.orEmpty().filter { it.ends == null || it.ends >= now }
        val areas = list.associate { a ->
            a.id to a.polygons.ifEmpty { a.zoneUrls.flatMap { zoneShapes[it].orEmpty() } }
        }
        val current = conditions.orEmpty().filter { now - it.observedAt <= Metar.MAX_AGE_MILLIS }
        val fresh = forecast?.takeIf { now - it.generatedAt <= Forecasts.MAX_AGE_MILLIS }
        return WeatherSnapshot(list, areas, radar, current, fresh, alertsAt.takeIf { alerts != null }, got.errors)
            .also { _snapshot.value = it }
    }

    /**
     * The forecast pack: the one kept on the phone first, then, at most once an hour, the release's current
     * file, downloaded only when it changed (it is rebuilt every six hours).
     */
    private fun loadForecast(plan: Plan, got: Fetched) {
        var have = plan.forecastId
        if (!plan.forecastLoaded) {
            got.forecastLoaded = true
            val dir = forecastDir
            if (dir != null) {
                runCatching {
                    val file = File(dir, Forecasts.ASSET)
                    if (file.isFile) {
                        got.forecast = Forecasts.parse(file.readBytes())
                        have = File(dir, ID_FILE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
                        got.forecastId = have
                    }
                }
            }
        }
        if (!forecastDue(plan.now, plan.forecastCheckedAt, plan.force)) return
        val source = forecastSource ?: return
        try {
            val id = source.latestId()
            // A check from here on, whatever comes of it: no release, or a pack that doesn't download or read, waits
            // for the next hourly check instead of being asked about at every refresh.
            got.forecastCheckedAt = plan.now
            if (id == null || id == have || id == plan.forecastFailedId) return
            val bytes = source.download(id)
            val pack = try {
                Forecasts.parse(bytes)
            } catch (e: IllegalArgumentException) {
                // The same file would fail the same way (a pack version this app doesn't know, a truncated upload).
                got.forecastFailedId = id
                throw e
            }
            got.forecast = pack
            got.forecastId = id
            forecastDir?.let { dir ->
                runCatching {
                    dir.mkdirs()
                    val tmp = File(dir, Forecasts.ASSET + ".tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(File(dir, Forecasts.ASSET))
                    File(dir, ID_FILE).writeText(id.toString())
                }
            }
        } catch (e: NetworkFeatureDisabledException) {
            throw e
        } catch (e: IOException) {
            got.errors += "Forecast: ${e.message}"
        } catch (e: IllegalArgumentException) {
            got.errors += "Forecast: ${e.message}"
        }
    }

    /** Reads the shapes of the zones alerts without a polygon name, a few per refresh. */
    private fun loadZones(list: List<WeatherAlert>, known: Set<String>, got: Fetched) {
        val missing = list.filter { it.polygons.isEmpty() }.flatMap { it.zoneUrls }.distinct().filter { it !in known }
        var failed = 0
        for (url in missing.take(MAX_ZONES_PER_REFRESH)) {
            if (!url.startsWith(NwsAlerts.ZONE_PREFIX)) continue
            try {
                got.zones[url] = NwsZones.parse(fetch(NetworkFeature.WEATHER, url).decodeToString())
            } catch (e: IOException) {
                failed++
            } catch (e: IllegalArgumentException) {
                // Not a zone (a captive portal's page answers 200 too): not kept, so it is asked again next time.
                failed++
            }
        }
        if (failed > 0) got.errors += "Weather zones: $failed couldn't be read"
    }

    /** What a refresh fetches, each part by one refresh at a time. ALERTS includes the zone shapes still missing. */
    private enum class Part { ALERTS, CONDITIONS, RADAR, FORECAST }

    /** One refresh's work, decided under the lock, with the state its requests start from. */
    private class Plan(
        val now: Long,
        val force: Boolean,
        val parts: Set<Part>,
        val alertsDue: Boolean,
        val alerts: List<WeatherAlert>?,
        val knownZones: Set<String>,
        val forecastLoaded: Boolean,
        val forecastId: Long?,
        val forecastCheckedAt: Long?,
        val forecastFailedId: Long?,
    )

    /** What one refresh's requests brought back, stored under the lock once they are done. */
    private class Fetched {
        var alerts: List<WeatherAlert>? = null
        val zones = HashMap<String, List<Ring>>()
        val conditions = HashMap<String, List<CurrentConditions>>()
        var radar: RadarImage? = null
        var forecastLoaded = false
        var forecast: ForecastPack? = null
        var forecastId: Long? = null
        var forecastCheckedAt: Long? = null
        var forecastFailedId: Long? = null
        val errors = ArrayList<String>()
    }

    companion object {
        const val ALERTS_TTL_MILLIS = 5 * 60_000L
        /** NOAA's mosaic updates about every 2 minutes; a new picture every 3, on the home map and while driving. */
        const val RADAR_TTL_MILLIS = 3 * 60_000L

        /** The forecast pack is rebuilt every six hours; the release is asked about it at most hourly. */
        const val FORECAST_CHECK_MILLIS = 60 * 60_000L
        private const val ID_FILE = "forecast-ny.id"

        /** Stations report hourly, more often when the weather changes. */
        const val CONDITIONS_TTL_MILLIS = 10 * 60_000L

        /** A storm can name most of a state's forecast zones (New York has 88); a few dozen at a time, the rest next refresh. */
        const val MAX_ZONES_PER_REFRESH = 40

        /**
         * Whether the release is asked about the forecast pack now: when forced, when it never has been, or when it
         * was last asked [FORECAST_CHECK_MILLIS] ago or more. Whatever came of the last check counts (a pack, none,
         * or one that didn't read), so a missing or broken pack costs a request an hour, not one a refresh.
         */
        internal fun forecastDue(now: Long, checkedAt: Long?, force: Boolean): Boolean =
            force || checkedAt == null || now - checkedAt >= FORECAST_CHECK_MILLIS
    }
}
