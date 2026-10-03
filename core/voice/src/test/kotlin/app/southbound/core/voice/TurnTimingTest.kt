package app.southbound.core.voice

import app.southbound.core.voice.TurnTiming.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTimingTest {

    // A step the way Valhalla's prompts come through Ferrostar: what the road
    // does at the step's start, an early warning, and the prompt at the turn.
    private val exitStep = listOf(
        Prompt("Continue on I 87 North for 2 miles.", 3_200.0),
        Prompt("In a quarter mile, Take exit 14S on the right.", 400.0),
        Prompt("Take exit 14S on the right.", 25.0),
    )

    @Test
    fun chainsWithinFourHundredMetresOrTwentySeconds() {
        assertEquals(400.0, TurnTiming.chainGapMeters(null), 0.0)
        // In town: the 400 m floor.
        assertTrue(TurnTiming.chains(380.0, 10.0))
        assertFalse(TurnTiming.chains(450.0, 10.0))
        // At 30 m/s (67 mph) 20 s is 600 m.
        assertEquals(600.0, TurnTiming.chainGapMeters(30.0), 1e-9)
        assertTrue(TurnTiming.chains(550.0, 30.0))
        assertFalse(TurnTiming.chains(650.0, 30.0))
        // No speed reading: the floor alone.
        assertTrue(TurnTiming.chains(400.0, null))
        assertFalse(TurnTiming.chains(401.0, Double.NaN))
    }

    @Test
    fun walkersAndCyclistsChainOnlyCloseTurns() {
        assertTrue(TurnTiming.chains(140.0, 1.4, GuidanceMode.WALKING))
        assertFalse(TurnTiming.chains(300.0, 1.4, GuidanceMode.WALKING))
        assertFalse(TurnTiming.chains(300.0, 5.0, GuidanceMode.CYCLING))
        assertTrue(TurnTiming.chains(190.0, 10.0, GuidanceMode.CYCLING))
    }

    @Test
    fun progressPromptsAreNotManeuvers() {
        assertTrue(TurnTiming.isProgress("Continue for 2 miles."))
        assertTrue(TurnTiming.isProgress("Continue on I 87 North for 1.5 miles."))
        assertTrue(TurnTiming.isProgress("Continue for a half mile."))
        assertTrue(TurnTiming.isProgress("Drive north on Main Street."))
        assertTrue(TurnTiming.isProgress("Head southwest on Broadway for 500 feet."))
        assertFalse(TurnTiming.isProgress("Continue straight onto Main Street."))
        assertFalse(TurnTiming.isProgress("Turn left onto Elm Street."))
        assertFalse(TurnTiming.isProgress("Take exit 14S on the right."))
        assertFalse(TurnTiming.isProgress("Keep left to take I 87 North toward Albany."))
    }

    @Test
    fun theNowPromptIsTheClosestInManeuverPrompt() {
        assertEquals(2, TurnTiming.nowIndex(exitStep))
        // Order doesn't matter; "Continue for" never counts.
        assertEquals(
            0,
            TurnTiming.nowIndex(listOf(Prompt("Turn left.", 30.0), Prompt("Continue for 300 feet.", 10.0))),
        )
        assertNull(TurnTiming.nowIndex(listOf(Prompt("Continue for 2 miles.", 3_000.0))))
        assertNull(TurnTiming.nowIndex(emptyList()))
    }

    @Test
    fun afterAChainOnlyTheNowPromptIsSaid() {
        val light = listOf(
            Prompt("In 1,000 feet, Turn left onto Route 9W.", 380.0),
            Prompt("Turn left onto Route 9W.", 20.0),
        )
        assertEquals(setOf(0), TurnTiming.skippedAfterChain(light))
        assertEquals(setOf(0, 1), TurnTiming.skippedAfterChain(exitStep))
        assertEquals(setOf(0), TurnTiming.skippedAfterChain(listOf(Prompt("Continue for 2 miles.", 3_000.0))))
    }

    @Test
    fun tooLateOnlyAtSpeed() {
        assertTrue(TurnTiming.tooLate(10.0, 25.0))
        assertTrue(TurnTiming.tooLate(0.0, 5.0))
        assertFalse(TurnTiming.tooLate(15.0, 25.0))
        assertFalse(TurnTiming.tooLate(40.0, 25.0))
        // Walking, crawling, or no speed: 15 m is still a few seconds away.
        assertFalse(TurnTiming.tooLate(10.0, 1.4))
        assertFalse(TurnTiming.tooLate(10.0, null))
    }

    @Test
    fun nowPromptMovesEarlierAtSpeed() {
        // 30 m/s: due 120 m out, instead of Ferrostar's 25 m + 5 m.
        assertNull(TurnTiming.speakNowEarly(exitStep, 130.0, 30.0))
        assertEquals(2, TurnTiming.speakNowEarly(exitStep, 118.0, 30.0))
        assertEquals(2, TurnTiming.speakNowEarly(exitStep, 60.0, 30.0))
        // Ferrostar is about to say it anyway.
        assertNull(TurnTiming.speakNowEarly(exitStep, 29.0, 30.0))
        // In town, 4 s is less than the trigger: Ferrostar's timing stands.
        assertNull(TurnTiming.speakNowEarly(exitStep, 35.0, 8.0))
        assertNull(TurnTiming.speakNowEarly(exitStep, 100.0, null))
    }

    @Test
    fun nowPromptIsNotRushedRightAfterAnotherOne() {
        val short = listOf(
            Prompt("Continue for 500 feet.", 160.0),
            Prompt("In 300 feet, Turn right onto Main Street.", 100.0),
            Prompt("Turn right onto Main Street.", 20.0),
        )
        // "In 300 feet" was heard at 100 m, under 4 s ago at 25 m/s: wait for Ferrostar's own.
        assertNull(TurnTiming.speakNowEarly(short, 95.0, 25.0))
        // A "Continue for" heard just now is no reason to wait.
        val ramp = listOf(Prompt("Continue for 400 feet.", 120.0), Prompt("Turn right.", 20.0))
        assertEquals(1, TurnTiming.speakNowEarly(ramp, 100.0, 30.0))
    }

    @Test
    fun nothingEarlyWhenItWouldAlreadyBeTooLate() {
        val tight = listOf(Prompt("Turn right.", 2.0))
        assertNull(TurnTiming.speakNowEarly(tight, 12.0, 30.0))
        assertEquals(0, TurnTiming.speakNowEarly(tight, 20.0, 30.0))
    }
}
