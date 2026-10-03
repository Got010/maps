package app.southbound.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionTextTest {

    private fun landmark(text: String, phrase: String = "after the next light") = InstructionText.withLandmark(text, phrase)

    @Test
    fun turnsGetTheLandmarkAfterTheVerb() {
        assertEquals("Turn right after the next light onto Main Street.", landmark("Turn right onto Main Street."))
        assertEquals("Turn left at the stop sign.", landmark("Turn left.", "at the stop sign"))
        assertEquals("Turn sharp left past the Shell gas station onto Elm Street.", landmark("Turn sharp left onto Elm Street.", "past the Shell gas station"))
        assertEquals("Bear right at the Walgreens toward I 87.", landmark("Bear right toward I 87.", "at the Walgreens"))
        assertEquals("Keep left after the next light to stay on Broadway.", landmark("Keep left to stay on Broadway."))
        assertEquals("Make a left U-turn at the next light onto 5th Avenue.", landmark("Make a left U-turn onto 5th Avenue.", "at the next light"))
        assertEquals("Continue straight at the next light onto Main Street.", landmark("Continue straight onto Main Street.", "at the next light"))
        assertEquals("Continue at the next light onto Main Street.", landmark("Continue onto Main Street.", "at the next light"))
    }

    @Test
    fun distancePrefixIsKept() {
        assertEquals(
            "In 500 feet, Turn right after the next light onto Main Street.",
            landmark("In 500 feet, Turn right onto Main Street."),
        )
        assertEquals("In a quarter mile, turn left at the stop sign.", landmark("In a quarter mile, turn left.", "at the stop sign"))
    }

    @Test
    fun otherSentencesAreLeftAlone() {
        for (text in listOf(
            "Continue for 2 miles.",
            "Continue on Broadway for 2 miles.",
            "Enter the roundabout and take the 2nd exit onto Main Street.",
            "Exit the roundabout onto Main Street.",
            "Take exit 3 on the right.",
            "Take the ramp on the right toward I 278.",
            "Merge left onto I 95 North.",
            "Head north on Broadway.",
            "You have arrived at your destination.",
            "Your destination is on the right.",
            "Keep right at the fork.",
            "Turning right is fun.",
        )) {
            assertEquals(text, landmark(text))
        }
    }

    @Test
    fun onlyTheFirstSentenceChanges() {
        assertEquals(
            "Turn right after the next light onto Main Street. Then Turn left onto Elm Street.",
            landmark("Turn right onto Main Street. Then Turn left onto Elm Street."),
        )
    }

    @Test
    fun followUpIsChained() {
        assertEquals(
            "Turn right onto Main Street, then turn left onto Elm Street.",
            InstructionText.withFollowUp("Turn right onto Main Street.", "Turn left onto Elm Street."),
        )
        // The follow-up's own distance and later sentences go.
        assertEquals(
            "Turn right, then bear left toward I 87.",
            InstructionText.withFollowUp("Turn right", "In 200 feet, Bear left toward I 87. Then keep right."),
        )
    }

    @Test
    fun followUpSkippedWhenValhallaAlreadyChainedOrArriving() {
        val chained = "Turn right onto Main Street. Then Turn left onto Elm Street."
        assertEquals(chained, InstructionText.withFollowUp(chained, "Turn left onto Elm Street."))
        assertEquals(
            "Turn right onto Main Street.",
            InstructionText.withFollowUp("Turn right onto Main Street.", "You have arrived at your destination."),
        )
    }

    @Test
    fun leadingDistance() {
        assertEquals("Turn left onto Elm Street.", InstructionText.withoutLeadingDistance("In 200 feet, turn left onto Elm Street."))
        assertEquals("Turn left.", InstructionText.withoutLeadingDistance("Turn left."))
        assertEquals("Continue for 2 miles.", InstructionText.withoutLeadingDistance("Continue for 2 miles."))
    }
}
