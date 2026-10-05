package app.southbound.core.routing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the gas price on route cards comes from. */
enum class PriceSource { MANUAL, EIA }

/**
 * What the driver's car burns and what a gallon costs, for the fuel estimate on route cards.
 * [mpg] is the car's combined rating. [pricePerGallon] is the price typed in Settings: it is
 * the default, the manual override and the offline fallback. With [priceSource] EIA, the
 * weekly average fetched for [region] stands in for it ([effectivePrice]).
 */
data class FuelSettings(
    val mpg: Double = DEFAULT_MPG,
    val pricePerGallon: Double = DEFAULT_PRICE,
    val priceSource: PriceSource = PriceSource.MANUAL,
    /** The state the driver picked in Settings. Never taken from GPS. */
    val region: GasRegion = GasRegion.NY,
    val fetchedPrice: Double? = null,
    /** The week the fetched price is for, as the feed gave it ("2026-09-29"). */
    val fetchedPeriod: String? = null,
    /** The region the fetched price is for; it stops counting when [region] changes. */
    val fetchedRegion: GasRegion? = null,
    val fetchedAtMillis: Long = 0L,
) {
    /** The fetched price, when there is one and it is for the region now picked. */
    val eiaPrice: Double? get() = fetchedPrice?.takeIf { fetchedRegion == region }

    /** What a gallon costs for the estimate. */
    val effectivePrice: Double
        get() = if (priceSource == PriceSource.EIA) eiaPrice ?: pricePerGallon else pricePerGallon

    /** Keeps both numbers inside sane limits; anything that is not a finite number goes back to its default. */
    fun sanitized(): FuelSettings = copy(
        mpg = clean(mpg, DEFAULT_MPG, MIN_MPG, MAX_MPG),
        pricePerGallon = clean(pricePerGallon, DEFAULT_PRICE, MIN_PRICE, MAX_PRICE),
        fetchedPrice = fetchedPrice?.takeIf { GasPrices.isValidPrice(it) },
    )

    companion object {
        const val DEFAULT_MPG = 25.0
        const val MIN_MPG = 5.0
        const val MAX_MPG = 150.0
        const val DEFAULT_PRICE = 3.50
        const val MIN_PRICE = 0.5
        const val MAX_PRICE = 15.0

        val DEFAULT = FuelSettings()

        private fun clean(v: Double, default: Double, min: Double, max: Double): Double =
            if (v.isNaN() || v.isInfinite()) default else v.coerceIn(min, max)
    }
}

/**
 * Fuel economy and gas price, kept in preferences. One per process, so Settings and the map
 * agree. Kept apart from [RoutePrefs]: changing these must not recompute routes.
 */
class FuelPrefs private constructor(context: Context) : GasPriceStore {

    private val prefs = context.applicationContext.getSharedPreferences("fuel", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())

    val settings: StateFlow<FuelSettings> = _settings.asStateFlow()

    fun setMpg(mpg: Double) = update(_settings.value.copy(mpg = mpg))

    /** The manual price, also the fallback when no fetched price is available. */
    fun setPricePerGallon(price: Double) = update(_settings.value.copy(pricePerGallon = price))

    fun setPriceSource(source: PriceSource) = update(_settings.value.copy(priceSource = source))

    fun setRegion(region: GasRegion) = update(_settings.value.copy(region = region))

    /**
     * The four things the driver chose, all in one synchronous commit (an import). The fetched price stays as it is.
     * Throws [java.io.IOException] if the write fails.
     */
    fun setChoices(mpg: Double, pricePerGallon: Double, source: PriceSource, region: GasRegion) =
        update(_settings.value.copy(mpg = mpg, pricePerGallon = pricePerGallon, priceSource = source, region = region), durable = true)

    override val region: GasRegion get() = _settings.value.region
    override val fetchedRegion: GasRegion? get() = _settings.value.fetchedRegion
    override val fetchedAtMillis: Long get() = _settings.value.fetchedAtMillis

    override fun saveFetched(price: GasPrice, region: GasRegion, atMillis: Long) = update(
        _settings.value.copy(
            fetchedPrice = price.pricePerGallon,
            fetchedPeriod = price.periodIso,
            fetchedRegion = region,
            fetchedAtMillis = atMillis,
        ),
    )

    private fun read() = FuelSettings(
        mpg = prefs.getFloat(KEY_MPG, FuelSettings.DEFAULT_MPG.toFloat()).toDouble(),
        pricePerGallon = prefs.getFloat(KEY_PRICE, FuelSettings.DEFAULT_PRICE.toFloat()).toDouble(),
        priceSource = PriceSource.entries.firstOrNull { it.name == prefs.getString(KEY_SOURCE, null) } ?: PriceSource.MANUAL,
        region = GasRegion.entries.firstOrNull { it.name == prefs.getString(KEY_REGION, null) } ?: GasRegion.NY,
        fetchedPrice = prefs.getString(KEY_FETCHED_PRICE, null)?.toDoubleOrNull(),
        fetchedPeriod = prefs.getString(KEY_FETCHED_PERIOD, null),
        fetchedRegion = GasRegion.entries.firstOrNull { it.name == prefs.getString(KEY_FETCHED_REGION, null) },
        fetchedAtMillis = prefs.getLong(KEY_FETCHED_AT, 0L),
    ).sanitized()

    @Synchronized
    private fun update(next: FuelSettings, durable: Boolean = false) {
        val clean = next.sanitized()
        val edit = prefs.edit()
        edit
            .putFloat(KEY_MPG, clean.mpg.toFloat())
            .putFloat(KEY_PRICE, clean.pricePerGallon.toFloat())
            .putString(KEY_SOURCE, clean.priceSource.name)
            .putString(KEY_REGION, clean.region.name)
            .putString(KEY_FETCHED_PRICE, clean.fetchedPrice?.toString())
            .putString(KEY_FETCHED_PERIOD, clean.fetchedPeriod)
            .putString(KEY_FETCHED_REGION, clean.fetchedRegion?.name)
            .putLong(KEY_FETCHED_AT, clean.fetchedAtMillis)
        if (durable) {
            if (!edit.commit()) throw java.io.IOException("Couldn't save the fuel options")
        } else {
            edit.apply()
        }
        _settings.value = clean
    }

    companion object {
        private const val KEY_MPG = "mpg"
        private const val KEY_PRICE = "price_per_gallon"
        private const val KEY_SOURCE = "price_source"
        private const val KEY_REGION = "region"
        private const val KEY_FETCHED_PRICE = "fetched_price"
        private const val KEY_FETCHED_PERIOD = "fetched_period"
        private const val KEY_FETCHED_REGION = "fetched_region"
        private const val KEY_FETCHED_AT = "fetched_at"

        @Volatile
        private var instance: FuelPrefs? = null

        fun get(context: Context): FuelPrefs =
            instance ?: synchronized(this) { instance ?: FuelPrefs(context.applicationContext).also { instance = it } }
    }
}
