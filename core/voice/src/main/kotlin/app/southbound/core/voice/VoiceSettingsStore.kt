package app.southbound.core.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore(name = "voice")

/** Voice settings in DataStore, on the device only. */
class VoiceSettingsStore(context: Context) {

    private val store = context.applicationContext.voiceDataStore

    val settings: Flow<VoiceSettings> = store.data.map { it.toSettings() }

    suspend fun update(transform: (VoiceSettings) -> VoiceSettings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    private object Keys {
        val mode = stringPreferencesKey("mode")
        val driving = booleanPreferencesKey("driving")
        val cycling = booleanPreferencesKey("cycling")
        val walking = booleanPreferencesKey("walking")
        val transit = booleanPreferencesKey("transit")
        val engine = stringPreferencesKey("engine")
        val streetNames = booleanPreferencesKey("street_names")
        val detail = stringPreferencesKey("detail")
        val trafficAlerts = booleanPreferencesKey("traffic_alerts")
        /** Before speed alerts had a mode this was an on/off; still read once to carry the choice over. */
        val speedLimitWarningsLegacy = booleanPreferencesKey("speed_limit_warnings")
        val speedAlertMode = stringPreferencesKey("speed_alert_mode")
        val rerouteAnnouncement = booleanPreferencesKey("reroute_announcement")
        val transitStopAlerts = booleanPreferencesKey("transit_stop_alerts")
        val volume = stringPreferencesKey("volume")
        val otherAudio = stringPreferencesKey("other_audio")
        val output = stringPreferencesKey("output")
        val speechRate = floatPreferencesKey("speech_rate")
        val distanceStyle = stringPreferencesKey("distance_style")
        val builtInVoice = stringPreferencesKey("built_in_voice")
        val instructionChime = booleanPreferencesKey("instruction_chime")
        val alertChime = booleanPreferencesKey("alert_chime")
        val hapticTurns = booleanPreferencesKey("haptic_turns")
        val speedCameraAlerts = booleanPreferencesKey("speed_camera_alerts")
        val alprCameraAlerts = booleanPreferencesKey("alpr_camera_alerts")
        val schoolZoneAlerts = booleanPreferencesKey("school_zone_alerts")
    }

    private fun Preferences.toSettings(): VoiceSettings {
        val d = VoiceSettings()
        return VoiceSettings(
            mode = enumOr(Keys.mode, d.mode),
            driving = this[Keys.driving] ?: d.driving,
            cycling = this[Keys.cycling] ?: d.cycling,
            walking = this[Keys.walking] ?: d.walking,
            transit = this[Keys.transit] ?: d.transit,
            engine = this[Keys.engine]?.let { name -> VoiceEngine.entries.firstOrNull { it.name == name } },
            streetNames = this[Keys.streetNames] ?: d.streetNames,
            detail = enumOr(Keys.detail, d.detail),
            trafficAlerts = this[Keys.trafficAlerts] ?: d.trafficAlerts,
            speedAlerts = SpeedAlertMode.fromStored(this[Keys.speedAlertMode], this[Keys.speedLimitWarningsLegacy]),
            rerouteAnnouncement = this[Keys.rerouteAnnouncement] ?: d.rerouteAnnouncement,
            transitStopAlerts = this[Keys.transitStopAlerts] ?: d.transitStopAlerts,
            volume = enumOr(Keys.volume, d.volume),
            otherAudio = enumOr(Keys.otherAudio, d.otherAudio),
            output = enumOr(Keys.output, d.output),
            speechRate = this[Keys.speechRate] ?: d.speechRate,
            distanceStyle = enumOr(Keys.distanceStyle, d.distanceStyle),
            builtInVoice = enumOr(Keys.builtInVoice, d.builtInVoice),
            instructionChime = this[Keys.instructionChime] ?: d.instructionChime,
            alertChime = this[Keys.alertChime] ?: d.alertChime,
            hapticTurns = this[Keys.hapticTurns] ?: d.hapticTurns,
            speedCameraAlerts = this[Keys.speedCameraAlerts] ?: d.speedCameraAlerts,
            alprCameraAlerts = this[Keys.alprCameraAlerts] ?: d.alprCameraAlerts,
            schoolZoneAlerts = this[Keys.schoolZoneAlerts] ?: d.schoolZoneAlerts,
        )
    }

    private fun VoiceSettings.writeTo(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[Keys.mode] = mode.name
        prefs[Keys.driving] = driving
        prefs[Keys.cycling] = cycling
        prefs[Keys.walking] = walking
        prefs[Keys.transit] = transit
        if (engine == null) prefs.remove(Keys.engine) else prefs[Keys.engine] = engine.name
        prefs[Keys.streetNames] = streetNames
        prefs[Keys.detail] = detail.name
        prefs[Keys.trafficAlerts] = trafficAlerts
        prefs[Keys.speedAlertMode] = speedAlerts.name
        prefs.remove(Keys.speedLimitWarningsLegacy)
        prefs[Keys.rerouteAnnouncement] = rerouteAnnouncement
        prefs[Keys.transitStopAlerts] = transitStopAlerts
        prefs[Keys.volume] = volume.name
        prefs[Keys.otherAudio] = otherAudio.name
        prefs[Keys.output] = output.name
        prefs[Keys.speechRate] = speechRate
        prefs[Keys.distanceStyle] = distanceStyle.name
        prefs[Keys.builtInVoice] = builtInVoice.name
        prefs[Keys.instructionChime] = instructionChime
        prefs[Keys.alertChime] = alertChime
        prefs[Keys.hapticTurns] = hapticTurns
        prefs[Keys.speedCameraAlerts] = speedCameraAlerts
        prefs[Keys.alprCameraAlerts] = alprCameraAlerts
        prefs[Keys.schoolZoneAlerts] = schoolZoneAlerts
    }

    private inline fun <reified E : Enum<E>> Preferences.enumOr(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
