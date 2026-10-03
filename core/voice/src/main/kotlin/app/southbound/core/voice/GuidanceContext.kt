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

    /** False once the turn the prompt [utteranceId] announces is behind the driver. */
    fun isCurrent(utteranceId: String): Boolean
}
