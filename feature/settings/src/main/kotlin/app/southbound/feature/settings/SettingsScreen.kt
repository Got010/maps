package app.southbound.feature.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.southbound.core.network.ApiKey
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.voice.AudioOutput
import app.southbound.core.voice.DetailLevel
import app.southbound.core.voice.GuidanceVoice
import app.southbound.core.voice.GuidanceVolume
import app.southbound.core.voice.OtherAudio
import app.southbound.core.voice.VoiceEngine
import app.southbound.core.voice.VoiceMode
import app.southbound.core.voice.VoiceSettings
import app.southbound.core.voice.VoiceSettingsStore
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings > Navigation > Voice, every toggle from HANDOFF.md, plus About.
 * Changes save immediately to [store]; [voice] speaks the Test sentence.
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
) {
    val scope = rememberCoroutineScope()
    fun update(transform: (VoiceSettings) -> VoiceSettings) {
        scope.launch { store.update(transform) }
    }
    var systemEngine by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(voice) { systemEngine = voice.systemEngineAvailable() }

    Scaffold(
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

            Toggle(R.string.voice_street_names, settings.streetNames) { v -> update { it.copy(streetNames = v) } }
            Choice(R.string.voice_detail, DetailLevel.entries, settings.detail, { stringResource(it.label()) }) { d ->
                update { it.copy(detail = d) }
            }
            Toggle(R.string.voice_traffic, settings.trafficAlerts) { v -> update { it.copy(trafficAlerts = v) } }
            Toggle(R.string.voice_speed_limits, settings.speedLimitWarnings) { v -> update { it.copy(speedLimitWarnings = v) } }
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
                            onValueChange = { r -> update { it.copy(speechRate = (r * 10).toInt() / 10f) } },
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
            DrivingSection()

            if (network != null && secrets != null) {
                HorizontalDivider(Modifier.padding(top = 24.dp))
                UpdatesSection(network, secrets)
                HorizontalDivider(Modifier.padding(top = 24.dp))
                LiveDataSection(network, secrets)
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
    KeyField(secrets, ApiKey.NY_511, R.string.live_511_key, R.string.live_511_key_saved)
    Toggle(R.string.live_dot, toggles.getValue(NetworkFeature.TRAFFIC_NYC_DOT), R.string.live_dot_note) {
        network.set(NetworkFeature.TRAFFIC_NYC_DOT, it)
    }
    Text(
        stringResource(R.string.live_traffic_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A password-style field that saves an API key encrypted, with Remove once one is saved. */
@Composable
private fun KeyField(secrets: SecretStore, key: ApiKey, @StringRes label: Int, @StringRes savedLabel: Int) {
    var hasKey by remember { mutableStateOf(secrets.has(key)) }
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
            if (hasKey) {
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
private fun <T> Choice(
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
