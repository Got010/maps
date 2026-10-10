package app.southbound.core.updates

import android.content.Context
import app.southbound.core.packs.ReleaseManifest
import java.io.File

/**
 * The last southbound-v2.json an update round read, kept on the phone so the route preview can say what a trip's
 * regions would download without asking GitHub. Only the release's file list: nothing about the phone.
 */
object ManifestCache {
    private const val FILE_NAME = "last-" + ReleaseManifest.FILE_NAME_V2

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    internal fun save(context: Context, text: String) {
        val target = file(context)
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        runCatching {
            temp.writeText(text)
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    /** The cached manifest, or null before the first v2 round (or if it can't be read). Reads a file: off the main thread. */
    fun load(context: Context): ReleaseManifest? =
        runCatching { ReleaseManifest.parseV2(file(context).readText()) }.getOrNull()
}
