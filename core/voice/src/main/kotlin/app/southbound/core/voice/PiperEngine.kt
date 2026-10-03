package app.southbound.core.voice

import android.content.Context
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStatus
import app.southbound.core.packs.PackStore
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.zip.ZipFile

/**
 * The Piper voices of the built-in engine, run by sherpa-onnx. They
 * guarantee directions still speak when the phone has no TTS engine, which
 * is the default on GrapheneOS.
 *
 * Amy is bundled in the APK; other voices arrive as `voice-<id>.zip` packs
 * through the updater, only once picked in Settings. espeak-ng needs real
 * files, so the pronunciation data (bundled, shared by every voice) and the
 * voice in use are copied out into private storage on first use, and again
 * only when they change. Until a picked voice's pack is installed, Amy speaks.
 */
internal class PiperEngine(private val context: Context) {

    private val packs = PackStore(context.filesDir, context.getExternalFilesDir(null))
    private var tts: OfflineTts? = null
    private var loaded: BuiltInVoice? = null

    @Synchronized
    fun synthesize(text: String, rate: Float, voice: BuiltInVoice = BuiltInVoice.AMY): Pcm {
        val wanted = if (isInstalled(voice)) voice else BuiltInVoice.AMY
        val engine = tts?.takeIf { loaded == wanted } ?: run {
            release()
            load(wanted).also { tts = it; loaded = wanted }
        }
        val audio = engine.generate(text, 0, rate)
        return Pcm.fromFloats(audio.samples, audio.sampleRate)
    }

    /** True when [voice] can speak: bundled, or its pack is installed. */
    fun isInstalled(voice: BuiltInVoice): Boolean = voice.bundled || pack(voice) != null

    /** Deletes a downloaded voice: its pack and the unpacked copy. Bundled voices stay. */
    @Synchronized
    fun delete(voice: BuiltInVoice) {
        if (voice.bundled) return
        if (loaded == voice) release()
        packs.delete(PackKind.VOICE, voice.id)
        File(context.filesDir, "$ROOT/${voice.id}").deleteRecursively()
        File(context.filesDir, "$ROOT/${voice.id}$INSTALLED").delete()
    }

    private fun pack(voice: BuiltInVoice): File? =
        (packs.status(PackKind.VOICE, voice.id) as? PackStatus.Installed)?.file

    private fun load(voice: BuiltInVoice): OfflineTts {
        File(context.filesDir, LEGACY_DIR).takeIf { it.exists() }?.deleteRecursively()
        val data = installAssets("$ROOT/$ESPEAK_DIR", "$ROOT/$ESPEAK_STAMP")
        val dir = if (voice.bundled) {
            installAssets("$ROOT/${voice.id}", "$ROOT/${voice.id}/$STAMP")
        } else {
            unpack(pack(voice) ?: error("Voice ${voice.id} isn't downloaded"), "$ROOT/${voice.id}")
        }
        val model = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
            ?: error("Voice ${voice.id} has no model")
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

    /** Copies the asset folder [path] to the same path in private storage unless [stampAsset] says it's current. */
    private fun installAssets(path: String, stampAsset: String): File {
        val bundled = context.assets.open(stampAsset).bufferedReader().use { it.readText() }
        return installOnce(path, bundled) { target -> copyAssets(path, target) }
    }

    /**
     * Unzips a voice pack to [path] in private storage, once per pack
     * version (its voice.sha256 entry). Only plain file names are taken, so
     * a crafted pack can't write outside the folder.
     */
    private fun unpack(zip: File, path: String): File {
        val version = ZipFile(zip).use { z ->
            z.getEntry(STAMP)?.let { e -> z.getInputStream(e).bufferedReader().use { it.readText() } }
        } ?: "${zip.length()}-${zip.lastModified()}"
        return installOnce(path, version) { target ->
            target.mkdirs()
            ZipFile(zip).use { z ->
                for (entry in z.entries()) {
                    val name = entry.name
                    if (entry.isDirectory || name.isEmpty() || '/' in name || '\\' in name || name.startsWith(".")) continue
                    z.getInputStream(entry).use { input -> File(target, name).outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }

    private fun installOnce(path: String, version: String, copy: (File) -> Unit): File {
        val target = File(context.filesDir, path)
        val stamp = File(context.filesDir, "$path$INSTALLED")
        if (target.isDirectory && stamp.isFile && stamp.readText() == version) return target
        stamp.delete()
        target.deleteRecursively()
        copy(target)
        stamp.writeText(version)
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
        /** Laid out by scripts/fetch-voice.sh in the APK's assets, and mirrored in private storage. */
        const val ROOT = "piper"
        const val ESPEAK_DIR = "espeak-ng-data"
        const val ESPEAK_STAMP = "espeak.sha256"
        const val STAMP = "voice.sha256"
        const val INSTALLED = ".installed"

        /** Where Phase 1 to 4 builds unpacked their one voice. */
        const val LEGACY_DIR = "voice"
    }
}
