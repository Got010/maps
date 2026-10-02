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

    private val defaults = VoiceSettings()
    private val turn = "Turn left onto West 20th Street."

    private fun decide(
        settings: VoiceSettings = defaults,
        mode: GuidanceMode = DRIVING,
        kind: UtteranceKind = INSTRUCTION,
        text: String = turn,
        trigger: Double = 0.0,
    ) = GuidancePolicy.decide(settings, mode, kind, text, trigger)

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
        assertEquals(Silent, decide(alerts.copy(speedLimitWarnings = true), kind = SPEED_LIMIT))
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
    fun alertToggles() {
        assertEquals(Silent, decide(defaults.copy(rerouteAnnouncement = false), kind = REROUTE))
        assertEquals(Silent, decide(defaults.copy(trafficAlerts = false), kind = TRAFFIC_ALERT))
        assertEquals(Silent, decide(kind = SPEED_LIMIT)) // off by default
        assertEquals(Speak(turn), decide(defaults.copy(speedLimitWarnings = true), kind = SPEED_LIMIT))
        assertEquals(Silent, decide(defaults.copy(transitStopAlerts = false), kind = TRANSIT_STOP))
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
    fun voiceModeCycles() {
        assertEquals(VoiceMode.ALERTS_ONLY, VoiceMode.ON.next())
        assertEquals(VoiceMode.MUTED, VoiceMode.ALERTS_ONLY.next())
        assertEquals(VoiceMode.ON, VoiceMode.MUTED.next())
    }
}
