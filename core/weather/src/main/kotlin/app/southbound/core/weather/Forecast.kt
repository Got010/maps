package app.southbound.core.weather

import app.southbound.core.network.MiniJson
import app.southbound.core.traffic.Geo
import app.southbound.core.traffic.GeoPoint
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** One forecast period of the NWS: "This Afternoon", "Tonight", "Thursday", ... (about 12 hours each). */
data class ForecastPeriod(
    /** POSIX millis. */
    val start: Long,
    val end: Long,
    val isDay: Boolean,
    /** The high for a day period, the low for a night one. */
    val tempF: Int?,
    /** Chance of rain or snow, percent. */
    val pop: Int?,
    val sky: Sky,
    /** "Tonight", "Thursday". */
    val name: String,
    /** "Chance Rain Showers". */
    val text: String,
)

/** The forecast at one point of the pack's grid. */
data class ForecastPoint(val location: GeoPoint, val periods: List<ForecastPeriod>)

/** One day of the 7-day forecast: the day period and the night after it, either may be missing. */
data class ForecastDay(
    /** "Thursday", or "Tonight" when only tonight is left of today. */
    val name: String,
    val start: Long,
    val high: Int?,
    val low: Int?,
    /** The day's sky (the night's when only the night is left). */
    val sky: Sky,
    val pop: Int?,
    val text: String,
)

/** The whole pack: every grid point's forecast, as built at [generatedAt] (POSIX millis). */
class ForecastPack(val generatedAt: Long, val points: List<ForecastPoint>) {
    /** The grid point nearest [point], within [Forecasts.NEAR_KM]; null when none is that close (outside the state). */
    fun nearest(point: GeoPoint): ForecastPoint? =
        points.minByOrNull { Geo.distanceMeters(it.location, point) }
            ?.takeIf { Geo.distanceMeters(it.location, point) <= Forecasts.NEAR_KM * 1000 }
}

/**
 * Reads forecast-ny.json.gz, the pack pipeline/weather/forecast_pack.py builds every six hours from the
 * National Weather Service (see .github/workflows/weather.yml), and turns its periods into days.
 */
object Forecasts {
    /** The release the pack is published on (a pre-release, so no update check sees it) and its file. */
    const val RELEASE_TAG = "weather"
    const val ASSET = "forecast-ny.json.gz"

    /** The pack's grid is 0.3 degrees (about 25 by 33 km): any place is within about 21 km of a point. */
    const val NEAR_KM = 30.0

    /** A pack this old is not shown: the forecast would be about days already gone. */
    const val MAX_AGE_MILLIS = 36 * 3_600_000L

    private const val MAX_UNZIPPED = 8 * 1024 * 1024

    /** @throws IllegalArgumentException when it isn't a pack. */
    fun parse(gz: ByteArray): ForecastPack {
        val json = try {
            GZIPInputStream(ByteArrayInputStream(gz)).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_UNZIPPED) throw IllegalArgumentException("forecast pack too large")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("not a gzip'd forecast pack", e)
        }
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("not a forecast pack")
        if ((root["v"] as? Number)?.toInt() != 1) throw IllegalArgumentException("unknown forecast pack version")
        val generated = ((root["generated"] as? Number)?.toLong() ?: 0L) * 1000
        val points = (root["points"] as? List<*>).orEmpty().mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            val lat = (m["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lon = (m["lon"] as? Number)?.toDouble() ?: return@mapNotNull null
            val periods = (m["periods"] as? List<*>).orEmpty().mapNotNull { period(it as? List<*>) }
            if (periods.isEmpty()) null else ForecastPoint(GeoPoint(lat, lon), periods)
        }
        return ForecastPack(generated, points)
    }

    private fun period(row: List<*>?): ForecastPeriod? {
        if (row == null || row.size < 8) return null
        val start = (row[0] as? Number)?.toLong() ?: return null
        val end = (row[1] as? Number)?.toLong() ?: return null
        val text = row[7] as? String ?: ""
        return ForecastPeriod(
            start = start * 1000,
            end = end * 1000,
            isDay = (row[2] as? Number)?.toInt() == 1,
            tempF = (row[3] as? Number)?.toInt(),
            pop = (row[4] as? Number)?.toInt(),
            sky = sky(row[5] as? String, text),
            name = row[6] as? String ?: "",
            text = text,
        )
    }

    /**
     * The NWS icon code ("rain_showers", "tsra_sct", "bkn", "fzra") as the app's [Sky]; the forecast's words decide
     * when the code is missing or unknown.
     */
    fun sky(icon: String?, text: String = ""): Sky {
        val code = icon?.lowercase()?.removePrefix("wind_").orEmpty()
        return when {
            code.startsWith("tsra") || code in setOf("tornado", "hurricane", "tropical_storm") -> Sky.THUNDER
            code in setOf("fzra", "rain_fzra", "snow_fzra", "sleet", "rain_sleet", "snow_sleet", "rain_snow") -> Sky.SLEET
            code == "snow" || code == "blizzard" -> Sky.SNOW
            code.startsWith("rain") -> Sky.RAIN
            code in setOf("fog", "haze", "smoke", "dust") -> Sky.FOG
            code == "ovc" -> Sky.OVERCAST
            code == "bkn" -> Sky.MOSTLY_CLOUDY
            code == "few" || code == "sct" -> Sky.PARTLY_CLOUDY
            code in setOf("skc", "hot", "cold") -> Sky.CLEAR
            else -> fromWords(text)
        }
    }

    private fun fromWords(text: String): Sky {
        val t = text.lowercase()
        return when {
            "thunder" in t -> Sky.THUNDER
            "freezing" in t || "sleet" in t -> Sky.SLEET
            "snow" in t || "flurries" in t -> Sky.SNOW
            "drizzle" in t -> Sky.DRIZZLE
            "rain" in t || "showers" in t -> Sky.RAIN
            "fog" in t || "haze" in t || "smoke" in t -> Sky.FOG
            "mostly cloudy" in t -> Sky.MOSTLY_CLOUDY
            "partly" in t -> Sky.PARTLY_CLOUDY
            "cloudy" in t || "overcast" in t -> Sky.OVERCAST
            else -> Sky.CLEAR
        }
    }

    /** The period that covers [time], or null when the forecast doesn't reach it. */
    fun at(point: ForecastPoint, time: Long): ForecastPeriod? = point.periods.firstOrNull { time >= it.start && time < it.end }

    /**
     * Up to [count] days from [now]: each day period with the night after it ("Thursday": high, low), today's
     * night alone when the day is over ("Tonight": low only). Periods already ended are left out.
     */
    fun days(point: ForecastPoint, now: Long, count: Int = 7): List<ForecastDay> {
        val left = point.periods.filter { it.end > now }.sortedBy { it.start }
        val out = ArrayList<ForecastDay>()
        var i = 0
        while (i < left.size && out.size < count) {
            val p = left[i]
            if (p.isDay) {
                val night = left.getOrNull(i + 1)?.takeIf { !it.isDay }
                out += ForecastDay(
                    name = p.name,
                    start = p.start,
                    high = p.tempF,
                    low = night?.tempF,
                    sky = p.sky,
                    pop = listOfNotNull(p.pop, night?.pop).maxOrNull(),
                    text = p.text,
                )
                i += if (night != null) 2 else 1
            } else {
                out += ForecastDay(p.name, p.start, null, p.tempF, p.sky, p.pop, p.text)
                i += 1
            }
        }
        return out
    }
}
