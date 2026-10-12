package app.southbound.core.voice

/** Why something is about to be said. Each kind obeys its own toggles. */
enum class UtteranceKind {
    /** A turn instruction from the route. */
    INSTRUCTION,

    /** "You have arrived". */
    ARRIVAL,
    REROUTE,
    TRAFFIC_ALERT,

    /** A speed camera, licence-plate reader or school zone ahead, from the pack. */
    HAZARD_ALERT,
    SPEED_LIMIT,
    TRANSIT_STOP,

    /** "Starting. You'll arrive at about 1:11 PM, 12 miles.", once as a trip starts. */
    TRIP_START,

    /** The Test voice button: always speaks. */
    TEST,
}

/** What to do with one utterance. */
sealed interface GuidanceAction {
    /** [vibrate]: buzz as well (Haptic turns). [chime]: the tone to play first, if any. */
    data class Speak(val text: String, val vibrate: Boolean = false, val chime: ChimeKind? = null) : GuidanceAction {
        /** A tone with nothing to say, e.g. the speed limit beep. */
        val isChimeOnly: Boolean get() = text.isEmpty() && chime != null
    }

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

    /**
     * [landmark] ("after the next light") and [followUp] (the next turn's
     * sentence when it comes right after this one) only apply to route
     * instructions; see [InstructionText]. So does [lanes], the lane advice
     * ("use the left two lanes") for the turn this prompt announces.
     */
    fun decide(
        settings: VoiceSettings,
        mode: GuidanceMode,
        kind: UtteranceKind,
        text: String,
        triggerDistanceMeters: Double = 0.0,
        landmark: String? = null,
        followUp: String? = null,
        lanes: String? = null,
        followUpLanes: String? = null,
        /** The exit's number, said on every prompt for it, not only the last ([InstructionText.withExitNumber]). */
        exitNumber: String? = null,
    ): GuidanceAction {
        if (kind == UtteranceKind.TEST) return GuidanceAction.Speak(text, chime = chimeFor(settings, kind))
        if (kind == UtteranceKind.SPEED_LIMIT) return speedLimitAction(settings, mode, text)
        val modeOn = settings.enabledFor(mode)
        val voiceOn = modeOn && settings.mode != VoiceMode.MUTED
        val atTurn = triggerDistanceMeters <= BRIEF_MAX_TRIGGER_METERS
        val wanted = when (kind) {
            UtteranceKind.INSTRUCTION -> settings.mode == VoiceMode.ON &&
                (settings.detail == DetailLevel.FULL || atTurn)
            UtteranceKind.ARRIVAL -> true
            UtteranceKind.REROUTE -> settings.rerouteAnnouncement
            UtteranceKind.TRAFFIC_ALERT -> settings.trafficAlerts
            // Which alerts (cameras, school zones) is decided by the app, per toggle.
            UtteranceKind.HAZARD_ALERT -> true
            UtteranceKind.SPEED_LIMIT -> false // decided above, by its own mode
            UtteranceKind.TRANSIT_STOP -> settings.transitStopAlerts && settings.mode == VoiceMode.ON
            UtteranceKind.TRIP_START -> settings.mode == VoiceMode.ON
            UtteranceKind.TEST -> true
        }
        // The prompt at the turn itself buzzes with Haptic turns on, voice or not.
        val buzz = kind == UtteranceKind.INSTRUCTION && atTurn && (settings.hapticTurns ||
            // HANDOFF: walking with voice off falls back to vibration cues at turns.
            (mode == GuidanceMode.WALKING && !modeOn))
        return when {
            voiceOn && wanted -> GuidanceAction.Speak(
                compose(settings, kind, text, atTurn, landmark, followUp, lanes, followUpLanes, exitNumber),
                vibrate = buzz,
                chime = chimeFor(settings, kind),
            )
            buzz -> GuidanceAction.Vibrate
            else -> GuidanceAction.Silent
        }
    }

    /**
     * Speed limit alerts: off, spoken (with the alert chime if that's on), or
     * a double beep and no words. They follow the voice button like turn
     * prompts do: muted or alerts-only silences them, beeps included.
     */
    private fun speedLimitAction(settings: VoiceSettings, mode: GuidanceMode, text: String): GuidanceAction {
        if (settings.mode != VoiceMode.ON || !settings.enabledFor(mode)) return GuidanceAction.Silent
        return when (settings.speedAlerts) {
            SpeedAlertMode.OFF -> GuidanceAction.Silent
            SpeedAlertMode.VOICE -> GuidanceAction.Speak(
                if (settings.streetNames) text else withoutStreetNames(text),
                chime = chimeFor(settings, UtteranceKind.SPEED_LIMIT),
            )
            SpeedAlertMode.BEEP -> GuidanceAction.Speak("", chime = ChimeKind.SPEED_BEEP)
        }
    }

    /** The sentence as spoken: street names and distance per the settings, then landmark, lanes and follow-up. */
    private fun compose(
        settings: VoiceSettings,
        kind: UtteranceKind,
        text: String,
        atTurn: Boolean,
        landmark: String?,
        followUp: String?,
        lanes: String? = null,
        followUpLanes: String? = null,
        exitNumber: String? = null,
    ): String {
        fun plain(sentence: String): String {
            var out = if (settings.streetNames) sentence else withoutStreetNames(sentence)
            if (settings.distanceStyle == DistanceStyle.SHORT && atTurn) out = InstructionText.withoutLeadingDistance(out)
            return out
        }
        // The trip's start and arrival name a time and a place, not a street to turn onto.
        if (kind == UtteranceKind.TRIP_START || kind == UtteranceKind.ARRIVAL) return text
        if (kind != UtteranceKind.INSTRUCTION) return if (settings.streetNames) text else withoutStreetNames(text)
        var out = plain(text)
        if (exitNumber != null) out = InstructionText.withExitNumber(out, exitNumber)
        if (landmark != null) out = InstructionText.withLandmark(out, landmark)
        if (lanes != null) out = InstructionText.withLanes(out, lanes)
        if (followUp != null) {
            // The second turn's lanes ride with it: its own early prompt is skipped once it is chained.
            val next = plain(followUp).let { if (followUpLanes != null) InstructionText.withLanes(it, followUpLanes) else it }
            out = InstructionText.withFollowUp(out, next)
        }
        return out
    }

    /** Turn prompts get the instruction tone, warnings the alert tone, each behind its toggle. */
    fun chimeFor(settings: VoiceSettings, kind: UtteranceKind): ChimeKind? = when (kind) {
        UtteranceKind.INSTRUCTION, UtteranceKind.ARRIVAL, UtteranceKind.TEST ->
            ChimeKind.INSTRUCTION.takeIf { settings.instructionChime }
        UtteranceKind.TRAFFIC_ALERT, UtteranceKind.HAZARD_ALERT, UtteranceKind.SPEED_LIMIT ->
            ChimeKind.ALERT.takeIf { settings.alertChime }
        UtteranceKind.REROUTE, UtteranceKind.TRANSIT_STOP, UtteranceKind.TRIP_START -> null
    }

    /**
     * Valhalla sentences carry street names after "onto", "on", "toward" or "at", and so do the app's alerts ("Crash
     * ahead on I 87 in 1.8 miles."). A name ends at "for" or "in" and a number (the distance stays), at a comma or
     * semicolon, or at a full stop that isn't a decimal point.
     */
    private val STREET_TAIL = Regex(
        """\s+(onto|on|toward|towards|at|to stay on)\s+.+?(?=\s+for\s|\s+in\s+\d|[,;]|\.(?!\d)|$)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * "Turn left onto West 20th Street." -> "Turn left."; "Crash ahead on I 87 in 1.8 miles." -> "Crash ahead in 1.8
     * miles."; leaves distances such as "In 500 feet," and road-free sentences alone.
     */
    fun withoutStreetNames(text: String): String =
        STREET_TAIL.replace(text, "").replace(Regex("""\s+([.,;])"""), "$1").trim()

    private val ARRIVAL = Regex("""\b(arrived|destination)\b""", RegexOption.IGNORE_CASE)

    /** Ferrostar marks every route sentence alike; arrival is told apart by its words. */
    fun kindOf(routeText: String): UtteranceKind =
        if (ARRIVAL.containsMatchIn(routeText)) UtteranceKind.ARRIVAL else UtteranceKind.INSTRUCTION
}
