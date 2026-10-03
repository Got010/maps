package app.southbound.core.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * The Piper voices bundled in the APK, run by sherpa-onnx. They guarantee
 * directions still speak when the phone has no TTS engine, which is the
 * default on GrapheneOS.
 *
 * espeak-ng needs real files, so the pronunciation data and the chosen voice
 * are copied out of the APK's assets into private storage on first use (and
 * again only when the bundled files change). A voice nobody picks is never
 * copied, so the second voice costs disk space only once chosen.
 */
internal class PiperEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var loaded: BuiltInVoice? = null

    @Synchronized
    fun synthesize(text: String, rate: Float, voice: BuiltInVoice = BuiltInVoice.AMY): Pcm {
        val engine = tts?.takeIf { loaded == voice } ?: run {
            release()
            load(voice).also { tts = it; loaded = voice }
        }
        val audio = engine.generate(text, 0, rate)
        return Pcm.fromFloats(audio.samples, audio.sampleRate)
    }

    private fun load(voice: BuiltInVoice): OfflineTts {
        File(context.filesDir, LEGACY_DIR).takeIf { it.exists() }?.deleteRecursively()
        val data = install("$ROOT/$ESPEAK_DIR", "$ROOT/$ESPEAK_STAMP")
        // A build without the voice (it shouldn't happen) falls back to the default one.
        val chosen = if (hasAsset("$ROOT/${voice.assetDir}/$STAMP")) voice else BuiltInVoice.AMY
        val dir = install("$ROOT/${chosen.assetDir}", "$ROOT/${chosen.assetDir}/$STAMP")
        val model = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
            ?: error("Bundled voice ${chosen.name} has no model")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = data.path,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        return OfflineTts(config = config)
    }

    private fun hasAsset(path: String): Boolean = runCatching { context.assets.open(path).close() }.isSuccess

    /** Copies the asset folder [path] to the same path in private storage unless [stampAsset] says it's current. */
    private fun install(path: String, stampAsset: String): File {
        val target = File(context.filesDir, path)
        val bundled = context.assets.open(stampAsset).bufferedReader().use { it.readText() }
        val stamp = File(context.filesDir, "$path.installed")
        if (target.isDirectory && stamp.isFile && stamp.readText() == bundled) return target
        target.deleteRecursively()
        copyAssets(path, target)
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
        loaded = null
    }

    private companion object {
        /** Laid out by scripts/fetch-voice.sh in the APK's assets. */
        const val ROOT = "piper"
        const val ESPEAK_DIR = "espeak-ng-data"
        const val ESPEAK_STAMP = "espeak.sha256"
        const val STAMP = "voice.sha256"

        /** Where Phase 1 to 4 builds unpacked their one voice. */
        const val LEGACY_DIR = "voice"
    }
}
