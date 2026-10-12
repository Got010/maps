package app.southbound.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.network.ApiKey
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import app.southbound.core.routing.DrivingPrefs
import app.southbound.core.routing.FuelPrefs
import app.southbound.core.routing.GasPrices
import app.southbound.core.routing.PriceSource
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

// Phase 6: Settings > Driving.
@Composable
internal fun DrivingSection(network: PreferenceNetworkToggles? = null, secrets: SecretStore? = null) {
    val context = LocalContext.current
    val prefs = DrivingPrefs.get(context)
    val night by prefs.nightBySunset.collectAsState()
    val searchAtBottom by prefs.searchAtBottom.collectAsState()
    Section(R.string.settings_driving)
    Toggle(R.string.driving_night, night, R.string.driving_night_note) { prefs.setNightBySunset(it) }
    Toggle(R.string.driving_search_bottom, searchAtBottom, R.string.driving_search_bottom_note) { prefs.setSearchAtBottom(it) }
    FuelEstimate(network, secrets)
}

/** Fuel economy and gas price, for the "about $4 in gas" on each route. Nothing here goes online by itself. */
@Composable
private fun FuelEstimate(network: PreferenceNetworkToggles?, secrets: SecretStore?) {
    val fuelPrefs = FuelPrefs.get(LocalContext.current)
    val fuel by fuelPrefs.settings.collectAsState()
    val noToggles = remember { MutableStateFlow<Map<NetworkFeature, Boolean>>(emptyMap()) }
    val toggles by (network?.state ?: noToggles).collectAsState()
    val eiaOn = toggles[NetworkFeature.GAS_PRICES] == true
    // Followed as state: saving the key in Settings > API keys takes the "no key" note away at once.
    val noKeys = remember { MutableStateFlow<Set<ApiKey>>(emptySet()) }
    val savedKeys by (secrets?.saved ?: noKeys).collectAsState()

    SettingsNumberField(R.string.driving_mpg, fuel.mpg, decimals = 0) { fuelPrefs.setMpg(it) }
    SettingsNumberField(R.string.driving_gas_price, fuel.pricePerGallon, decimals = 2) { fuelPrefs.setPricePerGallon(it) }
    Text(
        stringResource(R.string.driving_fuel_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    if (!eiaOn) return

    val eia = fuel.eiaPrice
    val period = fuel.fetchedPeriod
    val status = when {
        ApiKey.EIA !in savedKeys -> stringResource(R.string.driving_eia_no_key)
        eia == null -> stringResource(R.string.driving_eia_waiting)
        else -> stringResource(
            R.string.driving_eia_price,
            String.format(Locale.US, "$%.2f", eia),
            period?.let { GasPrices.formatPeriod(it) } ?: "",
        )
    }
    Text(
        status,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Choice(
        title = R.string.driving_price_source,
        options = PriceSource.entries,
        selected = fuel.priceSource,
        label = { stringResource(if (it == PriceSource.EIA) R.string.driving_use_eia else R.string.driving_use_mine) },
    ) { fuelPrefs.setPriceSource(it) }
}
