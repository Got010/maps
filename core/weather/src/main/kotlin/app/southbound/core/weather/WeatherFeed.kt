package app.southbound.core.weather

import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkFeatureDisabledException
import app.southbound.core.network.NetworkGate
import app.southbound.core.traffic.GeoPoint
import kotlinx.coroutines.Dispatchers
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
 * The radar picture of the whole state, a transparent PNG in Web Mercator spanning
 * [Radar.BOUNDS], so the map draws it between those corners. [fetchedAt] is POSIX millis.
 */
class RadarImage(val png: ByteArray, val fetchedAt: Long)

/** The latest weather: alerts for the state, the shapes of their areas and, when asked for, radar and current conditions. */
data class WeatherSnapshot(
    val alerts: List<WeatherAlert> = emptyList(),
    /** Each alert's id to its area: its own polygons, else its zones' shapes once read. */
    val areas: Map<String, List<Ring>> = emptyMap(),
    val radar: RadarImage? = null,
    /** The latest observation at each station in the state, when asked for. */
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

/** NOAA's radar mosaic as one picture of New York (seen on 2026-10-07: a 60-70 KB PNG, cached 2 min upstream). */
object Radar {
    /** West, south, east, north: the state with a margin, the same for every phone. */
    val BOUNDS = doubleArrayOf(-79.8, 40.4, -71.8, 45.1)
    const val WIDTH = 1024
    const val HEIGHT = 820

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
 * [NetworkGate]: the state's alerts, the shapes of the zones they name, and the radar
 * picture while the weather layer is shown. Every request is the same for every phone in
 * New York; none carries a location, search or route.
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
    private val mutex = Mutex()
    private var alerts: List<WeatherAlert>? = null
    private var alertsAt = 0L
    private var radar: RadarImage? = null
    private var conditions: List<CurrentConditions>? = null
    private var conditionsAt = 0L
    private var forecast: ForecastPack? = null
    private var forecastId: Long? = null
    private var forecastCheckedAt = 0L
    private var forecastLoaded = false

    // A zone's shape rarely changes: once read, kept for the life of the process.
    private val zoneShapes = HashMap<String, List<Ring>>()
    private val _snapshot = MutableStateFlow(WeatherSnapshot.EMPTY)

    val snapshot: StateFlow<WeatherSnapshot> = _snapshot.asStateFlow()

    val isEnabled: Boolean
        get() = gate.isAllowed(NetworkFeature.WEATHER)

    /**
     * Fetches what is stale and returns the snapshot. [withRadar] also fetches the radar
     * picture (for the map's weather layer) and [withConditions] the stations' current
     * conditions (the layer's temperatures, a place card); without them the last ones are
     * kept but nothing is requested for them. A failure keeps the last good data and adds an error.
     */
    suspend fun refresh(
        force: Boolean = false,
        withRadar: Boolean = false,
        withConditions: Boolean = false,
        withForecast: Boolean = false,
    ): WeatherSnapshot = mutex.withLock {
        if (!isEnabled) {
            alerts = null
            radar = null
            conditions = null
            return@withLock WeatherSnapshot.EMPTY.also { _snapshot.value = it }
        }
        val now = clock()
        val errors = ArrayList<String>()
        withContext(Dispatchers.IO) {
            if (force || alerts == null || now - alertsAt >= ALERTS_TTL_MILLIS) {
                try {
                    alerts = NwsAlerts.parse(fetch(NetworkFeature.WEATHER, NwsAlerts.URL).decodeToString())
                    alertsAt = now
                } catch (e: NetworkFeatureDisabledException) {
                    throw e
                } catch (e: IOException) {
                    errors += "Weather alerts: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    errors += "Weather alerts: ${e.message}"
                }
            }
            loadZones(alerts.orEmpty(), errors)
            if (withForecast) loadForecast(now, force, errors)
            if (withConditions && (force || conditions == null || now - conditionsAt >= CONDITIONS_TTL_MILLIS)) {
                try {
                    conditions = Metar.parse(fetch(NetworkFeature.WEATHER, Metar.URL).decodeToString())
                    conditionsAt = now
                } catch (e: NetworkFeatureDisabledException) {
                    throw e
                } catch (e: IOException) {
                    errors += "Current weather: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    errors += "Current weather: ${e.message}"
                }
            }
            if (withRadar && (force || radar == null || now - radar!!.fetchedAt >= RADAR_TTL_MILLIS)) {
                try {
                    val png = fetch(NetworkFeature.WEATHER, Radar.URL)
                    if (Radar.isPng(png)) radar = RadarImage(png, now) else errors += "Radar: not an image"
                } catch (e: NetworkFeatureDisabledException) {
                    throw e
                } catch (e: IOException) {
                    errors += "Radar: ${e.message}"
                }
            }
        }
        val list = alerts.orEmpty()
        val areas = list.associate { a ->
            a.id to a.polygons.ifEmpty { a.zoneUrls.flatMap { zoneShapes[it].orEmpty() } }
        }
        val fresh = forecast?.takeIf { now - it.generatedAt <= Forecasts.MAX_AGE_MILLIS }
        WeatherSnapshot(list, areas, radar, conditions.orEmpty(), fresh, alertsAt.takeIf { alerts != null }, errors).also { _snapshot.value = it }
    }

    /**
     * The forecast pack: the one kept on the phone first, then, at most once an hour, the release's current
     * file, downloaded only when it changed (it is rebuilt every six hours).
     */
    private fun loadForecast(now: Long, force: Boolean, errors: MutableList<String>) {
        if (!forecastLoaded) {
            forecastLoaded = true
            val dir = forecastDir
            if (dir != null) {
                runCatching {
                    val file = File(dir, Forecasts.ASSET)
                    if (file.isFile) {
                        forecast = Forecasts.parse(file.readBytes())
                        forecastId = File(dir, ID_FILE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
                    }
                }
            }
        }
        if (!force && forecast != null && now - forecastCheckedAt < FORECAST_CHECK_MILLIS) return
        val source = forecastSource ?: return
        try {
            val id = source.latestId()
            forecastCheckedAt = now
            if (id == null || id == forecastId) return
            val bytes = source.download(id)
            forecast = Forecasts.parse(bytes)
            forecastId = id
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
            errors += "Forecast: ${e.message}"
        } catch (e: IllegalArgumentException) {
            errors += "Forecast: ${e.message}"
        }
    }

    /** Reads the shapes of the zones alerts without a polygon name, a few per refresh. */
    private fun loadZones(list: List<WeatherAlert>, errors: MutableList<String>) {
        val missing = list.filter { it.polygons.isEmpty() }.flatMap { it.zoneUrls }.distinct().filter { it !in zoneShapes }
        var failed = 0
        for (url in missing.take(MAX_ZONES_PER_REFRESH)) {
            if (!url.startsWith(NwsAlerts.ZONE_PREFIX)) continue
            try {
                zoneShapes[url] = NwsZones.parse(fetch(NetworkFeature.WEATHER, url).decodeToString())
            } catch (e: IOException) {
                failed++
            } catch (e: IllegalArgumentException) {
                zoneShapes[url] = emptyList()
            }
        }
        if (failed > 0) errors += "Weather zones: $failed couldn't be read"
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

        /** A storm can name most of the state's 88 forecast zones; a few dozen at a time, the rest next refresh. */
        const val MAX_ZONES_PER_REFRESH = 40
    }
}
