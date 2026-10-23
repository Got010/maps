package app.southbound.feature.explore

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/**
 * How the trip log leaves the phone, and only when the user asks: as text through the system share sheet, or as a CSV
 * file written to the document the system file picker returned. No file of Southbound's own, no cache, no
 * `FileProvider`, no permission.
 */
internal object LegShare {

    /**
     * The share sheet with [text] as plain text (`EXTRA_TEXT` only: no stream, no URI). Its Copy carries the mileage
     * line to Lumen's Share Summary.
     */
    fun shareText(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title))
    }

    /** How writing the CSV file went. */
    enum class Written { DONE, FAILED_REMOVED, FAILED_LEFT }

    /**
     * Writes [csv] (UTF-8) to [uri], the document `CreateDocument` made, reads it back and compares. On any failure the
     * document is deleted (Settings > Backup's way with an export), so no half-written file is left behind. Blocking:
     * call it off the main thread.
     */
    fun writeCsv(resolver: ContentResolver, uri: Uri, csv: String): Written {
        val bytes = csv.toByteArray(Charsets.UTF_8)
        return try {
            (resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException()).use { it.write(bytes) }
            val back = (resolver.openInputStream(uri) ?: throw FileNotFoundException()).use { it.readBytes() }
            if (back.contentEquals(bytes)) Written.DONE else removed(resolver, uri)
        } catch (e: Exception) {
            removed(resolver, uri)
        }
    }

    private fun removed(resolver: ContentResolver, uri: Uri): Written =
        if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) Written.FAILED_REMOVED else Written.FAILED_LEFT
}
