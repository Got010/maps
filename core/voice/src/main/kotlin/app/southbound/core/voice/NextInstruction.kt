package app.southbound.core.voice

/**
 * Which route prompt Ferrostar will trigger next, so its audio can be made
 * ahead of time. Ferrostar speaks a step's prompt once the distance to the
 * step's maneuver drops to the prompt's trigger distance, so the next one is
 * the furthest-out prompt still ahead; when the step has none left, it's the
 * following step's first. Pure, and tested.
 */
object NextInstruction {

    /** A step's prompts by trigger distance (metres before its maneuver). */
    data class Pick(val stepOffset: Int, val index: Int)

    /**
     * [current]: trigger distances of the current step's prompts.
     * [following]: the next step's. [distanceToManeuver]: metres left on the
     * current step. Null when nothing more will be spoken.
     */
    fun pick(current: List<Double>, distanceToManeuver: Double, following: List<Double>?): Pick? {
        current.withIndex()
            .filter { it.value < distanceToManeuver }
            .maxByOrNull { it.value }
            ?.let { return Pick(0, it.index) }
        val next = following?.withIndex()?.maxByOrNull { it.value } ?: return null
        return Pick(1, next.index)
    }
}
