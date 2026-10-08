package app.southbound.core.weather

import app.southbound.core.network.MiniJson
import app.southbound.core.traffic.Geo
import app.southbound.core.traffic.GeoPoint
import kotlin.math.roundToInt

/** What the sky is doing, worst first: the icon on the map and in search shows this. */
enum class Sky { THUNDER, SNOW, SLEET, RAIN, DRIZZLE, FOG, OVERCAST, MOSTLY_CLOUDY, PARTLY_CLOUDY, CLEAR }

/** The latest observation at one weather station (an airport's METAR). */
data class CurrentConditions(
    /** "KALB". */
    val station: String,
    /** "Albany Intl", without ", NY, US". */
    val name: String,
    val location: GeoPoint,
    /** Rounded to a whole degree Fahrenheit; null when the station sent none. */
    val tempF: Int?,
    val sky: Sky,
    /** POSIX millis of the observation. */
    val observedAt: Long,
    /** Wind in mph, rounded; null when calm or not sent. */
    val windMph: Int?,
)

/**
 * Reads `https://aviationweather.gov/api/data/metar?ids=@NY&format=json`: the latest METAR of every
 * reporting station in New York (the NWS Aviation Weather Center), one request, no key, nothing about
 * where the driver is. Seen on 2026-10-08 (feed probe): 49 stations, about 25 KB.
 */
object Metar {
    const val URL: String = "https://aviationweather.gov/api/data/metar?ids=@NY&format=json"

    /** A place farther than this from every station shows no current weather (rough areas, not a guess). */
    const val NEAR_KM = 40.0

    /** @throws IllegalArgumentException when it isn't a JSON list. */
    fun parse(json: String): List<CurrentConditions> {
        if (json.isBlank()) return emptyList() // 204 No Content: nothing reported
        val list = MiniJson.parse(json) as? List<*> ?: throw IllegalArgumentException("aviationweather sent no list")
        return list.mapNotNull { (it as? Map<*, *>)?.let(::station) }.distinctBy { it.station }
    }

    /** The conditions nearest [point], within [NEAR_KM]; null when none is that close. */
    fun nearest(conditions: List<CurrentConditions>, point: GeoPoint): CurrentConditions? =
        conditions.minByOrNull { Geo.distanceMeters(it.location, point) }
            ?.takeIf { Geo.distanceMeters(it.location, point) <= NEAR_KM * 1000 }

    private fun station(m: Map<*, *>): CurrentConditions? {
        val id = (m["icaoId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val lat = (m["lat"] as? Number)?.toDouble() ?: return null
        val lon = (m["lon"] as? Number)?.toDouble() ?: return null
        val observed = (m["obsTime"] as? Number)?.toLong()?.times(1000) ?: return null
        val tempC = (m["temp"] as? Number)?.toDouble()
        val knots = (m["wspd"] as? Number)?.toDouble()
        return CurrentConditions(
            station = id,
            name = ((m["name"] as? String) ?: id).replace(Regex(""",\s*[A-Z]{2},\s*US\s*$"""), "").trim(),
            location = GeoPoint(lat, lon),
            tempF = tempC?.let { (it * 9 / 5 + 32).roundToInt() },
            sky = sky(m["wxString"] as? String, m["cover"] as? String),
            observedAt = observed,
            windMph = knots?.takeIf { it > 0 }?.let { (it * 1.15078).roundToInt() },
        )
    }

    /**
     * The weather codes first ("-RA", "VCTS -DZ", "+SN BR"), worst wins; "VC" (in the vicinity) counts only
     * for thunder. Then the cloud cover: CLR/SKC clear, FEW/SCT partly, BKN mostly, OVC/VV overcast.
     */
    fun sky(wx: String?, cover: String?): Sky {
        val codes = wx?.uppercase()?.split(Regex("""\s+""")).orEmpty().filter { it.isNotEmpty() }
        val here = codes.filterNot { it.startsWith("VC") }
        fun has(code: String) = here.any { code in it }
        return when {
            codes.any { "TS" in it } -> Sky.THUNDER
            has("SN") || has("SG") -> Sky.SNOW
            has("PL") || has("FZ") || has("GS") || has("GR") -> Sky.SLEET
            has("RA") || has("SH") -> Sky.RAIN
            has("DZ") -> Sky.DRIZZLE
            has("FG") || has("BR") || has("HZ") -> Sky.FOG
            else -> when (cover?.uppercase()) {
                "OVC", "VV", "OVX" -> Sky.OVERCAST
                "BKN" -> Sky.MOSTLY_CLOUDY
                "FEW", "SCT" -> Sky.PARTLY_CLOUDY
                else -> Sky.CLEAR
            }
        }
    }
}
