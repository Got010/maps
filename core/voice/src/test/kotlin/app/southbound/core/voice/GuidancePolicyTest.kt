package app.southbound.core.voice

import app.southbound.core.voice.GuidanceAction.Silent
import app.southbound.core.voice.GuidanceAction.Speak
import app.southbound.core.voice.GuidanceAction.Vibrate
import app.southbound.core.voice.GuidanceMode.DRIVING
import app.southbound.core.voice.GuidanceMode.WALKING
import app.southbound.core.voice.UtteranceKind.ARRIVAL
import app.southbound.core.voice.UtteranceKind.INSTRUCTION
import app.southbound.core.voice.UtteranceKind.REROUTE
import app.southbound.core.voice.UtteranceKind.SPEED_LIMIT
import app.southbound.core.voice.UtteranceKind.TEST
import app.southbound.core.voice.UtteranceKind.TRAFFIC_ALERT
import app.southbound.core.voice.UtteranceKind.TRANSIT_STOP
import org.junit.Assert.assertEquals
import org.junit.Test

class GuidancePolicyTest {

    // Chimes are covered on their own below; without them Speak(text) compares plainly.
    private val defaults = VoiceSettings(instructionChime = false, alertChime = false)
    private val turn = "Turn left onto West 20th Street."

    private fun decide(
        settings: VoiceSettings = defaults,
        mode: GuidanceMode = DRIVING,
        kind: UtteranceKind = INSTRUCTION,
        text: String = turn,
        trigger: Double = 0.0,
        landmark: String? = null,
        followUp: String? = null,
    ) = GuidancePolicy.decide(settings, mode, kind, text, trigger, landmark, followUp)

    @Test
    fun defaultsSpeakDrivingInstructions() {
        assertEquals(Speak(turn), decide())
    }

    @Test
    fun mutedSaysNothing() {
        val muted = defaults.copy(mode = VoiceMode.MUTED)
        for (kind in listOf(INSTRUCTION, ARRIVAL, REROUTE, TRAFFIC_ALERT)) {
            assertEquals(kind.name, Silent, decide(muted, kind = kind))
        }
    }

    @Test
    fun alertsOnlySpeaksIncidentsReroutesAndArrival() {
        val alerts = defaults.copy(mode = VoiceMode.ALERTS_ONLY)
        assertEquals(Silent, decide(alerts, kind = INSTRUCTION))
        assertEquals(Speak("x"), decide(alerts, kind = ARRIVAL, text = "x"))
        assertEquals(Speak("x"), decide(alerts, kind = REROUTE, text = "x"))
        assertEquals(Speak("x"), decide(alerts, kind = TRAFFIC_ALERT, text = "x"))
        assertEquals(Silent, decide(alerts.copy(speedAlerts = SpeedAlertMode.VOICE), kind = SPEED_LIMIT))
        assertEquals(Silent, decide(alerts.copy(speedAlerts = SpeedAlertMode.BEEP), kind = SPEED_LIMIT))
        assertEquals(Silent, decide(alerts, kind = TRANSIT_STOP))
    }

    @Test
    fun perModeToggles() {
        // Walking is off by default; the rest are on.
        assertEquals(Speak(turn), decide(mode = GuidanceMode.CYCLING))
        assertEquals(Speak(turn), decide(mode = GuidanceMode.TRANSIT))
        assertEquals(Speak(turn), decide(defaults.copy(walking = true), mode = WALKING))
        assertEquals(Silent, decide(defaults.copy(driving = false)))
    }

    @Test
    fun walkingWithVoiceOffVibratesAtTurns() {
        assertEquals(Vibrate, decide(mode = WALKING, trigger = 20.0))
        // ...but not for the early warning, and not for alerts.
        assertEquals(Silent, decide(mode = WALKING, trigger = 800.0))
        assertEquals(Silent, decide(mode = WALKING, kind = REROUTE))
    }

    @Test
    fun briefSkipsEarlyWarnings() {
        val brief = defaults.copy(detail = DetailLevel.BRIEF)
        assertEquals(Silent, decide(brief, text = "In half a mile, turn left.", trigger = 800.0))
        assertEquals(Speak(turn), decide(brief, trigger = 40.0))
        assertEquals(Speak("In half a mile, turn left."), decide(text = "In half a mile, turn left.", trigger = 800.0))
    }

    @Test
    fun streetNamesOff() {
        val noNames = defaults.copy(streetNames = false)
        assertEquals(Speak("Turn left."), decide(noNames))
        assertEquals(
            Speak("In 500 feet, bear right."),
            decide(noNames, text = "In 500 feet, bear right onto the FDR Drive toward Bronx."),
        )
        assertEquals(Speak("Continue for 2 miles."), decide(noNames, text = "Continue on Broadway for 2 miles."))
    }

    @Test
    fun streetNamesOffKeepsAnAlertsDistance() {
        val noNames = defaults.copy(streetNames = false)
        // The decimal point used to end the street name: "Crash ahead.8 miles."
        assertEquals(Speak("Crash ahead in 1.8 miles."), decide(noNames, kind = TRAFFIC_ALERT, text = "Crash ahead on I 87 in 1.8 miles."))
        assertEquals(Speak("Roadwork ahead in 500 feet."), decide(noNames, kind = TRAFFIC_ALERT, text = "Roadwork ahead on Route 9 in 500 feet."))
        assertEquals(
            Speak("Road closed ahead. Finding another way."),
            decide(noNames, kind = TRAFFIC_ALERT, text = "Road closed ahead on I 87. Finding another way."),
        )
        // "Snow on your route in 12 miles." used to come out as "Snow."
        assertEquals(Speak("Snow in 12 miles."), decide(noNames, kind = TRAFFIC_ALERT, text = "Snow on your route in 12 miles."))
    }

    @Test
    fun alertToggles() {
        assertEquals(Silent, decide(defaults.copy(rerouteAnnouncement = false), kind = REROUTE))
        assertEquals(Silent, decide(defaults.copy(trafficAlerts = false), kind = TRAFFIC_ALERT))
        assertEquals(Silent, decide(kind = SPEED_LIMIT)) // off by default
        assertEquals(Speak(turn), decide(defaults.copy(speedAlerts = SpeedAlertMode.VOICE), kind = SPEED_LIMIT))
        assertEquals(Silent, decide(defaults.copy(transitStopAlerts = false), kind = TRANSIT_STOP))
    }

    @Test
    fun speedLimitAlertModes() {
        val limit = "The speed limit is 55 miles per hour."
        val off = defaults.copy(speedAlerts = SpeedAlertMode.OFF)
        val voice = defaults.copy(speedAlerts = SpeedAlertMode.VOICE)
        val beep = defaults.copy(speedAlerts = SpeedAlertMode.BEEP)
        assertEquals(SpeedAlertMode.OFF, VoiceSettings().speedAlerts)
        assertEquals(Silent, decide(off, kind = SPEED_LIMIT, text = limit))
        assertEquals(Speak(limit), decide(voice, kind = SPEED_LIMIT, text = limit))
        // Beep: a tone and no words, whatever the alert chime says.
        val beeped = Speak("", chime = ChimeKind.SPEED_BEEP)
        assertEquals(beeped, decide(beep, kind = SPEED_LIMIT, text = limit))
        assertEquals(beeped, decide(beep.copy(alertChime = true), kind = SPEED_LIMIT, text = limit))
        assertEquals(true, beeped.isChimeOnly)
        assertEquals(false, Speak("x", chime = ChimeKind.ALERT).isChimeOnly)
        assertEquals(false, Speak("").isChimeOnly)
        // Voice keeps the alert chime when that's on.
        assertEquals(
            Speak(limit, chime = ChimeKind.ALERT),
            decide(voice.copy(alertChime = true), kind = SPEED_LIMIT, text = limit),
        )
    }

    @Test
    fun speedLimitAlertsFollowTheVoiceButtonAndTravelMode() {
        val beep = defaults.copy(speedAlerts = SpeedAlertMode.BEEP)
        assertEquals(Silent, decide(beep.copy(mode = VoiceMode.MUTED), kind = SPEED_LIMIT))
        assertEquals(Silent, decide(beep.copy(driving = false), kind = SPEED_LIMIT))
        assertEquals(Silent, decide(beep, mode = WALKING, kind = SPEED_LIMIT)) // walking is off by default
    }

    @Test
    fun speedAlertModeMigratesTheOldToggle() {
        assertEquals(SpeedAlertMode.OFF, SpeedAlertMode.fromStored(null, null))
        assertEquals(SpeedAlertMode.OFF, SpeedAlertMode.fromStored(null, false))
        assertEquals(SpeedAlertMode.VOICE, SpeedAlertMode.fromStored(null, true))
        // A stored mode wins over the old toggle.
        assertEquals(SpeedAlertMode.BEEP, SpeedAlertMode.fromStored("BEEP", true))
        assertEquals(SpeedAlertMode.OFF, SpeedAlertMode.fromStored("OFF", true))
        // An unrecognised name falls back to the old toggle.
        assertEquals(SpeedAlertMode.VOICE, SpeedAlertMode.fromStored("LOUD", true))
        assertEquals(SpeedAlertMode.OFF, SpeedAlertMode.fromStored("LOUD", null))
    }

    @Test
    fun testVoiceAlwaysSpeaks() {
        assertEquals(Speak("hi"), decide(defaults.copy(mode = VoiceMode.MUTED, driving = false), kind = TEST, text = "hi"))
    }

    @Test
    fun arrivalIsRecognised() {
        assertEquals(ARRIVAL, GuidancePolicy.kindOf("You have arrived at your destination."))
        assertEquals(ARRIVAL, GuidancePolicy.kindOf("Your destination is on the right."))
        assertEquals(INSTRUCTION, GuidancePolicy.kindOf(turn))
    }

    @Test
    fun tripStartSpeaksWithTheVoiceOnOnly() {
        val start = "Starting. You'll arrive at about 1:11 PM, 12 miles."
        assertEquals(Speak(start), decide(kind = UtteranceKind.TRIP_START, text = start))
        // Street names off doesn't touch it ("at about 1:11 PM" is no street).
        assertEquals(Speak(start), decide(defaults.copy(streetNames = false), kind = UtteranceKind.TRIP_START, text = start))
        assertEquals(Silent, decide(defaults.copy(mode = VoiceMode.ALERTS_ONLY), kind = UtteranceKind.TRIP_START, text = start))
        assertEquals(Silent, decide(defaults.copy(mode = VoiceMode.MUTED), kind = UtteranceKind.TRIP_START, text = start))
        assertEquals(Speak(start), decide(VoiceSettings(), kind = UtteranceKind.TRIP_START, text = start))
    }

    @Test
    fun arrivalKeepsThePlaceName() {
        val arrived = "You have arrived at Joe's Pizza."
        assertEquals(Speak(arrived), decide(defaults.copy(streetNames = false), kind = ARRIVAL, text = arrived))
        assertEquals(Speak(arrived), decide(defaults.copy(mode = VoiceMode.ALERTS_ONLY), kind = ARRIVAL, text = arrived))
    }

    @Test
    fun hapticTurnsBuzzWithTheVoice() {
        val haptic = defaults.copy(hapticTurns = true)
        assertEquals(Speak(turn, vibrate = true), decide(haptic, trigger = 30.0))
        // Only the prompt at the turn, not the early warning or alerts.
        assertEquals(Speak(turn), decide(haptic, trigger = 800.0))
        assertEquals(Speak("x"), decide(haptic, kind = REROUTE, text = "x"))
        // Voice muted or alerts only: still buzzes at the turn.
        assertEquals(Vibrate, decide(haptic.copy(mode = VoiceMode.MUTED), trigger = 30.0))
        assertEquals(Vibrate, decide(haptic.copy(mode = VoiceMode.ALERTS_ONLY), trigger = 30.0))
        assertEquals(Silent, decide(trigger = 30.0, settings = defaults.copy(mode = VoiceMode.MUTED)))
    }

    @Test
    fun shortDistanceStyleDropsTheDistanceAtTheTurn() {
        val short = defaults.copy(distanceStyle = DistanceStyle.SHORT)
        val near = "In 200 feet, Turn left onto Elm Street."
        assertEquals(Speak("Turn left onto Elm Street."), decide(short, text = near, trigger = 60.0))
        // The early warning keeps it: that's when the distance helps.
        val early = "In a quarter mile, Turn left onto Elm Street."
        assertEquals(Speak(early), decide(short, text = early, trigger = 400.0))
        assertEquals(Speak(near), decide(text = near, trigger = 60.0))
    }

    @Test
    fun landmarkAndFollowUp() {
        assertEquals(
            Speak("Turn right after the next light onto Main Street, then turn left onto Elm Street."),
            decide(
                text = "Turn right onto Main Street.",
                landmark = "after the next light",
                followUp = "Turn left onto Elm Street.",
            ),
        )
        // Street names off: names go, the landmark stays.
        assertEquals(
            Speak("Turn right at the next light, then turn left."),
            decide(
                defaults.copy(streetNames = false),
                text = "Turn right onto Main Street.",
                landmark = "at the next light",
                followUp = "Turn left onto Elm Street.",
            ),
        )
        // Not for alerts.
        assertEquals(Speak("x"), decide(kind = REROUTE, text = "x", landmark = "at the next light"))
    }

    @Test
    fun chimes() {
        val on = VoiceSettings()
        assertEquals(Speak(turn, chime = ChimeKind.INSTRUCTION), decide(on))
        assertEquals(Speak("x", chime = ChimeKind.ALERT), decide(on, kind = TRAFFIC_ALERT, text = "x"))
        assertEquals(Speak("x", chime = ChimeKind.ALERT), decide(on, kind = UtteranceKind.HAZARD_ALERT, text = "x"))
        assertEquals(Speak("x"), decide(on, kind = REROUTE, text = "x"))
        assertEquals(Speak(turn), decide(on.copy(instructionChime = false)))
        assertEquals(Speak("x"), decide(on.copy(alertChime = false), kind = TRAFFIC_ALERT, text = "x"))
    }

    @Test
    fun hazardAlertsSpeakInAlertsOnly() {
        val alerts = defaults.copy(mode = VoiceMode.ALERTS_ONLY)
        assertEquals(Speak("x"), decide(alerts, kind = UtteranceKind.HAZARD_ALERT, text = "x"))
        assertEquals(Silent, decide(alerts.copy(mode = VoiceMode.MUTED), kind = UtteranceKind.HAZARD_ALERT, text = "x"))
    }

    @Test
    fun voiceModeCycles() {
        assertEquals(VoiceMode.ALERTS_ONLY, VoiceMode.ON.next())
        assertEquals(VoiceMode.MUTED, VoiceMode.ALERTS_ONLY.next())
        assertEquals(VoiceMode.ON, VoiceMode.MUTED.next())
    }
}
