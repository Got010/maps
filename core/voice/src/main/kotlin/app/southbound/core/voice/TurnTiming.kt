package app.southbound.core.voice

import kotlin.math.max

/**
 * When turn prompts are said, on top of Ferrostar's trigger distances. Pure
 * and unit tested; the app feeds it the route's steps and the GPS speed.
 *
 * How Ferrostar 0.57 speaks: a step's prompts announce the maneuver at the
 * END of that step, each with a trigger distance before it. On every GPS fix
 * it picks the closest-in prompt whose trigger distance (plus a 5 m fudge) has
 * been reached, and speaks it once. Fixes come about once a second, so at
 * highway speed a "now" prompt with a 20-30 m trigger fires about a second
 * before the turn, and is heard during it. This object:
 *
 * - chains a quick second maneuver onto the first ("Take exit 14S, then turn
 *   left") when the two are close in distance or time ([chains]);
 * - says the "now" prompt earlier at speed ([speakNowEarly]);
 * - drops a prompt whose maneuver is already under the car ([tooLate]).
 */
object TurnTiming {

    /** One spoken prompt of a step: its words and how far before the maneuver it triggers. */
    data class Prompt(val text: String, val triggerMeters: Double)

    /** Driving: a maneuver this close after the previous one is said with it... */
    const val CHAIN_MIN_GAP_M = 400.0

    /** ...on foot or by bike, this close (400 m is five minutes' walk). */
    const val CHAIN_MIN_GAP_SLOW_M = 150.0

    /** ...or this many seconds after it at the current speed, whichever is further. */
    const val CHAIN_GAP_SECONDS = 20.0

    /** The "now" prompt is said at least this many seconds before the maneuver. */
    const val NOW_LEAD_SECONDS = 4.0

    /** Ferrostar speaks a prompt this many metres before its trigger distance. */
    const val FERROSTAR_FUDGE_M = 5.0

    /** Closer than this to the maneuver, a prompt is heard during it, not before it... */
    const val TOO_LATE_M = 15.0

    /** ...when moving at least this fast (m/s, about 9 mph). Slower, 15 m is still a few seconds. */
    const val TOO_LATE_MIN_SPEED_MPS = 4.0

    /** How far after a maneuver the next one still counts as "right after" it. */
    fun chainGapMeters(speedMps: Double?, mode: GuidanceMode = GuidanceMode.DRIVING): Double {
        val floor = if (mode == GuidanceMode.DRIVING) CHAIN_MIN_GAP_M else CHAIN_MIN_GAP_SLOW_M
        return max(floor, speedOrZero(speedMps) * CHAIN_GAP_SECONDS)
    }

    /**
     * True when the maneuver [gapMeters] after the one being announced should
     * be said with it. [gapMeters] is the length of the step between the two.
     */
    fun chains(gapMeters: Double, speedMps: Double?, mode: GuidanceMode = GuidanceMode.DRIVING): Boolean =
        gapMeters >= 0 && gapMeters <= chainGapMeters(speedMps, mode)

    private val PROGRESS = Regex(
        """^\s*(?:continue|keep\s+going|drive|walk|bike|cycle|head)\b[^.]*?\bfor\s+(?:about\s+)?(?:an?\b|one\b|half\b|\d)""",
        RegexOption.IGNORE_CASE,
    )

    private val DEPART = Regex(
        """^\s*(?:drive|walk|bike|cycle|head)\s+(?:north|south|east|west)(?:east|west)?\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True for a prompt about the road the user is on rather than the
     * maneuver ahead: "Continue for 2 miles.", "Continue on I 87 for 1 mile.",
     * "Drive north on Main Street." Nothing is chained onto these, and they
     * are never used as the follow-up.
     */
    fun isProgress(text: String): Boolean = PROGRESS.containsMatchIn(text) || DEPART.containsMatchIn(text)

    /**
     * The step's "now" prompt: the closest-in one that announces the
     * maneuver. Null when the step has none (only "Continue for ..." or nothing).
     */
    fun nowIndex(prompts: List<Prompt>): Int? =
        prompts.withIndex()
            .filter { !isProgress(it.value.text) }
            .minByOrNull { it.value.triggerMeters }
            ?.index

    /**
     * The prompts of a step whose maneuver was already said as a follow-up,
     * which are not said again: all but its "now" prompt.
     */
    fun skippedAfterChain(prompts: List<Prompt>): Set<Int> {
        val now = nowIndex(prompts)
        return prompts.indices.filter { it != now }.toSet()
    }

    /** True when a prompt for a maneuver [distanceToManeuver] metres ahead would be heard during it. */
    fun tooLate(distanceToManeuver: Double, speedMps: Double?): Boolean =
        distanceToManeuver < TOO_LATE_M && speedOrZero(speedMps) >= TOO_LATE_MIN_SPEED_MPS

    /**
     * The current step's "now" prompt to speak now, ahead of Ferrostar, or
     * null. At speed, it is due [NOW_LEAD_SECONDS] before the maneuver, i.e.
     * at max(trigger distance, speed x 4 s). Not when Ferrostar is about to
     * speak it anyway, when it would already be [tooLate], or when another
     * prompt about this maneuver was spoken in the last few seconds (the
     * driver just heard it; Ferrostar's own "now" prompt still follows).
     */
    fun speakNowEarly(prompts: List<Prompt>, distanceToManeuver: Double, speedMps: Double?): Int? {
        val now = nowIndex(prompts) ?: return null
        val nowTrigger = prompts[now].triggerMeters
        if (distanceToManeuver <= nowTrigger + FERROSTAR_FUDGE_M) return null
        val lead = speedOrZero(speedMps) * NOW_LEAD_SECONDS
        if (distanceToManeuver > lead) return null
        if (tooLate(distanceToManeuver, speedMps)) return null
        // The prompt Ferrostar spoke last for this maneuver: the closest-in one already reached.
        val lastHeard = prompts.withIndex()
            .filter { it.index != now && distanceToManeuver - it.value.triggerMeters <= FERROSTAR_FUDGE_M }
            .minByOrNull { it.value.triggerMeters }
        if (lastHeard != null && !isProgress(lastHeard.value.text) &&
            lastHeard.value.triggerMeters <= distanceToManeuver + lead
        ) {
            return null
        }
        return now
    }

    private fun speedOrZero(speedMps: Double?): Double =
        speedMps?.takeIf { it.isFinite() && it > 0 } ?: 0.0
}
