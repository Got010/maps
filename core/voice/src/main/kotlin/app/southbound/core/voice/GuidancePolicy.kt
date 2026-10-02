package app.southbound.core.voice

/** Why something is about to be said. Each kind obeys its own toggles. */
enum class UtteranceKind {
    /** A turn instruction from the route. */
    INSTRUCTION,

    /** "You have arrived". */
    ARRIVAL,
    REROUTE,
    TRAFFIC_ALERT,
    SPEED_LIMIT,
    TRANSIT_STOP,

    /** The Test voice button: always speaks. */
    TEST,
}

/** What to do with one utterance. */
sealed interface GuidanceAction {
    data class Speak(val text: String) : GuidanceAction

    /** Walking with voice off: buzz at the turn instead. */
    data object Vibrate : GuidanceAction
    data object Silent : GuidanceAction
}

/**
 * Applies the voice toggles to one utterance. Pure, so every toggle is unit
 * tested; GuidanceVoice does the speaking.
 */
object GuidancePolicy {

    /** Early warnings ("In half a mile, ...") are further out than this; Brief skips them. */
    const val BRIEF_MAX_TRIGGER_METERS = 250.0

    fun decide(
        settings: VoiceSettings,
        mode: GuidanceMode,
        kind: UtteranceKind,
        text: String,
        triggerDistanceMeters: Double = 0.0,
    ): GuidanceAction {
        if (kind == UtteranceKind.TEST) return GuidanceAction.Speak(text)
        val modeOn = settings.enabledFor(mode)
        val voiceOn = modeOn && settings.mode != VoiceMode.MUTED
        val wanted = when (kind) {
            UtteranceKind.INSTRUCTION -> settings.mode == VoiceMode.ON &&
                (settings.detail == DetailLevel.FULL || triggerDistanceMeters <= BRIEF_MAX_TRIGGER_METERS)
            UtteranceKind.ARRIVAL -> true
            UtteranceKind.REROUTE -> settings.rerouteAnnouncement
            UtteranceKind.TRAFFIC_ALERT -> settings.trafficAlerts
            UtteranceKind.SPEED_LIMIT -> settings.speedLimitWarnings && settings.mode == VoiceMode.ON
            UtteranceKind.TRANSIT_STOP -> settings.transitStopAlerts && settings.mode == VoiceMode.ON
            UtteranceKind.TEST -> true
        }
        return when {
            voiceOn && wanted -> GuidanceAction.Speak(if (settings.streetNames) text else withoutStreetNames(text))
            // HANDOFF: walking with voice off falls back to vibration cues at turns.
            mode == GuidanceMode.WALKING && !modeOn && kind == UtteranceKind.INSTRUCTION &&
                triggerDistanceMeters <= BRIEF_MAX_TRIGGER_METERS -> GuidanceAction.Vibrate
            else -> GuidanceAction.Silent
        }
    }

    /** Valhalla sentences carry street names after "onto", "on", "toward" or "at". */
    private val STREET_TAIL = Regex(
        """\s+(onto|on|toward|towards|at|to stay on)\s+.+?(?=\s+for\s|[.,;]|$)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Turn left onto West 20th Street." -> "Turn left."; leaves distances such
     * as "In 500 feet," and road-free sentences alone.
     */
    fun withoutStreetNames(text: String): String =
        STREET_TAIL.replace(text, "").replace(Regex("""\s+([.,;])"""), "$1").trim()

    private val ARRIVAL = Regex("""\b(arrived|destination)\b""", RegexOption.IGNORE_CASE)

    /** Ferrostar marks every route sentence alike; arrival is told apart by its words. */
    fun kindOf(routeText: String): UtteranceKind =
        if (ARRIVAL.containsMatchIn(routeText)) UtteranceKind.ARRIVAL else UtteranceKind.INSTRUCTION
}
