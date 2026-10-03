package app.southbound.core.voice

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ferrostar.SpokenInstruction

/**
 * Speaks navigation guidance with every voice toggle applied.
 *
 * Ferrostar calls [onSpokenInstructionTrigger] at the right moment for each
 * route instruction; the app calls [announce] for reroutes, incidents,
 * cameras and transit stops. With a [guidanceContext] set, turn prompts gain
 * landmarks and chain a quick second turn, and stale ones are dropped;
 * [prepare] makes the next prompt's audio ahead of time. [speakEarly] says a
 * prompt before Ferrostar would (at speed), [startTrip] says the arrival time
 * before the first turn, and [announceAndWait] lets the app end a trip once
 * "You have arrived" has been said. A sentence is never
 * cut off except by a reroute or closure alert ([SpeechQueue]).
 * [GuidancePolicy] decides whether each one is spoken, buzzed or skipped. Speech comes from the system TTS engine if one is active
 * (or chosen), else from the bundled Piper voice, so it never goes silent.
 * All of it runs on the phone.
 */
class GuidanceVoice(context: Context, private val settings: StateFlow<VoiceSettings>) : SpokenInstructionObserver {

    private val appContext = context.applicationContext

    /** The current trip's mode, for the per-mode toggles. */
    @Volatile
    var guidanceMode: GuidanceMode = GuidanceMode.DRIVING

    /** The trip as the app sees it, for landmarks, merged turns and stale prompts. Null between trips. */
    @Volatile
    var guidanceContext: GuidanceContext? = null

    // Three lanes: deciding what to say (in order), making audio ahead of
    // time, and playing (which blocks while a sentence plays).
    @OptIn(ExperimentalCoroutinesApi::class)
    private val intake = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val synth = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val playback = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val speech = SpeechQueue()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val system by lazy { SystemTtsEngine(appContext) }
    private val builtIn by lazy { PiperEngine(appContext) }
    private val player = GuidancePlayer(appContext)
    private val _muteState = MutableStateFlow(false)
    private val _mutedUntilNextTurn = MutableStateFlow(false)

    /** Route prompts already handled, by utterance id; touched only on [intake]. */
    private val heard = LinkedHashSet<String>()

    /** What [startTrip] says before the trip's first prompt, until it is queued. */
    @Volatile
    private var tripStart: String? = null

    /** Audio made ahead of time by [prepare], by [cacheKey]; the last couple only. */
    private val prepared = object : LinkedHashMap<String, Deferred<Pcm?>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Deferred<Pcm?>>?): Boolean = size > PREPARED_MAX
    }

    init {
        playback.launch {
            while (true) {
                val item = speech.next(SystemClock.elapsedRealtime(), ::isCurrent)
                if (item == null) {
                    wake.receive()
                    continue
                }
                try {
                    runCatching { play(item) }
                } finally {
                    speech.done()
                    item.onDone?.let { runCatching(it) }
                }
            }
        }
    }

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        intake.launch { handleRoutePrompt(spokenInstruction) }
    }

    /**
     * Says the route prompt [instruction] now, before Ferrostar's trigger
     * distance is reached (the app does this for the "now" prompt at speed,
     * see [TurnTiming.speakNowEarly]). Ferrostar's own trigger of the same
     * prompt later is then ignored.
     */
    fun speakEarly(instruction: SpokenInstruction) = onSpokenInstructionTrigger(instruction)

    /** Speaks an app event, e.g. "Rerouting", subject to its toggle. */
    fun announce(kind: UtteranceKind, text: String) {
        intake.launch { handle(kind, text, 0.0, null) }
    }

    /**
     * Like [announce], but returns once the sentence has been played, or
     * right away when the settings (or mute) skip it. Gives up after
     * [timeoutMillis]; false then.
     */
    suspend fun announceAndWait(kind: UtteranceKind, text: String, timeoutMillis: Long): Boolean {
        val done = CompletableDeferred<Unit>()
        intake.launch { handle(kind, text, 0.0, null) { done.complete(Unit) } }
        return withTimeoutOrNull(timeoutMillis) { done.await() } != null
    }

    /**
     * Starts a trip: runs [start] (which starts navigation, and with it
     * Ferrostar's first prompt), and says [announcement] ("Starting. You'll
     * arrive at about 1:11 PM, 12 miles.") once, ahead of that first prompt,
     * through the usual settings ([UtteranceKind.TRIP_START]).
     */
    fun startTrip(announcement: String?, start: () -> Unit) {
        // Held aside, not queued: starting navigation clears the queue.
        tripStart = announcement
        try {
            start()
        } catch (e: Throwable) {
            tripStart = null
            throw e
        }
        // Normally the first prompt has queued it already; this covers a start without one.
        intake.launch { flushTripStart() }
    }

    private fun flushTripStart() {
        val text = tripStart ?: return
        tripStart = null
        handle(UtteranceKind.TRIP_START, text, 0.0, null)
    }

    /** The Test voice button. */
    fun test() = announce(UtteranceKind.TEST, TEST_SENTENCE)

    /**
     * Makes the audio for [instruction] now, so it plays the moment Ferrostar
     * triggers it. The app calls this for the next prompt when a trip starts
     * and as each prompt is spoken.
     */
    fun prepare(instruction: SpokenInstruction) {
        synth.launch {
            val action = decide(
                GuidancePolicy.kindOf(instruction.text),
                instruction.text,
                instruction.triggerDistanceBeforeManeuver,
                instruction,
            ).action as? GuidanceAction.Speak ?: return@launch
            val current = settings.value
            val key = cacheKey(action.text, current)
            val audio = synchronized(prepared) {
                prepared[key]?.let { return@launch }
                CompletableDeferred<Pcm?>().also { prepared[key] = it }
            }
            audio.complete(runCatching { synthesize(action.text, current) }.getOrNull())
        }
    }

    /** Silence until the driver passes the next turn (long press on the voice button). */
    fun muteUntilNextTurn() {
        _mutedUntilNextTurn.value = true
        clearQueue()
    }

    /** Undoes [muteUntilNextTurn] early. */
    fun resumeVoice() {
        _mutedUntilNextTurn.value = false
    }

    /** True while [muteUntilNextTurn] holds, for the navigation screen. */
    val mutedUntilNextTurn: StateFlow<Boolean> = _mutedUntilNextTurn.asStateFlow()

    /** The app calls this as the driver passes a maneuver and when a trip ends. */
    fun maneuverPassed() {
        _mutedUntilNextTurn.value = false
    }

    private fun isCurrent(utteranceId: String): Boolean = guidanceContext?.isCurrent(utteranceId) ?: true

    /** What to do with an utterance, and the next turn's sentence chained onto it, if any. */
    private class Decision(val action: GuidanceAction, val followUp: String?)

    private fun decide(kind: UtteranceKind, text: String, triggerDistance: Double, instruction: SpokenInstruction?): Decision {
        val context = guidanceContext.takeIf { kind == UtteranceKind.INSTRUCTION }
        val landmark = instruction?.let { runCatching { context?.landmarkFor(it) }.getOrNull() }
        val followUp = instruction?.let { runCatching { context?.followUpFor(it) }.getOrNull() }
        return Decision(GuidancePolicy.decide(settings.value, guidanceMode, kind, text, triggerDistance, landmark, followUp), followUp)
    }

    /** A prompt from the route, from Ferrostar or [speakEarly]. On [intake]. */
    private fun handleRoutePrompt(instruction: SpokenInstruction) {
        // Each prompt is said once, whoever triggers it first.
        if (!heard.add(instruction.utteranceId.toString())) return
        if (heard.size > HEARD_MAX) heard.remove(heard.first())
        val context = guidanceContext
        if (context != null && !runCatching { context.shouldSpeak(instruction) }.getOrDefault(true)) return
        handle(
            GuidancePolicy.kindOf(instruction.text),
            instruction.text,
            instruction.triggerDistanceBeforeManeuver,
            instruction,
        )
    }

    /** Runs on [intake], in order. [onDone] is called once it has played, or at once if it won't be. */
    private fun handle(
        kind: UtteranceKind,
        text: String,
        triggerDistance: Double,
        instruction: SpokenInstruction?,
        onDone: (() -> Unit)? = null,
    ) {
        // The trip's arrival time goes ahead of its first prompt.
        if (kind == UtteranceKind.INSTRUCTION || kind == UtteranceKind.ARRIVAL) flushTripStart()
        if (kind != UtteranceKind.TEST && (_muteState.value || _mutedUntilNextTurn.value)) {
            onDone?.invoke()
            return
        }
        val decision = decide(kind, text, triggerDistance, instruction)
        when (val action = decision.action) {
            is GuidanceAction.Speak -> {
                if (action.vibrate) vibrate()
                // Once a chained prompt is heard, the turn after it isn't announced again in full.
                val context = guidanceContext
                val chained = instruction.takeIf { decision.followUp != null }
                val onStart: (() -> Unit)? = if (chained != null && context != null) {
                    fun() { runCatching { context.followUpSpoken(chained) } }
                } else {
                    null
                }
                val item = SpeechQueue.Item(
                    action,
                    kind,
                    SystemClock.elapsedRealtime(),
                    instruction?.utteranceId?.toString(),
                    onStart,
                    onDone,
                )
                if (speech.offer(item)) player.stop()
                wake.trySend(Unit)
            }
            GuidanceAction.Vibrate -> {
                vibrate()
                onDone?.invoke()
            }
            GuidanceAction.Silent -> onDone?.invoke()
        }
    }

    private suspend fun play(item: SpeechQueue.Item) {
        val action = item.action
        val current = settings.value
        if (action.isChimeOnly) {
            action.chime?.let { player.play(Chime.only(it, CHIME_ONLY_SAMPLE_RATE), current) }
            return
        }
        val ready = synchronized(prepared) { prepared.remove(cacheKey(action.text, current)) }
        val pcm = ready?.await() ?: synthesize(action.text, current) ?: return
        // Making the audio takes a moment: a turn reached meanwhile isn't announced during it.
        val id = item.utteranceId
        if (item.kind == UtteranceKind.INSTRUCTION && id != null && !isCurrent(id)) return
        item.onStart?.invoke()
        player.play(action.chime?.let { Chime.before(it, pcm) } ?: pcm, current)
    }

    private suspend fun synthesize(text: String, current: VoiceSettings): Pcm? {
        val rate = current.speechRate.coerceIn(VoiceSettings.MIN_SPEECH_RATE, VoiceSettings.MAX_SPEECH_RATE)
        return when (current.engine) {
            VoiceEngine.BUILT_IN -> builtIn.synthesize(text, rate, current.builtInVoice)
            // System, or the default "System if one is active". If the system
            // engine fails, fall back so directions still speak.
            VoiceEngine.SYSTEM, null -> system.synthesize(text, rate) ?: builtIn.synthesize(text, rate, current.builtInVoice)
        }
    }

    /** Audio depends on the words, the rate, and which engine and voice speak them. */
    private fun cacheKey(text: String, current: VoiceSettings): String =
        "${current.engine?.name ?: "AUTO"}|${current.builtInVoice.name}|${current.speechRate}|$text"

    private fun vibrate() {
        val vibrator = appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1))
    }

    /** Ferrostar calls this as a trip starts, is rerouted or ends. */
    override fun stopAndClearQueue() {
        clearQueue()
        // A restarted route keeps its prompts' ids; let them be said again.
        intake.launch { heard.clear() }
    }

    private fun clearQueue() {
        speech.clear()
        player.stop()
    }

    override fun setMuted(isMuted: Boolean) {
        _muteState.value = isMuted
        if (isMuted) clearQueue()
    }

    override val muteState: StateFlow<Boolean> = _muteState.asStateFlow()

    /** Which engine speaks with the current settings, for the Settings screen. */
    suspend fun activeEngine(): VoiceEngine = when (settings.value.engine) {
        VoiceEngine.BUILT_IN -> VoiceEngine.BUILT_IN
        else -> if (system.isAvailable()) VoiceEngine.SYSTEM else VoiceEngine.BUILT_IN
    }

    suspend fun systemEngineAvailable(): Boolean = system.isAvailable()

    /** True when [voice] can speak now: bundled, or its pack is downloaded. Reads a file header; call off the main thread. */
    fun isVoiceInstalled(voice: BuiltInVoice): Boolean = builtIn.isInstalled(voice)

    /** Frees the space of a downloaded voice that's no longer picked. */
    fun deleteVoice(voice: BuiltInVoice) = builtIn.delete(voice)

    fun shutdown() {
        stopAndClearQueue()
        intake.cancel()
        synth.cancel()
        playback.cancel()
        wake.close()
        system.shutdown()
        builtIn.release()
    }

    companion object {
        const val TEST_SENTENCE = "This is Southbound. In a quarter mile, turn left onto Broadway."
        private const val PREPARED_MAX = 2

        /** Sample rate of a tone played without speech, e.g. the speed limit beep. */
        private const val CHIME_ONLY_SAMPLE_RATE = 24_000

        /** Route prompt ids remembered for [speakEarly]'s de-duplication; a long trip has a few hundred. */
        private const val HEARD_MAX = 1_000
    }
}
