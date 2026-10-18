package app.southbound.feature.explore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.southbound.core.packs.ImportedPack
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStore
import app.southbound.core.packs.Regions
import app.southbound.core.packs.ShownName
import app.southbound.core.packs.StagedPack
import app.southbound.core.packs.UnrecognizedPackException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Progress of importing a pack the user picked or shared to the app. */
sealed interface ImportState {
    data object Idle : ImportState
    data object Importing : ImportState
    data class Done(val pack: ImportedPack) : ImportState
    data class Failed(val notAPack: Boolean, val detail: String?) : ImportState

    /** A shared pack the person chose not to install: nothing changed. */
    data object Declined : ImportState
}

/** Copies the pack at [uri] into private storage. Runs on the IO dispatcher. */
suspend fun importPackFrom(context: Context, packStore: PackStore, uri: Uri): ImportState =
    withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
            packStore.importPack(input, context.displayName(uri))
        }.fold(
            onSuccess = { ImportState.Done(it) },
            onFailure = { importFailure(it) },
        )
    }

/**
 * A pack another app shared in, copied into private storage and recognised but not installed: what the
 * confirmation names. [fileName] is the name the sending side gives the file and [source] the label of the app that
 * holds it (the content provider's app), else its address; both are chosen by other apps, so both are cleaned to one
 * short line ([ShownName]) and shown as their claims, never as proof.
 */
class SharedPackOffer(val staged: StagedPack, val fileName: String?, val source: String?)

/**
 * Copies and recognises the pack at [uri] without installing it, so the person can be asked first. Runs on the IO
 * dispatcher. Returns the offer, or the failure to show.
 */
suspend fun stageSharedPack(context: Context, packStore: PackStore, uri: Uri): Result<SharedPackOffer> =
    withContext(Dispatchers.IO) {
        runCatching {
            val name = context.displayName(uri)
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
            val staged = input.use { packStore.stagePack(it, name) }
            SharedPackOffer(staged, ShownName.clean(name), ShownName.clean(context.sourceOf(uri)))
        }
    }

/** The failure [stageSharedPack] or [installSharedPack] met, as the banner shows it. */
fun importFailure(error: Throwable): ImportState.Failed =
    ImportState.Failed(notAPack = error is UnrecognizedPackException, detail = error.message)

/** Installs a confirmed [offer]. Runs on the IO dispatcher. */
suspend fun installSharedPack(packStore: PackStore, offer: SharedPackOffer): ImportState =
    withContext(Dispatchers.IO) {
        runCatching { packStore.installStaged(offer.staged) }.fold(
            onSuccess = { ImportState.Done(it) },
            onFailure = { importFailure(it) },
        )
    }

/** Drops a declined [offer]'s copy. Runs on the IO dispatcher. */
suspend fun discardSharedPack(packStore: PackStore, offer: SharedPackOffer) {
    withContext(Dispatchers.IO) { packStore.discardStaged(offer.staged) }
}

/**
 * The app that holds the shared file: the label of the app whose content provider serves [uri] (Android lets an
 * app see the app that granted it a file), else the provider's address. That app picks its own label, so any app
 * can call itself "Downloads": the dialog says so.
 */
@Suppress("DEPRECATION") // resolveContentProvider(String, ComponentInfoFlags) needs API 33; minSdk is 31.
private fun Context.sourceOf(uri: Uri): String? {
    val authority = uri.authority ?: return null
    val label = runCatching {
        packageManager.resolveContentProvider(authority, 0)?.applicationInfo?.loadLabel(packageManager)?.toString()
    }.getOrNull()
    return label?.takeIf { it.isNotBlank() } ?: authority
}

private fun Context.displayName(uri: Uri): String? =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

@StringRes
fun PackKind.installedMessage(): Int = when (this) {
    PackKind.BASEMAP -> R.string.explore_imported_basemap
    PackKind.ROUTING -> R.string.explore_imported_routing
    PackKind.SEARCH -> R.string.explore_imported_search
    PackKind.TRANSIT -> R.string.explore_imported_transit
    PackKind.VOICE -> R.string.explore_imported_voice
    PackKind.WIFI -> R.string.explore_imported_wifi
}

/** A small card reporting an import, shown over the map or the setup screen. */
@Composable
fun ImportBanner(state: ImportState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (state is ImportState.Idle) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                ImportState.Importing -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.explore_importing), Modifier.padding(vertical = 12.dp))
                }
                is ImportState.Done -> {
                    Text(stringResource(state.pack.kind.installedMessage()), Modifier.weight(1f, fill = false))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.explore_import_ok)) }
                }
                is ImportState.Failed -> {
                    Text(
                        if (state.notAPack) {
                            stringResource(R.string.explore_import_not_a_pack)
                        } else {
                            stringResource(R.string.explore_import_failed) + " " + state.detail.orEmpty()
                        },
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.explore_import_ok)) }
                }
                ImportState.Declined -> {
                    Text(stringResource(R.string.explore_import_declined), Modifier.weight(1f, fill = false))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.explore_import_ok)) }
                }
                ImportState.Idle -> Unit
            }
        }
    }
}

/**
 * Asks before a pack shared in from another app replaces anything. The warning comes first, in Southbound's own
 * words, so nothing the sending app chose can push it out of view; then what the pack is and how big, then the file
 * name and the app as those apps give them (cleaned to one short line each), and the body scrolls. Dismissing it is
 * a no.
 */
@Composable
fun SharedPackDialog(offer: SharedPackOffer, onInstall: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    val staged = offer.staged
    val unknown = stringResource(R.string.explore_shared_unknown)
    val what = stringResource(staged.kind.sharedLabel())
    val region = ShownName.clean(if (staged.kind == PackKind.VOICE) staged.region else Regions.nameOf(staged.region)) ?: unknown
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.explore_shared_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.explore_shared_warning), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.explore_shared_what, what, region, Formatter.formatShortFileSize(context, staged.sizeBytes)))
                Text(
                    stringResource(R.string.explore_shared_claims, offer.fileName ?: unknown, offer.source ?: unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text(stringResource(R.string.explore_shared_install)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.explore_shared_decline)) } },
    )
}

@StringRes
private fun PackKind.sharedLabel(): Int = when (this) {
    PackKind.BASEMAP -> R.string.explore_shared_kind_basemap
    PackKind.ROUTING -> R.string.explore_shared_kind_routing
    PackKind.SEARCH -> R.string.explore_shared_kind_search
    PackKind.TRANSIT -> R.string.explore_shared_kind_transit
    PackKind.VOICE -> R.string.explore_shared_kind_voice
    PackKind.WIFI -> R.string.explore_shared_kind_wifi
}
