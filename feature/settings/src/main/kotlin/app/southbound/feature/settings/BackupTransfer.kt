package app.southbound.feature.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.southbound.core.export.BackupCounts
import app.southbound.core.export.BackupRefusedException
import app.southbound.core.export.BackupService
import app.southbound.core.export.ImportOutcome
import app.southbound.core.export.ReadBackFailedException
import app.southbound.core.export.SouthboundBundle
import app.southbound.core.export.fwx.FwxError
import app.southbound.core.export.fwx.FwxException
import app.southbound.core.export.fwx.FwxHeader
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.SecretUnreadableException
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.text.Normalizer

/** What an export came to, for the dialog. */
internal sealed interface ExportResult {
    data class Done(val counts: BackupCounts, val size: Long?) : ExportResult
    data class Failed(val reason: String, val cleanup: Cleanup) : ExportResult
}

/** What became of the document the picker made, so the message says only what is true. */
internal enum class Cleanup {
    /** It was deleted (a partial or empty file). */
    DELETED,

    /** Nothing was written to it and it already held something (an earlier export): left as it was. */
    UNTOUCHED,

    /** It should have been deleted and could not be. */
    NOT_DELETED,
}

/** What an import came to, for the dialog. */
internal sealed interface ImportResult {
    data class Applied(val counts: BackupCounts, val turnedOn: List<NetworkFeature>, val wifiTurnedOn: Boolean) : ImportResult
    data class Pending(
        val counts: BackupCounts,
        val turnedOn: List<NetworkFeature>,
        val reason: String,
        val wifiTurnedOn: Boolean,
    ) : ImportResult

    /** Stopped before anything changed; [message] is the string to show. */
    data class Refused(val message: String) : ImportResult
}

/**
 * The Android half of export and import: documents, sizes and messages. The work itself is [BackupService]; this
 * only opens the streams the system file picker gave and turns each failure into the text the person reads.
 */
internal class BackupTransfer(private val context: Context, private val service: BackupService) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Writes the export to [uri], reads it back, and deletes the document if anything went wrong. Blocking. */
    suspend fun export(uri: Uri, passphrase: CharArray): ExportResult {
        var opened = false
        return try {
            val counts = service.export(
                openOutput = {
                    opened = true
                    resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException()
                },
                openInput = { resolver.openInputStream(uri) ?: throw FileNotFoundException() },
                passphrase = passphrase,
            )
            ExportResult.Done(counts, sizeOf(uri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Once the document has been opened for writing it holds a partial file. Before that it is the empty
            // document the picker made, or, if the picker handed back an earlier export, that export: leave a
            // non-empty one alone.
            val cleanup = when {
                !opened && sizeOf(uri) != 0L -> Cleanup.UNTOUCHED
                delete(uri) -> Cleanup.DELETED
                else -> Cleanup.NOT_DELETED
            }
            ExportResult.Failed(exportReason(e), cleanup)
        }
    }

    /** Removes the empty document the picker made when the person cancelled before exporting. */
    fun discardIfEmpty(uri: Uri) {
        if (sizeOf(uri) == 0L) delete(uri)
    }

    /** Steps 1 to 6 of the import (header, app, schema), or the message for why the file can't be used. */
    fun peek(uri: Uri): Result<FwxHeader> = try {
        Result.success(service.readHeader { resolver.openInputStream(uri) ?: throw FileNotFoundException() })
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun import(uri: Uri, passphrase: CharArray): ImportResult = try {
        when (val outcome = service.import({ resolver.openInputStream(uri) ?: throw FileNotFoundException() }, passphrase)) {
            is ImportOutcome.Done -> ImportResult.Applied(outcome.counts, outcome.turnedOn, outcome.wifiTurnedOn)
            is ImportOutcome.Pending -> ImportResult.Pending(outcome.counts, outcome.turnedOn, outcome.reason, outcome.wifiTurnedOn)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ImportResult.Refused(importFailure(e))
    }

    private fun exportReason(e: Exception): String = when {
        e is BackupRefusedException -> e.reason
        e is FwxException && e.code == FwxError.BAD_PASSPHRASE -> e.detail
        e is FwxException && e.code == FwxError.TOO_LARGE -> context.getString(R.string.backup_reason_too_large)
        // Notes name the rule that was broken, never what the data held.
        e is FwxException -> context.getString(R.string.backup_reason_rule, e.detail)
        e is SecretUnreadableException -> context.getString(R.string.backup_reason_key_unreadable)
        e is ReadBackFailedException -> context.getString(R.string.backup_reason_read_back)
        e is IOException -> context.getString(R.string.backup_reason_write)
        else -> context.getString(R.string.backup_reason_other)
    }

    /**
     * The message for each problem with a file (FWX spec section 8). For every one of these nothing was
     * changed: the file is checked whole before anything is applied.
     */
    fun importFailure(e: Throwable): String {
        val fwx = e as? FwxException
        return when (fwx?.code) {
            FwxError.WRONG_APP -> context.getString(R.string.backup_wrong_app, SouthboundBundle.appName(fwx.otherAppId.orEmpty()))
            FwxError.BAD_PASSPHRASE -> context.getString(R.string.backup_bad_passphrase, fwx.detail)
            FwxError.NOT_AN_EXPORT -> context.getString(R.string.backup_not_an_export)
            FwxError.LEGACY -> context.getString(R.string.backup_legacy)
            FwxError.UNSUPPORTED_VERSION -> context.getString(R.string.backup_unsupported_version)
            FwxError.MALFORMED_HEADER -> context.getString(R.string.backup_malformed_header)
            FwxError.UNSUPPORTED_KDF -> context.getString(R.string.backup_unsupported_kdf)
            FwxError.KDF_PARAMS -> context.getString(R.string.backup_kdf_params)
            FwxError.SCHEMA_TOO_NEW -> context.getString(R.string.backup_schema_too_new)
            FwxError.SCHEMA_TOO_OLD -> context.getString(R.string.backup_schema_too_old)
            FwxError.WRONG_PASSPHRASE -> context.getString(R.string.backup_wrong_passphrase)
            FwxError.DAMAGED -> context.getString(R.string.backup_damaged)
            FwxError.MALFORMED_PAYLOAD -> context.getString(R.string.backup_malformed_payload)
            FwxError.TOO_LARGE -> context.getString(R.string.backup_too_large)
            null -> when (e) {
                is BackupRefusedException -> context.getString(R.string.backup_refused, e.reason)
                // Opening or reading the file failed before anything was decrypted or applied.
                else -> context.getString(R.string.backup_unreadable, reasonOf(e))
            }
        }
    }

    private fun reasonOf(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun delete(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    private fun sizeOf(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()
}

/** The number of Unicode code points once NFC-normalised, as the codec counts them for its 12-point floor. */
internal fun codePointsAfterNfc(text: CharSequence): Int {
    val normal = if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) text else Normalizer.normalize(text, Normalizer.Form.NFC)
    return Character.codePointCount(normal, 0, normal.length)
}
