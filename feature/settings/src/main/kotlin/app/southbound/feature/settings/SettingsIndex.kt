package app.southbound.feature.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/** Settings' tabs, in order (owner, October 2026: "Tabs across the top", "Icon + label tabs"). */
internal enum class SettingsTab(@StringRes val title: Int) {
    VOICE(R.string.settings_tab_voice),
    DRIVING(R.string.settings_tab_driving),
    LIVE(R.string.settings_tab_live),
    MAPS(R.string.settings_tab_maps),
    BACKUP(R.string.settings_tab_backup),
    ;

    /** The tab's icon, over its name. */
    val icon: ImageVector
        get() = when (this) {
            VOICE -> Icons.Filled.RecordVoiceOver
            DRIVING -> Icons.Filled.DirectionsCar
            LIVE -> Icons.Filled.Sensors
            MAPS -> Icons.Filled.Map
            BACKUP -> Icons.Filled.Backup
        }
}

/** A setting search can find: its title, its tab, and the card or group it sits in ([place]), for "Voice › Alerts". */
internal data class SettingEntry(@StringRes val title: Int, val tab: SettingsTab, @StringRes val place: Int? = null)

/**
 * Every setting by its title, for Settings' search. `SettingsIndexTest` checks that each titled control in this
 * module's sources is here, so a new switch can't be left out of search.
 */
internal object SettingsIndex {
    private fun on(tab: SettingsTab, place: Int?, vararg titles: Int) = titles.map { SettingEntry(it, tab, place) }

    val entries: List<SettingEntry> = on(
        SettingsTab.VOICE, null,
        R.string.voice_guidance, R.string.voice_volume, R.string.voice_rate, R.string.voice_street_names, R.string.voice_test,
    ) + on(
        SettingsTab.VOICE, R.string.settings_voice_alerts,
        R.string.voice_traffic, R.string.voice_speed_limits, R.string.voice_speed_cameras, R.string.voice_alpr_cameras,
        R.string.voice_school_zones, R.string.voice_reroute, R.string.voice_transit_stops,
    ) + on(
        SettingsTab.VOICE, R.string.settings_voice_more,
        R.string.voice_driving, R.string.voice_cycling, R.string.voice_walking, R.string.voice_transit,
        R.string.voice_engine, R.string.voice_built_in_voice, R.string.voice_detail, R.string.voice_distance_style,
        R.string.voice_instruction_chime, R.string.voice_alert_chime, R.string.voice_haptic_turns,
        R.string.voice_other_audio, R.string.voice_output,
    ) + on(
        SettingsTab.DRIVING, R.string.settings_driving,
        R.string.driving_night, R.string.driving_search_bottom, R.string.driving_state_cards,
    ) + on(
        SettingsTab.DRIVING, R.string.driving_fuel_heading,
        R.string.driving_mpg, R.string.driving_gas_price, R.string.driving_price_source,
    ) + on(
        SettingsTab.LIVE, R.string.live_transit_heading,
        R.string.live_transit, R.string.live_bus, R.string.live_bus_key,
    ) + on(
        SettingsTab.LIVE, R.string.live_traffic,
        R.string.live_511, R.string.live_511pa, R.string.live_ctroads, R.string.live_chart_md, R.string.live_deldot,
        R.string.live_ohgo, R.string.live_ohgo_key, R.string.live_511in, R.string.live_travel_midwest, R.string.live_midrive,
        R.string.live_511wi, R.string.live_511mn, R.string.live_511ia, R.string.live_nddot, R.string.live_sd511,
        R.string.live_511ne, R.string.live_kandrive, R.string.live_511va, R.string.live_wv511, R.string.live_kytc, R.string.live_dot,
    ) + on(
        SettingsTab.LIVE, R.string.live_weather_heading,
        R.string.live_weather,
    ) + on(
        SettingsTab.LIVE, R.string.live_fuel,
        R.string.live_gas, R.string.live_gas_key, R.string.live_gas_state,
    ) + on(
        SettingsTab.MAPS, R.string.settings_updates,
        R.string.updates_check_now, R.string.updates_packs, R.string.updates_app, R.string.updates_mobile, R.string.updates_token,
    ) + on(
        SettingsTab.MAPS, null,
        R.string.regions_title, R.string.wifi_pack_title, R.string.road_pack_title, R.string.updates_packs_installed, R.string.coverage_title,
    ) + on(
        SettingsTab.BACKUP, R.string.backup_title,
        R.string.backup_export, R.string.backup_import,
    ) + on(
        SettingsTab.BACKUP, null,
        R.string.settings_about,
    )

    /**
     * The entries whose title or place holds every word of [query], in the order they appear in Settings; [text]
     * turns a string resource into its words. Nothing for a blank query.
     */
    fun search(query: String, text: (Int) -> String): List<SettingEntry> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        return entries.filter { entry ->
            val said = words(text(entry.title) + " " + (entry.place?.let(text) ?: "") + " " + text(entry.tab.title))
            words.all { word -> said.any { it.startsWith(word) } }
        }
    }

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    private fun words(s: String): List<String> = s.lowercase(Locale.ROOT).split(NOT_WORD).filter { it.isNotEmpty() }
}
