package app.southbound.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.southbound.core.network.ApiKey
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import app.southbound.core.packs.Coverage
import app.southbound.core.packs.RegionSelection
import app.southbound.core.routing.FuelPrefs
import app.southbound.core.routing.GasRegion
import app.southbound.core.routing.PriceSource

/** A state's traffic feed in Settings: its switch, what it does, and the key it needs, if any. */
private class TrafficFeed(
    val state: String,
    val feature: NetworkFeature,
    @StringRes val title: Int,
    @StringRes val note: Int,
    val key: ApiKey? = null,
    @StringRes val keyLabel: Int = 0,
    @StringRes val keySaved: Int = 0,
)

/** Each state's feed, by [Coverage.State.code]. New Jersey, DC and Missouri have none ([Coverage]). */
private val TRAFFIC_FEEDS = listOf(
    TrafficFeed("NY", NetworkFeature.TRAFFIC_511NY, R.string.live_511, R.string.live_511_note),
    TrafficFeed("CT", NetworkFeature.TRAFFIC_CTROADS, R.string.live_ctroads, R.string.live_ctroads_note),
    TrafficFeed("PA", NetworkFeature.TRAFFIC_511PA, R.string.live_511pa, R.string.live_511pa_note),
    TrafficFeed("MD", NetworkFeature.TRAFFIC_CHART_MD, R.string.live_chart_md, R.string.live_chart_md_note),
    TrafficFeed("DE", NetworkFeature.TRAFFIC_DELDOT, R.string.live_deldot, R.string.live_deldot_note),
    TrafficFeed(
        "OH", NetworkFeature.TRAFFIC_OHGO, R.string.live_ohgo, R.string.live_ohgo_note,
        ApiKey.OHGO, R.string.live_ohgo_key, R.string.live_ohgo_key_saved,
    ),
    TrafficFeed("IN", NetworkFeature.TRAFFIC_511IN, R.string.live_511in, R.string.live_511in_note),
    TrafficFeed("IL", NetworkFeature.TRAFFIC_TRAVEL_MIDWEST, R.string.live_travel_midwest, R.string.live_travel_midwest_note),
    TrafficFeed("MI", NetworkFeature.TRAFFIC_MIDRIVE, R.string.live_midrive, R.string.live_midrive_note),
    TrafficFeed("WI", NetworkFeature.TRAFFIC_511WI, R.string.live_511wi, R.string.live_511wi_note),
    TrafficFeed("MN", NetworkFeature.TRAFFIC_511MN, R.string.live_511mn, R.string.live_511mn_note),
    TrafficFeed("IA", NetworkFeature.TRAFFIC_511IA, R.string.live_511ia, R.string.live_511ia_note),
    TrafficFeed("ND", NetworkFeature.TRAFFIC_NDDOT, R.string.live_nddot, R.string.live_nddot_note),
    TrafficFeed("SD", NetworkFeature.TRAFFIC_SD511, R.string.live_sd511, R.string.live_sd511_note),
    TrafficFeed("NE", NetworkFeature.TRAFFIC_511NE, R.string.live_511ne, R.string.live_511ne_note),
    TrafficFeed("KS", NetworkFeature.TRAFFIC_KANDRIVE, R.string.live_kandrive, R.string.live_kandrive_note),
)

/**
 * Settings > Live: online extras, each off until turned on. Requests fetch whole feeds, so they never carry your
 * location, searches or routes. Traffic is by state, the states of the phone's map regions first and the rest in
 * one closed group (owner, October 2026: "By state, mine first"); a key field shows while its feed is on or a key
 * is saved, so a saved key can always be removed.
 */
@Composable
internal fun LiveDataTab(network: PreferenceNetworkToggles, secrets: SecretStore) {
    val toggles by network.state.collectAsState()
    val savedKeys by secrets.saved.collectAsState()
    val context = LocalContext.current
    val selection = remember { RegionSelection.get(context) }
    val regions by selection.regions.collectAsState()

    CardNote(stringResource(R.string.settings_live_note))

    SettingsCard(R.string.live_transit_heading) {
        Toggle(R.string.live_transit, toggles.getValue(NetworkFeature.TRANSIT_REALTIME), R.string.live_transit_note) {
            network.set(NetworkFeature.TRANSIT_REALTIME, it)
        }
        Toggle(R.string.live_bus, toggles.getValue(NetworkFeature.TRANSIT_BUS_TIME), R.string.live_bus_note) {
            network.set(NetworkFeature.TRANSIT_BUS_TIME, it)
        }
        if (toggles.getValue(NetworkFeature.TRANSIT_BUS_TIME) || ApiKey.MTA_BUS_TIME in savedKeys) {
            KeyField(secrets, ApiKey.MTA_BUS_TIME, R.string.live_bus_key, R.string.live_bus_key_saved)
        }
    }

    val mine = Coverage.statesWithRegions(regions)
    val others = Coverage.STATES.filter { it !in mine }
    SettingsCard(R.string.live_traffic) {
        if (mine.isEmpty()) {
            for (state in others) StateTraffic(state, toggles, savedKeys, network, secrets)
        } else {
            Subheading(R.string.live_your_states)
            for (state in mine) StateTraffic(state, toggles, savedKeys, network, secrets)
            val otherFeeds = TRAFFIC_FEEDS.filter { feed -> others.any { it.code == feed.state } }
            if (others.isNotEmpty()) {
                SettingsGroup(
                    title = R.string.live_other_states,
                    summary = stringResource(
                        R.string.live_other_states_on,
                        otherFeeds.count { toggles.getValue(it.feature) },
                        otherFeeds.size,
                    ),
                    titles = otherFeeds.flatMap { listOf(it.title, it.keyLabel) }.toSet(),
                    inCard = true,
                ) {
                    for (state in others) StateTraffic(state, toggles, savedKeys, network, secrets)
                }
            }
        }
        Toggle(R.string.live_dot, toggles.getValue(NetworkFeature.TRAFFIC_NYC_DOT), R.string.live_dot_note) {
            network.set(NetworkFeature.TRAFFIC_NYC_DOT, it)
        }
        CardNote(stringResource(R.string.live_traffic_hint))
        // Left from older versions and shown only while set, so they can be cleared and stop riding along in every
        // export: the TomTom toggle (only a backup can turn it on; no version has the layer) and the 511NY key (511NY
        // has needed none since October 2026).
        if (toggles.getValue(NetworkFeature.TRAFFIC_TOMTOM)) {
            Toggle(R.string.live_tomtom_old, true, R.string.live_tomtom_old_note) { network.set(NetworkFeature.TRAFFIC_TOMTOM, it) }
        }
        if (ApiKey.NY_511 in savedKeys) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_511_key_old), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { secrets.set(ApiKey.NY_511, null) }) { Text(stringResource(R.string.live_key_remove)) }
            }
        }
    }

    SettingsCard(R.string.live_weather_heading) {
        Toggle(R.string.live_weather, toggles.getValue(NetworkFeature.WEATHER), R.string.live_weather_note) {
            network.set(NetworkFeature.WEATHER, it)
        }
    }

    // Gas price for the fuel estimate on route cards. The state is a choice made here, never worked out from GPS.
    val fuelPrefs = FuelPrefs.get(context)
    val fuel by fuelPrefs.settings.collectAsState()
    val gasOn = toggles.getValue(NetworkFeature.GAS_PRICES)
    SettingsCard(R.string.live_fuel) {
        Toggle(R.string.live_gas, gasOn, R.string.live_gas_note) { on ->
            network.set(NetworkFeature.GAS_PRICES, on)
            // Off means the typed-in price again, even though an old fetched one is kept.
            if (!on) fuelPrefs.setPriceSource(PriceSource.MANUAL)
        }
        if (gasOn || ApiKey.EIA in savedKeys) KeyField(secrets, ApiKey.EIA, R.string.live_gas_key, R.string.live_gas_key_saved)
        if (gasOn) {
            Choice(
                title = R.string.live_gas_state,
                options = GasRegion.entries,
                selected = fuel.region,
                label = { it.name },
                note = if (fuel.region == GasRegion.NJ) stringResource(R.string.live_gas_nj_note) else null,
            ) { fuelPrefs.setRegion(it) }
        }
    }
}

/** One state's traffic: its feed's switch (and key), or why it has none. */
@Composable
private fun StateTraffic(
    state: Coverage.State,
    toggles: Map<NetworkFeature, Boolean>,
    savedKeys: Set<ApiKey>,
    network: PreferenceNetworkToggles,
    secrets: SecretStore,
) {
    val feed = TRAFFIC_FEEDS.firstOrNull { it.state == state.code }
    if (feed == null) {
        val why = state.entry(Coverage.Item.INCIDENTS).why
        ListItem(
            headlineContent = { Text(state.name) },
            supportingContent = why?.let { { Note(stringResource(R.string.live_no_feed, it)) } },
            colors = rowColors(),
        )
        return
    }
    val on = toggles.getValue(feed.feature)
    Toggle(feed.title, on, feed.note) { network.set(feed.feature, it) }
    val key = feed.key
    if (key != null && (on || key in savedKeys)) KeyField(secrets, key, feed.keyLabel, feed.keySaved)
}

/** A password-style field that saves an API key encrypted, with Remove once one is saved. */
@Composable
internal fun KeyField(secrets: SecretStore, key: ApiKey, @StringRes label: Int, @StringRes savedLabel: Int) {
    // Saved and readable; a saved key the Keystore can't open asks to be typed again, and can still be removed.
    var hasKey by remember { mutableStateOf(secrets.has(key)) }
    val saved by secrets.saved.collectAsState()
    var draft by remember { mutableStateOf("") }
    JumpTarget(label) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(if (hasKey) savedLabel else label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    secrets.set(key, draft)
                    hasKey = secrets.has(key)
                    draft = ""
                }, enabled = draft.isNotBlank()) { Text(stringResource(R.string.live_key_save)) }
                if (hasKey || key in saved) {
                    TextButton(onClick = {
                        secrets.set(key, null)
                        hasKey = false
                    }) { Text(stringResource(R.string.live_key_remove)) }
                }
            }
        }
    }
}
