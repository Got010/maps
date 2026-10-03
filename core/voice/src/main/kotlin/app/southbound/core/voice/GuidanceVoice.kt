package app.southbound.core.voice

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.CoroutineScope
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
 * route instruction; the app calls [announce] for reroutes, incidents and
 * transit stops. [GuidancePolicy] decides whether each one is spoken, buzzed
 * or skipped. Speech comes from the system TTS engine if one is active
 * (or chosen), else from the bundled Piper voice, so it never goes silent.
 * All of it runs on the phone.
 */
class GuidanceVoice(context: Context, private val settings: StateFlow<VoiceSettings>) : SpokenInstructionObserver {

    private val appContext = context.applicationContext

    /** The current trip's mode, for the per-mode toggles. */
    @Volatile
    var guidanceMode: GuidanceMode = GuidanceMode.DRIVING

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val system by lazy { SystemTtsEngine(appContext) }
    private val builtIn by lazy { PiperEngine(appContext) }
    private val player = GuidancePlayer(appContext)
    private val _muteState = MutableStateFlow(false)

    init {
        scope.launch {
            for (text in queue) runCatching { speakNow(text) }
        }
    }

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        handle(
            GuidancePolicy.kindOf(spokenInstruction.text),
            spokenInstruction.text,
            spokenInstruction.triggerDistanceBeforeManeuver,
        )
    }

    /** Speaks an app event, e.g. "Rerouting", subject to its toggle. */
    fun announce(kind: UtteranceKind, text: String) = handle(kind, text, 0.0)

    /** The Test voice button. */
    fun test() = announce(UtteranceKind.TEST, TEST_SENTENCE)

    private fun handle(kind: UtteranceKind, text: String, triggerDistance: Double) {
        if (_muteState.value && kind != UtteranceKind.TEST) return
        when (val action = GuidancePolicy.decide(settings.value, guidanceMode, kind, text, triggerDistance)) {
            is GuidanceAction.Speak -> queue.trySend(action.text)
            GuidanceAction.Vibrate -> vibrate()
            GuidanceAction.Silent -> Unit
        }
    }

    private suspend fun speakNow(text: String) {
        val current = settings.value
        val rate = current.speechRate.coerceIn(VoiceSettings.MIN_SPEECH_RATE, VoiceSettings.MAX_SPEECH_RATE)
        val pcm = when (current.engine) {
            VoiceEngine.BUILT_IN -> builtIn.synthesize(text, rate)
            // System, or the default "System if one is active". If the system
            // engine fails, fall back so directions still speak.
            VoiceEngine.SYSTEM, null -> system.synthesize(text, rate) ?: builtIn.synthesize(text, rate)
        }
        player.play(pcm, current)
    }

    private fun vibrate() {
        val vibrator = appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1))
    }

    override fun stopAndClearQueue() {
        while (queue.tryReceive().isSuccess) Unit
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
        scope.cancel()
        queue.close()
        system.shutdown()
        builtIn.release()
    }

    companion object {
        const val TEST_SENTENCE = "This is Southbound. In a quarter mile, turn left onto Broadway."
    }
}
