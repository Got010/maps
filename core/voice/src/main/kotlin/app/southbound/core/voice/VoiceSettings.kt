package app.southbound.core.voice

/** The voice button on the navigation screen cycles these. */
enum class VoiceMode {
    ON,

    /** Only incidents, reroutes and arrival. */
    ALERTS_ONLY,
    MUTED,
    ;

    fun next(): VoiceMode = entries[(ordinal + 1) % entries.size]
}

/** Travel modes as far as voice is concerned; each has its own on/off toggle. */
enum class GuidanceMode { DRIVING, CYCLING, WALKING, TRANSIT }

enum class VoiceEngine {
    /** The phone's text-to-speech engine, e.g. GrapheneOS Speech Services. */
    SYSTEM,

    /** The Piper voice bundled in the app (sherpa-onnx). */
    BUILT_IN,
}

enum class DetailLevel {
    FULL,

    /** Only the instruction at the turn itself, no early "in half a mile" warnings. */
    BRIEF,
}

/** How turn prompts give distances. Valhalla words them ("In 500 feet", "In a quarter mile"). */
enum class DistanceStyle {
    FULL,

    /** The prompt right at the turn drops its distance: "Turn left onto Elm Street." */
    SHORT,
}

/**
 * The Piper voices the built-in engine can speak with. [id] names the
 * voice's folder under the `piper` assets (when [bundled]) or its pack,
 * `voice-<id>.zip`, which the updater downloads only once it's picked.
 */
enum class BuiltInVoice(val id: String, val bundled: Boolean) {
    /** US English, female (Piper "amy", low). Bundled in the APK, so it always works. */
    AMY("amy", bundled = true),

    /** US English, male (Piper "norman", medium; public domain LibriVox recordings). A download. */
    NORMAN("norman", bundled = false),
}

enum class GuidanceVolume(val gain: Float, val boostMillibels: Int) {
    SOFTER(0.55f, 0),
    NORMAL(1.0f, 0),
    LOUDER(1.0f, 800),
}

/** What happens to music or podcasts while guidance speaks. */
enum class OtherAudio { LOWER, PAUSE }

enum class AudioOutput {
    PHONE_SPEAKER,
    BLUETOOTH_MEDIA,

    /** Over the Bluetooth call channel, for car kits that only play calls during navigation. */
    BLUETOOTH_CALL,
}

/**
 * Every voice toggle in Settings > Navigation > Voice, with the defaults from
 * HANDOFF.md. Stored by [VoiceSettingsStore].
 */
data class VoiceSettings(
    val mode: VoiceMode = VoiceMode.ON,
    val driving: Boolean = true,
    val cycling: Boolean = true,
    val walking: Boolean = false,
    val transit: Boolean = true,
    /** Null means "System if one is active, else built-in". */
    val engine: VoiceEngine? = null,
    val streetNames: Boolean = true,
    val detail: DetailLevel = DetailLevel.FULL,
    val trafficAlerts: Boolean = true,
    val speedLimitWarnings: Boolean = false,
    val rerouteAnnouncement: Boolean = true,
    val transitStopAlerts: Boolean = true,
    val volume: GuidanceVolume = GuidanceVolume.NORMAL,
    val otherAudio: OtherAudio = OtherAudio.LOWER,
    val output: AudioOutput = AudioOutput.BLUETOOTH_MEDIA,
    val speechRate: Float = 1.0f,
    val distanceStyle: DistanceStyle = DistanceStyle.FULL,
    val builtInVoice: BuiltInVoice = BuiltInVoice.AMY,
    /** A short tone before each turn prompt. */
    val instructionChime: Boolean = true,
    /** A different tone before alerts (incidents, cameras, school zones, speeding). */
    val alertChime: Boolean = true,
    /** Buzz at each turn even while the voice speaks, for bikes and loud cars. */
    val hapticTurns: Boolean = false,
    val speedCameraAlerts: Boolean = true,
    /** Licence-plate readers (Flock and others), mapped in OSM by DeFlock. */
    val alprCameraAlerts: Boolean = true,
    val schoolZoneAlerts: Boolean = true,
) {
    fun enabledFor(mode: GuidanceMode): Boolean = when (mode) {
        GuidanceMode.DRIVING -> driving
        GuidanceMode.CYCLING -> cycling
        GuidanceMode.WALKING -> walking
        GuidanceMode.TRANSIT -> transit
    }

    companion object {
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f
    }
}
