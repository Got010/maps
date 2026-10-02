package app.southbound.feature.explore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.packs.ImportedPack
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStore
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
}

/** Copies the pack at [uri] into private storage. Runs on the IO dispatcher. */
suspend fun importPackFrom(context: Context, packStore: PackStore, uri: Uri): ImportState =
    withContext(Dispatchers.IO) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
            packStore.importPack(input, context.displayName(uri))
        }.fold(
            onSuccess = { ImportState.Done(it) },
            onFailure = { ImportState.Failed(notAPack = it is UnrecognizedPackException, detail = it.message) },
        )
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
                ImportState.Idle -> Unit
            }
        }
    }
}
