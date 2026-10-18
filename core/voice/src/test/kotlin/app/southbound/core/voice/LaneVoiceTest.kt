package app.southbound.core.voice

import app.southbound.core.voice.GuidanceAction.Speak
import app.southbound.core.voice.TurnTiming.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Lane advice in turn prompts: which prompt says it, and how it joins the sentence. */
class LaneVoiceTest {

    private val defaults = VoiceSettings(instructionChime = false, alertChime = false)

    @Test
    fun theClosestEarlyPromptCarriesTheLanes() {
        // Valhalla's usual step: progress at the start, an alert, the "now" prompt.
        val step = listOf(
            Prompt("Continue on I 90 for 3 miles.", 4800.0),
            Prompt("In 1 mile, Take exit 5 on the right toward Lakeview.", 1609.0),
            Prompt("In a half mile, Take exit 5 on the right toward Lakeview.", 805.0),
            Prompt("Take exit 5 on the right toward Lakeview.", 60.0),
        )
        assertEquals(2, LanePrompt.indexFor(step))
        assertEquals(
            0,
            LanePrompt.indexFor(
                listOf(Prompt("In 500 feet, Turn left onto Elm Street.", 152.0), Prompt("Turn left onto Elm Street.", 20.0)),
            ),
        )
    }

    @Test
    fun noEarlyPromptNoLanes() {
        // Only the "now" prompt: the lanes are only drawn.
        assertNull(LanePrompt.indexFor(listOf(Prompt("Turn left onto Elm Street.", 20.0))))
        // A progress prompt isn't about the turn.
        assertNull(
            LanePrompt.indexFor(listOf(Prompt("Continue for 2 miles.", 3200.0), Prompt("Turn left onto Elm Street.", 20.0))),
        )
        assertNull(LanePrompt.indexFor(emptyList()))
    }

    @Test
    fun lanesJoinTheSentence() {
        assertEquals(
            "In 500 feet, Turn left onto Elm Street, use the left two lanes.",
            InstructionText.withLanes("In 500 feet, Turn left onto Elm Street.", "use the left two lanes"),
        )
        assertEquals(
            "In a half mile, Take exit 5 on the right, use the far right lane.",
            InstructionText.withLanes("In a half mile, Take exit 5 on the right", "Use the far right lane."),
        )
        // Valhalla's own "Then" sentence is about the next turn: the advice goes before it.
        assertEquals(
            "Turn left onto Elm Street, use the left lane. Then turn right onto Oak Street.",
            InstructionText.withLanes("Turn left onto Elm Street. Then turn right onto Oak Street.", "use the left lane"),
        )
        // Said once only.
        assertEquals(
            "Use the right lane to take exit 3.",
            InstructionText.withLanes("Use the right lane to take exit 3.", "use the right lane"),
        )
        assertEquals("Turn left.", InstructionText.withLanes("Turn left.", "  "))
    }

    @Test
    fun lanesComposeWithLandmarkAndFollowUp() {
        assertEquals(
            Speak("In 500 feet, Turn left after the next light onto Elm Street, use the left two lanes, then turn right onto Oak Street."),
            GuidancePolicy.decide(
                defaults,
                GuidanceMode.DRIVING,
                UtteranceKind.INSTRUCTION,
                "In 500 feet, Turn left onto Elm Street.",
                triggerDistanceMeters = 400.0,
                landmark = "after the next light",
                followUp = "Turn right onto Oak Street.",
                lanes = "use the left two lanes",
            ),
        )
        // Street names off: names go (with "on the right", as before), the lanes stay.
        assertEquals(
            Speak("In a half mile, Take exit 5, use the far right lane."),
            GuidancePolicy.decide(
                defaults.copy(streetNames = false),
                GuidanceMode.DRIVING,
                UtteranceKind.INSTRUCTION,
                "In a half mile, Take exit 5 on the right toward Lakeview.",
                triggerDistanceMeters = 805.0,
                lanes = "use the far right lane",
            ),
        )
        // Not for alerts.
        assertEquals(
            Speak("Rerouting."),
            GuidancePolicy.decide(defaults, GuidanceMode.DRIVING, UtteranceKind.REROUTE, "Rerouting.", lanes = "use the left lane"),
        )
    }

    @Test
    fun theThenChainStillWorksWithoutLanes() {
        assertEquals(
            Speak("Turn right onto Main Street, then turn left onto Elm Street."),
            GuidancePolicy.decide(
                defaults,
                GuidanceMode.DRIVING,
                UtteranceKind.INSTRUCTION,
                "Turn right onto Main Street.",
                followUp = "Turn left onto Elm Street.",
                lanes = null,
            ),
        )
    }

    @Test
    fun aChainedTurnKeepsItsLanes() {
        // Off an exit and onto a four-lane road, then a left at the light 250 m on: the left turn is said with the
        // ramp's prompt, and its own early prompt (which carried the lanes) is skipped. Its lanes go with it.
        assertEquals(
            Speak("Turn right onto Main Street, then turn left onto Elm Street, use the far left lane."),
            GuidancePolicy.decide(
                defaults,
                GuidanceMode.DRIVING,
                UtteranceKind.INSTRUCTION,
                "Turn right onto Main Street.",
                triggerDistanceMeters = 60.0,
                followUp = "Turn left onto Elm Street.",
                followUpLanes = "use the far left lane",
            ),
        )
        // Both turns with lanes: each keeps its own.
        assertEquals(
            Speak("In 500 feet, Turn right onto Main Street, use the right lane, then turn left onto Elm Street, use the far left lane."),
            GuidancePolicy.decide(
                defaults,
                GuidanceMode.DRIVING,
                UtteranceKind.INSTRUCTION,
                "In 500 feet, Turn right onto Main Street.",
                triggerDistanceMeters = 152.0,
                followUp = "Turn left onto Elm Street.",
                lanes = "use the right lane",
                followUpLanes = "use the far left lane",
            ),
        )
    }

    @Test
    fun theEarlyExitPromptNamesTheNumberAndTheLanes() {
        assertEquals(
            Speak("In 2 miles, Take exit 5 on the right, use the far right lane."),
            GuidancePolicy.decide(
                defaults,
                GuidanceMode.DRIVING,
                UtteranceKind.INSTRUCTION,
                "In 2 miles, Take the exit on the right.",
                triggerDistanceMeters = 3200.0,
                lanes = "use the far right lane",
                exitNumber = "5",
            ),
        )
    }
}
