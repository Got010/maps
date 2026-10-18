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

    @Test
    fun theRoutesOwnArrivalIsRecognised() {
        assertEquals(true, InstructionText.isArrived("You have arrived at your destination."))
        assertEquals(true, InstructionText.isArrived("You have arrived at 350 5th Avenue."))
        assertEquals(false, InstructionText.isArrived("You will arrive at your destination."))
        assertEquals(false, InstructionText.isArrived("Your destination is on the right."))
        assertEquals(false, InstructionText.isArrived("Turn left onto Arrive Street."))
    }

    @Test
    fun exitThenTurnAtTheLight() {
        assertEquals(
            "Take exit 5A on the right, then turn left onto Main Street.",
            InstructionText.withFollowUp("Take exit 5A on the right.", "Turn left onto Main Street."),
        )
        assertEquals(
            "In a quarter mile, Take exit 5A on the right, then turn left onto Main Street.",
            InstructionText.withFollowUp("In a quarter mile, Take exit 5A on the right.", "In 300 feet, Turn left onto Main Street."),
        )
    }

    @Test
    fun theExitNumberIsSaidOnEveryPromptForIt() {
        assertEquals("In 2 miles, Take exit 5 on the right.", InstructionText.withExitNumber("In 2 miles, Take the exit on the right.", "5"))
        assertEquals(
            "In 2 miles, Take exit 5A on the right toward Lakeview.",
            InstructionText.withExitNumber("In 2 miles, Take the exit on the right toward Lakeview.", "5A"),
        )
        assertEquals(
            "In 1 mile, Take exit 24, the I 90 East exit, toward Boston.",
            InstructionText.withExitNumber("In 1 mile, Take the I 90 East exit toward Boston.", "24"),
        )
        assertEquals(
            "Take exit 24, the I 90 East exit.",
            InstructionText.withExitNumber("Take the I 90 East exit.", "24"),
        )
        // Already numbered, not about an exit, or a roundabout's: unchanged.
        assertEquals("Take exit 5 on the right.", InstructionText.withExitNumber("Take exit 5 on the right.", "5"))
        assertEquals("Continue on I 90 for 2 miles.", InstructionText.withExitNumber("Continue on I 90 for 2 miles.", "5"))
        assertEquals(
            "Enter the roundabout and take the 2nd exit onto Main Street.",
            InstructionText.withExitNumber("Enter the roundabout and take the 2nd exit onto Main Street.", "3"),
        )
        assertEquals("Take the exit on the right.", InstructionText.withExitNumber("Take the exit on the right.", " "))
    }
}
