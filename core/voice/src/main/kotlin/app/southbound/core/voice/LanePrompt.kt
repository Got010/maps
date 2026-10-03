package app.southbound.core.voice

/**
 * Which of a step's prompts carries the lane advice ("..., use the left two
 * lanes"): the closest-in prompt before the "now" one, so it is said once,
 * early enough to change lanes, and the prompt at the turn stays short.
 * Never a "Continue for 2 miles" prompt, which isn't about the turn. Null
 * when the step has no such prompt (lanes are then only drawn). Pure, tested.
 */
object LanePrompt {

    fun indexFor(prompts: List<TurnTiming.Prompt>): Int? {
        val now = TurnTiming.nowIndex(prompts) ?: return null
        val nowTrigger = prompts[now].triggerMeters
        return prompts.withIndex()
            .filter { it.index != now && it.value.triggerMeters > nowTrigger && !TurnTiming.isProgress(it.value.text) }
            .minByOrNull { it.value.triggerMeters }
            ?.index
    }
}
