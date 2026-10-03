package app.southbound.core.voice

import uniffi.ferrostar.SpokenInstruction

/**
 * What the voice needs to know about the trip, which only the app has (the
 * route, the landmarks along it). The app sets it on [GuidanceVoice] while
 * navigating. Called off the main thread; implementations must be quick.
 */
interface GuidanceContext {
    /** Words placing the turn [instruction] announces, e.g. "after the next light"; null for none. */
    fun landmarkFor(instruction: SpokenInstruction): String?

    /**
     * The sentence for the turn right after the one [instruction] announces,
     * when it comes so soon that both are best said together; null otherwise.
     */
    fun followUpFor(instruction: SpokenInstruction): String?

    /**
     * Lane advice to add to [instruction], e.g. "use the left two lanes":
     * for the turn it announces, on one early prompt only ([LanePrompt]);
     * null for none.
     */
    fun lanesFor(instruction: SpokenInstruction): String? = null

    /**
     * False once the turn the prompt [utteranceId] announces is behind the
     * driver, or so close that the prompt would be heard during it. Asked
     * again just before the prompt plays.
     */
    fun isCurrent(utteranceId: String): Boolean

    /**
     * False for a route prompt the app doesn't want said at all: one whose
     * turn was already said as a follow-up, or the route's own "You have
     * arrived" (the app says its own). Asked as Ferrostar triggers it.
     */
    fun shouldSpeak(instruction: SpokenInstruction): Boolean = true

    /** [instruction], chained with the turn after it ([followUpFor]), has started playing. */
    fun followUpSpoken(instruction: SpokenInstruction) {}
}
