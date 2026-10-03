package app.southbound.core.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * The Piper voice bundled in the APK, run by sherpa-onnx. It guarantees
 * directions still speak when the phone has no TTS engine, which is the
 * default on GrapheneOS.
 *
 * espeak-ng needs real files, so the voice is copied out of the APK's assets
 * into private storage once (and again only when the bundled voice changes).
 */
internal class PiperEngine(private val context: Context) {

    private var tts: OfflineTts? = null

    @Synchronized
    fun synthesize(text: String, rate: Float): Pcm {
        val engine = tts ?: load().also { tts = it }
        val audio = engine.generate(text, 0, rate)
        return Pcm.fromFloats(audio.samples, audio.sampleRate)
    }

    private fun load(): OfflineTts {
        val dir = installVoice()
        val model = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
            ?: error("Bundled voice has no model")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        return OfflineTts(config = config)
    }

    private fun installVoice(): File {
        val target = File(context.filesDir, ASSET_DIR)
        val bundled = context.assets.open("$ASSET_DIR/$STAMP").bufferedReader().use { it.readText() }
        val stamp = File(target, STAMP)
        if (stamp.isFile && stamp.readText() == bundled) return target
        target.deleteRecursively()
        copyAssets(ASSET_DIR, target)
        stamp.writeText(bundled)
        return target
    }

    private fun copyAssets(path: String, to: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (child in children) copyAssets("$path/$child", File(to, child))
    }

    @Synchronized
    fun release() {
        tts?.release()
        tts = null
    }

    private companion object {
        /** Bundled by scripts/fetch-voice.sh into the APK's assets. */
        const val ASSET_DIR = "voice"
        const val STAMP = "voice.sha256"
    }
}
