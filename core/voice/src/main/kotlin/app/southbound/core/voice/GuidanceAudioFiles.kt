package app.southbound.core.voice

import java.io.File

/**
 * The system engine's spoken prompts, rendered to `guidance-<id>.wav` in the app's cache. Each is read into memory
 * and deleted at once ([SystemTtsEngine.synthesize]); one a process death left behind (killed mid-sentence) still
 * holds a street name as audio, so [sweep] removes every such file when the app starts.
 */
object GuidanceAudioFiles {
    private val NAME = Regex("guidance-[0-9a-fA-F-]{36}\\.wav")

    /** Where the prompt [id] (a random UUID) is rendered. */
    fun fileFor(dir: File, id: String): File = File(dir, "guidance-$id.wav")

    /** Whether [file] is one of these prompts: only those are ever swept. */
    fun isPrompt(file: File): Boolean = NAME.matches(file.name)

    /** Deletes every prompt left in [dir]; returns how many are still there (0 unless a delete failed). */
    fun sweep(dir: File): Int {
        val left = dir.listFiles()?.filter { it.isFile && isPrompt(it) }.orEmpty()
        return left.count { !it.delete() && it.exists() }
    }
}
