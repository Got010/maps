package app.southbound.core.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's own text-to-speech engine, e.g. GrapheneOS Speech Services.
 *
 * Speech is rendered to a file rather than played by the engine, so
 * [GuidancePlayer] controls output device, volume and ducking the same way
 * for both engines.
 */
internal class SystemTtsEngine(context: Context) {

    private val cacheDir = context.cacheDir
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.complete(false)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                pending.remove(utteranceId)?.complete(false)
            }
        })
    }

    /** True once an engine is bound and speaks US English. */
    suspend fun isAvailable(): Boolean {
        val bound = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!bound || tts.engines.isNullOrEmpty()) return false
        val support = tts.isLanguageAvailable(Locale.US)
        return support >= TextToSpeech.LANG_AVAILABLE
    }

    suspend fun synthesize(text: String, rate: Float): Pcm? {
        if (!isAvailable()) return null
        tts.language = Locale.US
        tts.setSpeechRate(rate)
        val id = UUID.randomUUID().toString()
        val file = File(cacheDir, "guidance-$id.wav")
        val done = CompletableDeferred<Boolean>().also { pending[id] = it }
        try {
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) return null
            val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() } ?: false
            return if (ok && file.length() > 0) WavReader.read(file) else null
        } finally {
            pending.remove(id)
            file.delete()
        }
    }

    fun shutdown() = tts.shutdown()

    private companion object {
        const val INIT_TIMEOUT_MS = 3_000L
        const val SYNTH_TIMEOUT_MS = 10_000L
    }
}
