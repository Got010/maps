package app.southbound.core.voice

/**
 * The order guidance speaks in, so a sentence is never cut off for no good
 * reason. Pure and unit tested; [GuidanceVoice] plays what [next] hands out.
 *
 * - A reroute or a closure alert cuts in at once: whatever was playing or
 *   queued is about a route that no longer applies.
 * - Anything else waits for the current sentence to finish.
 * - A newer turn prompt replaces an older one still waiting.
 * - Turn prompts whose turn is behind the driver, or that waited too long,
 *   are dropped instead of being spoken late.
 */
class SpeechQueue(private val maxWaitMillis: Long = MAX_WAIT_MS) {

    /**
     * One sentence to say. [utteranceId] ties a route prompt to its turn, for
     * staleness. The player calls [onStart] just before the sound starts and
     * [onDone] once it is finished with the sentence (played, cut off, or
     * found stale just before playing). A sentence the queue drops (cleared,
     * replaced or stale) gets neither.
     */
    data class Item(
        val action: GuidanceAction.Speak,
        val kind: UtteranceKind,
        val queuedAt: Long,
        val utteranceId: String? = null,
        val onStart: (() -> Unit)? = null,
        val onDone: (() -> Unit)? = null,
    )

    private val waiting = ArrayDeque<Item>()

    /** What is playing now, between [next] and [done]. */
    var playing: Item? = null
        private set

    /** Queues [item]. True when the one playing should be stopped for it. */
    @Synchronized
    fun offer(item: Item): Boolean {
        if (item.kind in CUTS_IN) {
            waiting.removeAll { it.kind == UtteranceKind.INSTRUCTION || it.kind == UtteranceKind.REROUTE }
            waiting.addFirst(item)
            return playing?.let { it.kind != UtteranceKind.TEST } ?: false
        }
        if (item.kind == UtteranceKind.INSTRUCTION) waiting.removeAll { it.kind == UtteranceKind.INSTRUCTION }
        waiting.addLast(item)
        return false
    }

    /**
     * The next sentence to play, or null. [isCurrent] says whether a route
     * prompt's turn is still ahead (by its utterance id).
     */
    @Synchronized
    fun next(now: Long, isCurrent: (String) -> Boolean): Item? {
        while (true) {
            val item = waiting.removeFirstOrNull() ?: return null
            if (item.kind == UtteranceKind.INSTRUCTION) {
                if (now - item.queuedAt > maxWaitMillis) continue
                if (item.utteranceId != null && !isCurrent(item.utteranceId)) continue
            }
            playing = item
            return item
        }
    }

    /** The playing sentence finished (or was stopped). */
    @Synchronized
    fun done() {
        playing = null
    }

    @Synchronized
    fun clear() {
        waiting.clear()
    }

    @get:Synchronized
    val isEmpty: Boolean get() = waiting.isEmpty()

    companion object {
        /** A turn prompt older than this is about a moment that has passed. */
        const val MAX_WAIT_MS = 8_000L

        private val CUTS_IN = setOf(UtteranceKind.REROUTE, UtteranceKind.TRAFFIC_ALERT)
    }
}
