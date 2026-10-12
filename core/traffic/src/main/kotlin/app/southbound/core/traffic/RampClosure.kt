package app.southbound.core.traffic

/**
 * An event that shuts a ramp, not the road it leaves or joins: "Exit ramp closed", "I-80 eastbound: Entrance ramp
 * closed, because of roadwork.", "Ramp closed from NY-17 Westbound to Exit 120.". The feeds put its point at the
 * gore, on the mainline, so taken as a closure it said "Road closed ahead" and put an exclusion square across an
 * open highway. The readers make it road work or other instead, still on the map with its words.
 */
object RampClosure {
    // A ramp first (after any "road direction:" the CARS tooltips start with), then closed, in the same clause.
    private val RAMP = Regex(
        """^(?:[^:.]{0,80}:\s*)?(?:(?:entrance|exit|on|off)[- ]?)?ramps?\b[^.,;]*\bclos(?:ed|ure)""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [text] (a description, a headline or a sub-type) says a ramp is closed. */
    fun matches(text: String?): Boolean = text != null && RAMP.containsMatchIn(text.trim())
}
