package app.southbound.feature.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.southbound.core.export.BackupService
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.VoiceSelection
import app.southbound.core.updates.UpdateScheduler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** Screen state kept on the phone and out of backups: the tab Settings opened on last. Not a setting. */
private const val UI_PREFS = "settings_ui"
private const val KEY_TAB = "tab"

/** How long a search's jump waits for its row before letting go. */
private const val JUMP_FORGET_MS = 4_000L

/**
 * Settings, in five tabs (Voice, Driving, Live, Maps, Backup) with a search over every setting; it opens on the tab
 * used last (docs/settings-tabs.md). Changes save immediately to [store]; [voice] speaks the Test sentence. [backup]
 * adds the Backup card; [onImported] is called once an import has replaced the settings shown here, so the caller
 * can rebuild this screen.
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiPrefs = remember { context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }
    val pager = rememberPagerState(
        initialPage = remember { SettingsTab.entries.firstOrNull { it.name == uiPrefs.getString(KEY_TAB, null) }?.ordinal ?: 0 },
    ) { SettingsTab.entries.size }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { uiPrefs.edit().putString(KEY_TAB, SettingsTab.entries[it].name).apply() }
    }
    val jump = remember { SettingsJump() }
    // A jump to a row that isn't shown (a key field while its feed is off) lets go after a while.
    val target = jump.target
    LaunchedEffect(target) {
        if (target != null) {
            delay(JUMP_FORGET_MS)
            if (jump.target == target) jump.target = null
        }
    }
    // Keeps the updater's voice list in step with the setting, e.g. after a backup restore, whichever tab is open.
    val voiceSelection = remember { VoiceSelection.get(context) }
    LaunchedEffect(settings.builtInVoice) { voiceSelection.choose(settings.builtInVoice.takeUnless { it.bundled }?.id) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    fun closeSearch() {
        searching = false
        query = ""
    }
    BackHandler(enabled = searching) { closeSearch() }

    CompositionLocalProvider(LocalSettingsJump provides jump) {
        Scaffold(
            // The keyboard too: the app moves its own content clear of it (adjustResize), so a field it would cover scrolls up.
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
            topBar = {
                TopAppBar(
                    title = {
                        if (searching) SearchField(query) { query = it } else Text(stringResource(R.string.settings_title))
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (searching) closeSearch() else onBack() }) {
                            Icon(
                                Icons.Filled.ArrowBack,
                                contentDescription = stringResource(if (searching) R.string.settings_search_close else R.string.settings_back),
                            )
                        }
                    },
                    actions = {
                        if (!searching) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.settings_search))
                            }
                        } else if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_search_clear))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (searching) {
                    SearchResults(query) { entry ->
                        closeSearch()
                        jump.target = entry.title
                        scope.launch { pager.scrollToPage(entry.tab.ordinal) }
                    }
                } else {
                    Tabs(pager) { page -> scope.launch { pager.animateScrollToPage(page) } }
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 32.dp)) {
                            when (SettingsTab.entries[page]) {
                                SettingsTab.VOICE -> VoiceTab(store, settings, voice)
                                SettingsTab.DRIVING -> DrivingSection(network, secrets)
                                SettingsTab.LIVE -> if (network != null && secrets != null) LiveDataTab(network, secrets)
                                SettingsTab.MAPS -> {
                                    if (network != null && secrets != null) UpdatesSection(network, secrets)
                                    CoverageSection()
                                }
                                SettingsTab.BACKUP -> {
                                    if (backup != null) BackupSection(backup, onImported)
                                    JumpTarget(R.string.settings_about) {
                                        SettingsCard(R.string.settings_about) {
                                            Text(
                                                stringResource(R.string.settings_about_body),
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The tabs: an icon over each name, the open one underlined. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tabs(pager: PagerState, onSelect: (Int) -> Unit) {
    PrimaryTabRow(selectedTabIndex = pager.currentPage) {
        for (tab in SettingsTab.entries) {
            Tab(
                selected = pager.currentPage == tab.ordinal,
                onClick = { onSelect(tab.ordinal) },
                text = { Text(stringResource(tab.title), maxLines = 1) },
                icon = { Icon(tab.icon, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.settings_search)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** What matches the search, each with where it lives ("Voice › Alerts"); picking one jumps there. */
@Composable
private fun SearchResults(query: String, onPick: (SettingEntry) -> Unit) {
    val resources = LocalResources.current
    val results = remember(query, resources) { SettingsIndex.search(query) { resources.getString(it) } }
    val hint = when {
        query.isBlank() -> stringResource(R.string.settings_search_empty)
        results.isEmpty() -> stringResource(R.string.settings_search_none, query.trim())
        else -> null
    }
    if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.title }) { entry ->
            val where = listOfNotNull(stringResource(entry.tab.title), entry.place?.let { stringResource(it) }).joinToString(" › ")
            ListItem(
                headlineContent = { Text(stringResource(entry.title)) },
                supportingContent = { Text(where) },
                modifier = Modifier.clickable { onPick(entry) },
            )
        }
    }
}

/**
 * Settings > Voice: what is changed often on top, then the alerts and the rest of the voice options in two closed
 * groups, each saying what's set inside (owner, October 2026: "Essentials + More").
 */
@Composable
private fun VoiceTab(store: VoiceSettingsStore, settings: VoiceSettings, voice: GuidanceVoice) {
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

    SettingsCard(R.string.settings_voice) {
        Choice(R.string.voice_guidance, VoiceMode.entries, settings.mode, { stringResource(it.label()) }) { m ->
            update { it.copy(mode = m) }
        }
        Choice(R.string.voice_volume, GuidanceVolume.entries, settings.volume, { stringResource(it.label()) }) { v ->
            update { it.copy(volume = v) }
        }
        JumpTarget(R.string.voice_rate) {
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
                colors = rowColors(),
            )
        }
        Toggle(R.string.voice_street_names, settings.streetNames) { v -> update { it.copy(streetNames = v) } }
        JumpTarget(R.string.voice_test) {
            Button(onClick = { voice.test() }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null)
                Text(stringResource(R.string.voice_test), Modifier.padding(start = 8.dp))
            }
        }
    }

    SettingsGroup(
        title = R.string.settings_voice_alerts,
        summary = alertsSummary(settings),
        titles = SettingsIndex.entries.filter { it.place == R.string.settings_voice_alerts }.map { it.title }.toSet(),
    ) {
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
    }

    val chimes = settings.instructionChime || settings.alertChime
    SettingsGroup(
        title = R.string.settings_voice_more,
        summary = listOf(
            stringResource(engineLabel(settings.engine)),
            stringResource(settings.builtInVoice.label()),
            stringResource(if (chimes) R.string.settings_chimes_on else R.string.settings_chimes_off),
        ).joinToString(" · "),
        titles = SettingsIndex.entries.filter { it.place == R.string.settings_voice_more }.map { it.title }.toSet(),
    ) {
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
            menu = true,
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
            menu = true,
            onSelect = ::pickVoice,
        )
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
        Choice(R.string.voice_other_audio, OtherAudio.entries, settings.otherAudio, { stringResource(it.label()) }) { o ->
            update { it.copy(otherAudio = o) }
        }
        Choice(R.string.voice_output, AudioOutput.entries, settings.output, { stringResource(it.label()) }, menu = true) { o ->
            update { it.copy(output = o) }
        }
    }
}

/** The alerts that are on, by name ("Speed cameras, plate readers, school zones"), or "All off". */
@Composable
private fun alertsSummary(settings: VoiceSettings): String {
    val on = listOfNotNull(
        R.string.settings_alerts_traffic.takeIf { settings.trafficAlerts },
        R.string.settings_alerts_speed.takeIf { settings.speedAlerts != SpeedAlertMode.OFF },
        R.string.settings_alerts_cameras.takeIf { settings.speedCameraAlerts },
        R.string.settings_alerts_readers.takeIf { settings.alprCameraAlerts },
        R.string.settings_alerts_schools.takeIf { settings.schoolZoneAlerts },
        R.string.settings_alerts_reroutes.takeIf { settings.rerouteAnnouncement },
        R.string.settings_alerts_transit.takeIf { settings.transitStopAlerts },
    ).map { stringResource(it) }
    return if (on.isEmpty()) {
        stringResource(R.string.settings_alerts_none)
    } else {
        on.joinToString(", ").replaceFirstChar { it.titlecase(Locale.getDefault()) }
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
