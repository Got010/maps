package app.southbound.core.voice

/**
 * Rewrites Valhalla's spoken sentences: a landmark into a turn ("Turn right
 * after the next light onto Main Street."), a quick second turn onto the end
 * ("..., then turn left onto Elm Street."), and the distance off a prompt
 * given at the turn. Pure and unit tested; [GuidancePolicy] decides when.
 *
 * Valhalla's sentences (English, as Ferrostar passes them on) look like
 * "Turn right onto Main Street.", "In 500 feet, Turn right onto Main
 * Street.", "Bear left toward I 87.", "Keep right to take exit 3.",
 * "Continue for 2 miles.", "Enter the roundabout and take the 2nd exit.".
 */
object InstructionText {

    private const val DISTANCE = """in\s+[^,]{1,40},\s*"""

    private val LEADING_DISTANCE = Regex("""^\s*$DISTANCE""", RegexOption.IGNORE_CASE)

    /**
     * The turn verbs a landmark can follow. Roundabouts, exits, ramps,
     * merges, departures and arrivals have their own wording and are left
     * alone; so is "Continue for 2 miles", which isn't a maneuver.
     */
    private val MANEUVER = Regex(
        """^(\s*(?:$DISTANCE)?)(""" +
            """turn\s+(?:sharp\s+|slight\s+)?(?:left|right)""" +
            """|bear\s+(?:left|right)""" +
            """|keep\s+(?:left|right)""" +
            // U-turns first, so "Make a left U-turn" isn't split after "left".
            """|make\s+a\s+(?:left\s+|right\s+)?u-turn""" +
            """|make\s+a\s+(?:sharp\s+)?(?:left|right)""" +
            """|continue\s+straight""" +
            """|continue(?=\s+onto\b)""" +
            """)(?=[\s.,;]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val THEN = Regex("""\bthen\b""", RegexOption.IGNORE_CASE)

    /** "In 500 feet, Turn left onto Elm Street." -> "Turn left onto Elm Street." */
    fun withoutLeadingDistance(text: String): String {
        val stripped = LEADING_DISTANCE.replaceFirst(text, "")
        return if (stripped == text) text else stripped.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * "Turn right onto Main Street." + "after the next light" ->
     * "Turn right after the next light onto Main Street." Unchanged when the
     * sentence isn't a plain turn, or already says where ("Keep right at the fork").
     */
    fun withLandmark(text: String, phrase: String): String {
        if (phrase.isBlank()) return text
        val match = MANEUVER.find(text) ?: return text
        val rest = text.substring(match.range.last + 1)
        if (Regex("""^\s+(at|past|after)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rest)) return text
        return text.substring(0, match.range.last + 1) + " " + phrase.trim() + rest
    }

    /**
     * "Turn right onto Main Street." + "Turn left onto Elm Street." ->
     * "Turn right onto Main Street, then turn left onto Elm Street." Unchanged
     * when Valhalla already chained them ("... Then turn left ...") or the
     * second is the arrival.
     */
    fun withFollowUp(text: String, next: String): String {
        if (THEN.containsMatchIn(text)) return text
        val second = firstSentence(withoutLeadingDistance(next.trim()))
        if (second.isEmpty() || GuidancePolicy.kindOf(second) == UtteranceKind.ARRIVAL) return text
        val first = text.trim().trimEnd('.', ' ', ',')
        return "$first, then ${second.replaceFirstChar { it.lowercaseChar() }}."
    }

    private val LANE_WORD = Regex("""\blanes?\b""", RegexOption.IGNORE_CASE)

    /** Valhalla's own two-maneuver prompt: "Turn left onto Elm Street. Then turn right onto Oak Street." */
    private val THEN_SENTENCE = Regex("""\.\s+(?=then\b)""", RegexOption.IGNORE_CASE)

    /**
     * "In 500 feet, Turn left onto Elm Street." + "use the left two lanes" ->
     * "In 500 feet, Turn left onto Elm Street, use the left two lanes." The
     * advice is for the first maneuver, so it goes before Valhalla's own
     * ". Then ..." sentence. Unchanged when the sentence already talks about lanes.
     */
    fun withLanes(text: String, phrase: String): String {
        val advice = phrase.trim().trimEnd('.', ' ', ',').replaceFirstChar { it.lowercaseChar() }
        if (advice.isEmpty() || LANE_WORD.containsMatchIn(text)) return text
        val then = THEN_SENTENCE.find(text)
        if (then != null) {
            val head = text.substring(0, then.range.first).trimEnd('.', ' ', ',')
            return "$head, $advice" + text.substring(then.range.first)
        }
        return "${text.trim().trimEnd('.', ' ', ',')}, $advice."
    }

    private val ARRIVED = Regex("""\barrived\b""", RegexOption.IGNORE_CASE)

    /**
     * True for the route's own "You have arrived at your destination."; the
     * app says its own arrival ("You have arrived at Joe's Pizza.") instead.
     * "You will arrive ..." and "Your destination is on the right." are not.
     */
    fun isArrived(text: String): Boolean = ARRIVED.containsMatchIn(text)

    private fun firstSentence(text: String): String =
        text.split(Regex("""(?<=\.)\s+""")).first().trim().trimEnd('.', ' ', ',')
}
