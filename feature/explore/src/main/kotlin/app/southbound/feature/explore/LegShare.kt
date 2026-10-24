package app.southbound.feature.explore

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * How the trip log leaves the phone, and only when the user asks: as text through the system share sheet, or as a CSV
 * file written to the document the system file picker returned. No file of Southbound's own, no cache, no
 * `FileProvider`, no permission.
 */
internal object LegShare {

    /**
     * The share sheet with [text] as plain text (`EXTRA_TEXT` only: no stream, no URI). Its Copy carries the mileage
     * line to Lumen's Share Summary. Answers false when the sheet could not open (no app to take it, or a text too big
     * for an intent), so the caller says so instead of crashing; nothing was sent then.
     */
    fun shareText(context: Context, text: String, title: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return try {
            context.startActivity(Intent.createChooser(send, title))
            true
        } catch (e: RuntimeException) {
            // ActivityNotFoundException, or TransactionTooLargeException wrapped in a RuntimeException.
            false
        }
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

    /**
     * The document `CreateDocument` made, deleted when nothing was written to it (Settings > Backup's `discardIfEmpty`):
     * true when it is gone. A document that isn't empty, or whose size can't be read, is left alone.
     */
    fun discardIfEmpty(resolver: ContentResolver, uri: Uri): Boolean {
        val size = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
        return size == 0L && runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    private fun removed(resolver: ContentResolver, uri: Uri): Written =
        if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) Written.FAILED_REMOVED else Written.FAILED_LEFT
}
