package app.southbound.feature.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.southbound.core.packs.Coverage
import app.southbound.core.export.BackupService
import app.southbound.core.network.ApiKey
import app.southbound.core.routing.FuelPrefs
import app.southbound.core.routing.GasRegion
import app.southbound.core.routing.PriceSource
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.voice.AudioOutput
import app.southbound.core.voice.BuiltInVoice
import app.southbound.core.voice.DetailLevel
import app.southbound.core.voice.DistanceStyle
import app.southbound.core.voice.GuidanceVoice
import app.southbound.core.voice.GuidanceVolume
import app.southbound.core.voice.OtherAudio
import app.southbound.core.voice.SpeedAlertMode
import app.southbound.core.voice.VoiceEngine
import app.southbound.core.voice.VoiceMode
import app.southbound.core.voice.VoiceSettings
import app.southbound.core.voice.VoiceSettingsStore
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.VoiceSelection
import app.southbound.core.updates.UpdateScheduler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Settings > Navigation > Voice, every toggle from HANDOFF.md, plus About.
 * Changes save immediately to [store]; [voice] speaks the Test sentence. [backup] adds the
 * Backup section; [onImported] is called once an import has replaced the settings shown here,
 * so the caller can rebuild this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: VoiceSettingsStore,
    settings: VoiceSettings,
    voice: GuidanceVoice,
    onBack: () -> Unit,
    network: PreferenceNetworkToggles? = null,
    secrets: SecretStore? = null,
    backup: BackupService? = null,
    onImported: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Writes outlive the screen: Back cancels its scope, and a slider drag's last writes may still be queued then.
    fun update(transform: (VoiceSettings) -> VoiceSettings) {
        scope.launch(NonCancellable) { store.update(transform) }
    }
    var systemEngine by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(voice) { systemEngine = voice.systemEngineAvailable() }

    // Downloadable voices: which are on the phone, and which one the updater should fetch.
    val context = LocalContext.current
    val voiceSelection = remember { VoiceSelection.get(context) }
    var installedVoices by remember { mutableStateOf<Set<BuiltInVoice>>(emptySet()) }
    suspend fun refreshVoices() {
        installedVoices = withContext(Dispatchers.IO) { BuiltInVoice.entries.filter { voice.isVoiceInstalled(it) }.toSet() }
    }
    LaunchedEffect(voice) {
        refreshVoices()
        PackEvents.installed.collect { if (it.kind == PackKind.VOICE) refreshVoices() }
    }
    // Keeps the updater's list in step with the setting, e.g. after a backup restore.
    LaunchedEffect(settings.builtInVoice) { voiceSelection.choose(settings.builtInVoice.takeUnless { it.bundled }?.id) }
    fun pickVoice(picked: BuiltInVoice) {
        val previous = settings.builtInVoice
        update { it.copy(builtInVoice = picked) }
        voiceSelection.choose(picked.takeUnless { it.bundled }?.id)
        if (!picked.bundled && picked !in installedVoices) UpdateScheduler.checkNow(context, anyNetwork = false)
        if (previous != picked && !previous.bundled) {
            // Unpicked: give its space back. Picking it again downloads it again: picked back while this ran (Norman,
            // Amy, Norman), the voice now chosen is missing, so its download is queued here.
            scope.launch(Dispatchers.IO + NonCancellable) {
                voice.deleteVoice(previous)
                PackEvents.emitRemoved(PackKind.VOICE, previous.id)
                val chosen = BuiltInVoice.entries.firstOrNull { it.id in voiceSelection.voices.value }
                if (chosen != null && !voice.isVoiceInstalled(chosen)) UpdateScheduler.checkNow(context, anyNetwork = false)
                refreshVoices()
            }
        }
    }

    Scaffold(
        // The keyboard too: the app moves its own content clear of it (adjustResize), so a field it would cover scrolls up.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            Section(R.string.settings_voice)
            Choice(
                R.string.voice_guidance,
                VoiceMode.entries,
                settings.mode,
                { stringResource(it.label()) },
            ) { m -> update { it.copy(mode = m) } }

            Subheading(R.string.voice_per_mode)
            Toggle(R.string.voice_driving, settings.driving) { v -> update { it.copy(driving = v) } }
            Toggle(R.string.voice_cycling, settings.cycling) { v -> update { it.copy(cycling = v) } }
            Toggle(R.string.voice_walking, settings.walking, R.string.voice_walking_note) { v -> update { it.copy(walking = v) } }
            Toggle(R.string.voice_transit, settings.transit) { v -> update { it.copy(transit = v) } }

            Choice(
                R.string.voice_engine,
                listOf<VoiceEngine?>(null, VoiceEngine.SYSTEM, VoiceEngine.BUILT_IN),
                settings.engine,
                { stringResource(engineLabel(it)) },
                note = stringResource(
                    when (systemEngine) {
                        null -> R.string.voice_engine_checking
                        true -> R.string.voice_engine_system_found
                        false -> R.string.voice_engine_system_missing
                    },
                ),
            ) { e -> update { it.copy(engine = e) } }
            Choice(
                R.string.voice_built_in_voice,
                BuiltInVoice.entries,
                settings.builtInVoice,
                { stringResource(it.label()) },
                note = when {
                    settings.builtInVoice.bundled -> stringResource(R.string.voice_built_in_voice_note)
                    settings.builtInVoice in installedVoices -> stringResource(R.string.voice_pack_installed)
                    else -> stringResource(R.string.voice_pack_not_downloaded)
                },
                onSelect = ::pickVoice,
            )

            Toggle(R.string.voice_street_names, settings.streetNames) { v -> update { it.copy(streetNames = v) } }
            Choice(R.string.voice_detail, DetailLevel.entries, settings.detail, { stringResource(it.label()) }) { d ->
                update { it.copy(detail = d) }
            }
            Choice(
                R.string.voice_distance_style,
                DistanceStyle.entries,
                settings.distanceStyle,
                { stringResource(it.label()) },
                note = stringResource(R.string.voice_distance_style_note),
            ) { d -> update { it.copy(distanceStyle = d) } }
            Toggle(R.string.voice_instruction_chime, settings.instructionChime) { v -> update { it.copy(instructionChime = v) } }
            Toggle(R.string.voice_alert_chime, settings.alertChime) { v -> update { it.copy(alertChime = v) } }
            Toggle(R.string.voice_haptic_turns, settings.hapticTurns, R.string.voice_haptic_turns_note) { v ->
                update { it.copy(hapticTurns = v) }
            }
            Toggle(R.string.voice_traffic, settings.trafficAlerts) { v -> update { it.copy(trafficAlerts = v) } }
            Choice(
                R.string.voice_speed_limits,
                SpeedAlertMode.entries,
                settings.speedAlerts,
                { stringResource(it.label()) },
                note = stringResource(R.string.voice_speed_limits_note),
            ) { m -> update { it.copy(speedAlerts = m) } }
            Subheading(R.string.voice_road_alerts)
            Toggle(R.string.voice_speed_cameras, settings.speedCameraAlerts) { v -> update { it.copy(speedCameraAlerts = v) } }
            Toggle(R.string.voice_alpr_cameras, settings.alprCameraAlerts, R.string.voice_alpr_cameras_note) { v ->
                update { it.copy(alprCameraAlerts = v) }
            }
            Toggle(R.string.voice_school_zones, settings.schoolZoneAlerts) { v -> update { it.copy(schoolZoneAlerts = v) } }
            Toggle(R.string.voice_reroute, settings.rerouteAnnouncement) { v -> update { it.copy(rerouteAnnouncement = v) } }
            Toggle(R.string.voice_transit_stops, settings.transitStopAlerts) { v -> update { it.copy(transitStopAlerts = v) } }
            Choice(R.string.voice_volume, GuidanceVolume.entries, settings.volume, { stringResource(it.label()) }) { v ->
                update { it.copy(volume = v) }
            }
            Choice(R.string.voice_other_audio, OtherAudio.entries, settings.otherAudio, { stringResource(it.label()) }) { o ->
                update { it.copy(otherAudio = o) }
            }
            Choice(R.string.voice_output, AudioOutput.entries, settings.output, { stringResource(it.label()) }) { o ->
                update { it.copy(output = o) }
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.voice_rate)) },
                supportingContent = {
                    Column {
                        Text(String.format(Locale.US, "%.1f×", settings.speechRate))
                        Slider(
                            value = settings.speechRate,
                            // To the nearest tenth: a tick can land at 1.1999, which truncating stored as 1.1.
                            onValueChange = { r -> update { it.copy(speechRate = (r * 10).roundToInt() / 10f) } },
                            valueRange = VoiceSettings.MIN_SPEECH_RATE..VoiceSettings.MAX_SPEECH_RATE,
                            steps = 14,
                        )
                    }
                },
            )
            Button(onClick = { voice.test() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null)
                Text(stringResource(R.string.voice_test), Modifier.padding(start = 8.dp))
            }

            // Phase 6: driving options.
            HorizontalDivider(Modifier.padding(top = 24.dp))
            DrivingSection(network, secrets)

            if (network != null && secrets != null) {
                HorizontalDivider(Modifier.padding(top = 24.dp))
                UpdatesSection(network, secrets)
                HorizontalDivider(Modifier.padding(top = 24.dp))
                CoverageSection()
                HorizontalDivider(Modifier.padding(top = 24.dp))
                LiveDataSection(network, secrets)
            }

            if (backup != null) {
                HorizontalDivider(Modifier.padding(top = 24.dp))
                BackupSection(backup, onImported)
            }

            HorizontalDivider(Modifier.padding(top = 24.dp))
            Section(R.string.settings_about)
            Text(
                stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Online extras, each off until turned on. Requests fetch whole feeds, so
 * they never carry your location, searches or routes.
 */
@Composable
private fun LiveDataSection(network: PreferenceNetworkToggles, secrets: SecretStore) {
    val toggles by network.state.collectAsState()
    Section(R.string.settings_live)
    Text(
        stringResource(R.string.settings_live_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Toggle(R.string.live_transit, toggles.getValue(NetworkFeature.TRANSIT_REALTIME), R.string.live_transit_note) {
        network.set(NetworkFeature.TRANSIT_REALTIME, it)
    }
    Toggle(R.string.live_bus, toggles.getValue(NetworkFeature.TRANSIT_BUS_TIME), R.string.live_bus_note) {
        network.set(NetworkFeature.TRANSIT_BUS_TIME, it)
    }
    KeyField(secrets, ApiKey.MTA_BUS_TIME, R.string.live_bus_key, R.string.live_bus_key_saved)

    Subheading(R.string.live_traffic)
    Toggle(R.string.live_511, toggles.getValue(NetworkFeature.TRAFFIC_511NY), R.string.live_511_note) {
        network.set(NetworkFeature.TRAFFIC_511NY, it)
    }
    Toggle(R.string.live_511pa, toggles.getValue(NetworkFeature.TRAFFIC_511PA), R.string.live_511pa_note) {
        network.set(NetworkFeature.TRAFFIC_511PA, it)
    }
    Toggle(R.string.live_ctroads, toggles.getValue(NetworkFeature.TRAFFIC_CTROADS), R.string.live_ctroads_note) {
        network.set(NetworkFeature.TRAFFIC_CTROADS, it)
    }
    Toggle(R.string.live_chart_md, toggles.getValue(NetworkFeature.TRAFFIC_CHART_MD), R.string.live_chart_md_note) {
        network.set(NetworkFeature.TRAFFIC_CHART_MD, it)
    }
    Toggle(R.string.live_deldot, toggles.getValue(NetworkFeature.TRAFFIC_DELDOT), R.string.live_deldot_note) {
        network.set(NetworkFeature.TRAFFIC_DELDOT, it)
    }
    Toggle(R.string.live_511wi, toggles.getValue(NetworkFeature.TRAFFIC_511WI), R.string.live_511wi_note) {
        network.set(NetworkFeature.TRAFFIC_511WI, it)
    }
    Toggle(R.string.live_travel_midwest, toggles.getValue(NetworkFeature.TRAFFIC_TRAVEL_MIDWEST), R.string.live_travel_midwest_note) {
        network.set(NetworkFeature.TRAFFIC_TRAVEL_MIDWEST, it)
    }
    Toggle(R.string.live_nddot, toggles.getValue(NetworkFeature.TRAFFIC_NDDOT), R.string.live_nddot_note) {
        network.set(NetworkFeature.TRAFFIC_NDDOT, it)
    }
    Toggle(R.string.live_sd511, toggles.getValue(NetworkFeature.TRAFFIC_SD511), R.string.live_sd511_note) {
        network.set(NetworkFeature.TRAFFIC_SD511, it)
    }
    Toggle(R.string.live_511in, toggles.getValue(NetworkFeature.TRAFFIC_511IN), R.string.live_511in_note) {
        network.set(NetworkFeature.TRAFFIC_511IN, it)
    }
    Toggle(R.string.live_511mn, toggles.getValue(NetworkFeature.TRAFFIC_511MN), R.string.live_511mn_note) {
        network.set(NetworkFeature.TRAFFIC_511MN, it)
    }
    Toggle(R.string.live_511ia, toggles.getValue(NetworkFeature.TRAFFIC_511IA), R.string.live_511ia_note) {
        network.set(NetworkFeature.TRAFFIC_511IA, it)
    }
    Toggle(R.string.live_511ne, toggles.getValue(NetworkFeature.TRAFFIC_511NE), R.string.live_511ne_note) {
        network.set(NetworkFeature.TRAFFIC_511NE, it)
    }
    Toggle(R.string.live_kandrive, toggles.getValue(NetworkFeature.TRAFFIC_KANDRIVE), R.string.live_kandrive_note) {
        network.set(NetworkFeature.TRAFFIC_KANDRIVE, it)
    }
    Toggle(R.string.live_midrive, toggles.getValue(NetworkFeature.TRAFFIC_MIDRIVE), R.string.live_midrive_note) {
        network.set(NetworkFeature.TRAFFIC_MIDRIVE, it)
    }
    Toggle(R.string.live_ohgo, toggles.getValue(NetworkFeature.TRAFFIC_OHGO), R.string.live_ohgo_note) {
        network.set(NetworkFeature.TRAFFIC_OHGO, it)
    }
    if (toggles.getValue(NetworkFeature.TRAFFIC_OHGO)) KeyField(secrets, ApiKey.OHGO, R.string.live_ohgo_key, R.string.live_ohgo_key_saved)
    Toggle(R.string.live_dot, toggles.getValue(NetworkFeature.TRAFFIC_NYC_DOT), R.string.live_dot_note) {
        network.set(NetworkFeature.TRAFFIC_NYC_DOT, it)
    }
    Text(
        stringResource(R.string.live_traffic_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    // The states whose traffic isn't here, so an empty traffic layer there is never a mystery.
    Text(
        Coverage.summary().first(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    // Left from older versions and shown only while set, so they can be cleared and stop riding along in every
    // export: the TomTom toggle (only a backup can turn it on; no version has the layer) and the 511NY key (511NY
    // has needed none since October 2026).
    if (toggles.getValue(NetworkFeature.TRAFFIC_TOMTOM)) {
        Toggle(R.string.live_tomtom_old, true, R.string.live_tomtom_old_note) { network.set(NetworkFeature.TRAFFIC_TOMTOM, it) }
    }
    val savedKeys by secrets.saved.collectAsState()
    if (ApiKey.NY_511 in savedKeys) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_511_key_old), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { secrets.set(ApiKey.NY_511, null) }) { Text(stringResource(R.string.live_key_remove)) }
        }
    }

    Subheading(R.string.live_weather_heading)
    Toggle(R.string.live_weather, toggles.getValue(NetworkFeature.WEATHER), R.string.live_weather_note) {
        network.set(NetworkFeature.WEATHER, it)
    }

    // Gas price for the fuel estimate on route cards. The state is a choice made here, never worked out from GPS.
    val fuelPrefs = FuelPrefs.get(LocalContext.current)
    val fuel by fuelPrefs.settings.collectAsState()
    Subheading(R.string.live_fuel)
    Toggle(R.string.live_gas, toggles.getValue(NetworkFeature.GAS_PRICES), R.string.live_gas_note) { on ->
        network.set(NetworkFeature.GAS_PRICES, on)
        // Off means the typed-in price again, even though an old fetched one is kept.
        if (!on) fuelPrefs.setPriceSource(PriceSource.MANUAL)
    }
    KeyField(secrets, ApiKey.EIA, R.string.live_gas_key, R.string.live_gas_key_saved)
    if (toggles.getValue(NetworkFeature.GAS_PRICES)) {
        Choice(
            title = R.string.live_gas_state,
            options = GasRegion.entries,
            selected = fuel.region,
            label = { it.name },
            note = if (fuel.region == GasRegion.NJ) stringResource(R.string.live_gas_nj_note) else null,
        ) { fuelPrefs.setRegion(it) }
    }
}

/** A password-style field that saves an API key encrypted, with Remove once one is saved. */
@Composable
private fun KeyField(secrets: SecretStore, key: ApiKey, @StringRes label: Int, @StringRes savedLabel: Int) {
    // Saved and readable; a saved key the Keystore can't open asks to be typed again, and can still be removed.
    var hasKey by remember { mutableStateOf(secrets.has(key)) }
    val saved by secrets.saved.collectAsState()
    var draft by remember { mutableStateOf("") }
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

@Composable
internal fun Section(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Subheading(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
    )
}

@Composable
internal fun Toggle(@StringRes title: Int, checked: Boolean, @StringRes note: Int? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = note?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
internal fun <T> Choice(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    note: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label(option), maxLines = 1) }
            }
        }
        note?.let {
            Row { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun VoiceMode.label() = when (this) {
    VoiceMode.ON -> R.string.voice_on
    VoiceMode.ALERTS_ONLY -> R.string.voice_alerts_only
    VoiceMode.MUTED -> R.string.voice_muted
}

private fun engineLabel(engine: VoiceEngine?) = when (engine) {
    null -> R.string.voice_engine_auto
    VoiceEngine.SYSTEM -> R.string.voice_engine_system
    VoiceEngine.BUILT_IN -> R.string.voice_engine_built_in
}

private fun DetailLevel.label() = when (this) {
    DetailLevel.FULL -> R.string.voice_detail_full
    DetailLevel.BRIEF -> R.string.voice_detail_brief
}

private fun BuiltInVoice.label() = when (this) {
    BuiltInVoice.AMY -> R.string.voice_built_in_amy
    BuiltInVoice.NORMAN -> R.string.voice_built_in_norman
}

private fun SpeedAlertMode.label() = when (this) {
    SpeedAlertMode.OFF -> R.string.voice_speed_off
    SpeedAlertMode.VOICE -> R.string.voice_speed_voice
    SpeedAlertMode.BEEP -> R.string.voice_speed_beep
}

private fun DistanceStyle.label() = when (this) {
    DistanceStyle.FULL -> R.string.voice_distance_full
    DistanceStyle.SHORT -> R.string.voice_distance_short
}

private fun GuidanceVolume.label() = when (this) {
    GuidanceVolume.SOFTER -> R.string.voice_volume_softer
    GuidanceVolume.NORMAL -> R.string.voice_volume_normal
    GuidanceVolume.LOUDER -> R.string.voice_volume_louder
}

private fun OtherAudio.label() = when (this) {
    OtherAudio.LOWER -> R.string.voice_other_lower
    OtherAudio.PAUSE -> R.string.voice_other_pause
}

private fun AudioOutput.label() = when (this) {
    AudioOutput.PHONE_SPEAKER -> R.string.voice_output_speaker
    AudioOutput.BLUETOOTH_MEDIA -> R.string.voice_output_bt_media
    AudioOutput.BLUETOOTH_CALL -> R.string.voice_output_bt_call
}
