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
import uniffi.ferrostar.SpokenInstruction

/**
 * Speaks navigation guidance with every voice toggle applied.
 *
 * Ferrostar calls [onSpokenInstructionTrigger] at the right moment for each
 * route instruction; the app calls [announce] for reroutes, incidents,
 * cameras and transit stops. With a [guidanceContext] set, turn prompts gain
 * landmarks and chain a quick second turn, and stale ones are dropped;
 * [prepare] makes the next prompt's audio ahead of time. A sentence is never
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
                    runCatching { play(item.action) }
                } finally {
                    speech.done()
                }
            }
        }
    }

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        intake.launch {
            handle(
                GuidancePolicy.kindOf(spokenInstruction.text),
                spokenInstruction.text,
                spokenInstruction.triggerDistanceBeforeManeuver,
                spokenInstruction,
            )
        }
    }

    /** Speaks an app event, e.g. "Rerouting", subject to its toggle. */
    fun announce(kind: UtteranceKind, text: String) {
        intake.launch { handle(kind, text, 0.0, null) }
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
            ) as? GuidanceAction.Speak ?: return@launch
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
        stopAndClearQueue()
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

    private fun decide(kind: UtteranceKind, text: String, triggerDistance: Double, instruction: SpokenInstruction?): GuidanceAction {
        val context = guidanceContext.takeIf { kind == UtteranceKind.INSTRUCTION }
        val landmark = instruction?.let { runCatching { context?.landmarkFor(it) }.getOrNull() }
        val followUp = instruction?.let { runCatching { context?.followUpFor(it) }.getOrNull() }
        return GuidancePolicy.decide(settings.value, guidanceMode, kind, text, triggerDistance, landmark, followUp)
    }

    private fun handle(kind: UtteranceKind, text: String, triggerDistance: Double, instruction: SpokenInstruction?) {
        if (kind != UtteranceKind.TEST && (_muteState.value || _mutedUntilNextTurn.value)) return
        when (val action = decide(kind, text, triggerDistance, instruction)) {
            is GuidanceAction.Speak -> {
                if (action.vibrate) vibrate()
                val item = SpeechQueue.Item(action, kind, SystemClock.elapsedRealtime(), instruction?.utteranceId?.toString())
                if (speech.offer(item)) player.stop()
                wake.trySend(Unit)
            }
            GuidanceAction.Vibrate -> vibrate()
            GuidanceAction.Silent -> Unit
        }
    }

    private suspend fun play(action: GuidanceAction.Speak) {
        val current = settings.value
        val ready = synchronized(prepared) { prepared.remove(cacheKey(action.text, current)) }
        val pcm = ready?.await() ?: synthesize(action.text, current) ?: return
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

    override fun stopAndClearQueue() {
        speech.clear()
        player.stop()
    }

    override fun setMuted(isMuted: Boolean) {
        _muteState.value = isMuted
        if (isMuted) stopAndClearQueue()
    }

    override val muteState: StateFlow<Boolean> = _muteState.asStateFlow()

    /** Which engine speaks with the current settings, for the Settings screen. */
    suspend fun activeEngine(): VoiceEngine = when (settings.value.engine) {
        VoiceEngine.BUILT_IN -> VoiceEngine.BUILT_IN
        else -> if (system.isAvailable()) VoiceEngine.SYSTEM else VoiceEngine.BUILT_IN
    }

    suspend fun systemEngineAvailable(): Boolean = system.isAvailable()

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
    }
}
