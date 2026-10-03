package app.southbound.core.routing

import app.southbound.core.network.MiniJson
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkFeatureDisabledException
import app.southbound.core.network.NetworkGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * EIA series ids for weekly retail regular gasoline, $/gal. UNVERIFIED against the live API:
 * check them once with a real key (the request in [GasPrices.url] should return one row for
 * each) and correct them here if EIA names them differently. They are the only place ids live.
 */
object EiaSeries {
    const val NEW_YORK = "EMM_EPMR_PTE_SNY_DPG"

    /** Central Atlantic (PADD 1B); EIA has no New Jersey series, so New Jersey uses this. */
    const val CENTRAL_ATLANTIC = "EMM_EPMR_PTE_R1Y_DPG"
    const val US = "EMM_EPMR_PTE_NUS_DPG"
}

/** The places Settings offers a gas price for. A choice the user makes, never worked out from GPS. */
enum class GasRegion(val seriesId: String) {
    NY(EiaSeries.NEW_YORK),
    NJ(EiaSeries.CENTRAL_ATLANTIC),
    US(EiaSeries.US),
}

/** A weekly average retail price, as published for [periodIso] (the week's date, "2026-09-29"). */
data class GasPrice(val pricePerGallon: Double, val periodIso: String, val seriesId: String)

/** Where [GasPrices] keeps its last result, so it can wait a day between requests. [FuelPrefs] is the real one. */
interface GasPriceStore {
    /** The region the driver has picked. */
    val region: GasRegion

    /** The region the stored price is for, or null when none is stored. */
    val fetchedRegion: GasRegion?
    val fetchedAtMillis: Long

    fun saveFetched(price: GasPrice, region: GasRegion, atMillis: Long)
}

/**
 * The state's average gas price from the U.S. Energy Information Administration, through
 * [NetworkGate] and only with the Gas prices toggle on and the user's own EIA key. The request
 * names a series for the region the user picked in Settings and carries nothing else about them:
 * no location, search or route. At most one request a day per region; failures change nothing,
 * so the typed-in price keeps working offline.
 */
class GasPrices(
    private val gate: NetworkGate,
    private val apiKey: () -> String?,
    private val store: GasPriceStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (NetworkFeature, String) -> ByteArray = gate::get,
) {
    private val mutex = Mutex()
    private var lastAttemptMillis = 0L
    private var lastAttemptRegion: GasRegion? = null

    /** Why the last request failed, for diagnostics; null after a success or when none was made. */
    @Volatile
    var lastError: String? = null
        private set

    /** The toggle is on and a key is saved. */
    val isReady: Boolean
        get() = gate.isAllowed(NetworkFeature.GAS_PRICES) && !apiKey().isNullOrBlank()

    /**
     * Fetches the price for the picked region if the stored one is missing, for another region,
     * or a day old, and stores it. Returns the new price, or null when nothing was fetched.
     * Suspends on IO.
     */
    suspend fun refresh(force: Boolean = false): GasPrice? = mutex.withLock {
        val key = apiKey()
        if (!gate.isAllowed(NetworkFeature.GAS_PRICES) || key.isNullOrBlank()) return@withLock null
        val now = clock()
        val region = store.region
        if (!force && !isDue(store.fetchedRegion, store.fetchedAtMillis, region, now)) return@withLock null
        // After a failure, wait a few minutes rather than asking again on every screen change.
        if (!force && lastAttemptRegion == region && now - lastAttemptMillis in 0 until RETRY_MILLIS) return@withLock null
        lastAttemptMillis = now
        lastAttemptRegion = region
        try {
            val body = withContext(Dispatchers.IO) { fetch(NetworkFeature.GAS_PRICES, url(key, region.seriesId)) }
            val price = parse(body.decodeToString())
            store.saveFetched(price, region, now)
            lastError = null
            price
        } catch (e: NetworkFeatureDisabledException) {
            null
        } catch (e: IOException) {
            lastError = e.message ?: "couldn't be reached"
            null
        } catch (e: IllegalArgumentException) {
            // Not JSON, or an error document such as a refused key.
            lastError = e.message ?: "sent something unexpected"
            null
        }
    }

    companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val RETRY_MILLIS = 15 * 60 * 1000L
        const val MIN_PRICE = 0.5
        const val MAX_PRICE = 15.0

        private const val ENDPOINT = "https://api.eia.gov/v2/petroleum/pri/gnd/data/"

        /** True when the stored price is missing, for a different region, from the future, or a day old. */
        fun isDue(fetchedRegion: GasRegion?, fetchedAtMillis: Long, region: GasRegion, now: Long): Boolean =
            fetchedRegion != region || now - fetchedAtMillis !in 0 until DAY_MILLIS

        fun isValidPrice(price: Double): Boolean = price.isFinite() && price in MIN_PRICE..MAX_PRICE

        /** The newest weekly retail regular gasoline price for [seriesId]. The key is the user's own. */
        fun url(key: String, seriesId: String): String =
            ENDPOINT +
                "?api_key=${enc(key)}" +
                "&frequency=weekly" +
                "&data%5B0%5D=value" +
                "&facets%5Bseries%5D%5B%5D=${enc(seriesId)}" +
                "&sort%5B0%5D%5Bcolumn%5D=period" +
                "&sort%5B0%5D%5Bdirection%5D=desc" +
                "&length=1"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /**
         * Reads EIA's v2 answer: `{"response":{"data":[{"period":"2026-09-29","value":"3.512",...}]}}`.
         * The value may be a string or a number. Takes the first row with a usable price; a price
         * outside 0.5-15 is invalid. Throws [IllegalArgumentException] when there is none.
         */
        fun parse(json: String): GasPrice {
            val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("EIA sent no object")
            val response = root["response"] as? Map<*, *>
                ?: throw IllegalArgumentException(errorText(root) ?: "EIA sent no response")
            val rows = response["data"] as? List<*> ?: throw IllegalArgumentException("EIA sent no data")
            for (row in rows) {
                val m = row as? Map<*, *> ?: continue
                val value = when (val v = m["value"]) {
                    is Number -> v.toDouble()
                    is String -> v.trim().toDoubleOrNull()
                    else -> null
                } ?: continue
                if (!isValidPrice(value)) continue
                val period = (m["period"] as? String)?.takeIf { it.isNotBlank() } ?: continue
                return GasPrice(value, period, (m["series"] as? String).orEmpty())
            }
            throw IllegalArgumentException("EIA sent no usable price")
        }

        private fun errorText(root: Map<*, *>): String? = (root["error"] as? String)?.takeIf { it.isNotBlank() }

        /** "Sep 29" for "2026-09-29"; the text as given when it is not a date. */
        fun formatPeriod(periodIso: String): String = try {
            LocalDate.parse(periodIso).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        } catch (e: DateTimeParseException) {
            periodIso
        }
    }
}
