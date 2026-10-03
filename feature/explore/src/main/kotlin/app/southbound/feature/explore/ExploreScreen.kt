package app.southbound.feature.explore

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStatus
import app.southbound.core.packs.PackStore
import kotlinx.coroutines.launch

private const val REGION = "nyc"

/**
 * The first screen: the map, or the setup screen while no map is installed.
 *
 * [sharedPack] is a file another app shared to Southbound (Share > Southbound).
 * It is imported as soon as it arrives, then [onSharedPackHandled] clears it.
 */
@Composable
fun ExploreScreen(
    sharedPack: Uri? = null,
    onSharedPackHandled: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val services = remember { ExploreServices.get(context) }
    val packStore = services.packStore
    var packStatus by remember { mutableStateOf(packStore.basemapStatus(REGION)) }
    var importState by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    // Bumped when a new basemap replaces the old one at the same path, so the map reloads.
    var basemapGeneration by remember { mutableIntStateOf(0) }
    // Bumped after any import, so search and routing pick up a new pack.
    var packGeneration by remember { mutableIntStateOf(0) }
    fun onImportFinished(result: ImportState) {
        importState = result
        if (result is ImportState.Done) {
            packGeneration++
            if (result.pack.kind == PackKind.BASEMAP) basemapGeneration++
        }
        packStatus = packStore.basemapStatus(REGION)
    }

    // Pick up a pack pushed with adb while the app was in the background.
    LifecycleResumeEffect(packStore) {
        packStatus = packStore.basemapStatus(REGION)
        onPauseOrDispose { }
    }

    LaunchedEffect(sharedPack) {
        val uri = sharedPack ?: return@LaunchedEffect
        importState = ImportState.Importing
        onImportFinished(importPackFrom(context, packStore, uri))
        onSharedPackHandled()
    }

    Box(Modifier.fillMaxSize()) {
        when (val status = packStatus) {
            is PackStatus.Installed -> key(basemapGeneration) {
                ExploreMap(status.file, services, packGeneration, onOpenSettings)
            }
            else -> MissingPack(
                status = status,
                importing = importState is ImportState.Importing,
                onPicked = { importState = ImportState.Importing },
                onImported = ::onImportFinished,
                packStore = packStore,
                onRetry = { packStatus = packStore.basemapStatus(REGION) },
            )
        }
        ImportBanner(
            state = importState,
            onDismiss = { importState = ImportState.Idle },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
        )
    }
}

@Composable
private fun MissingPack(
    status: PackStatus,
    importing: Boolean,
    onPicked: (Uri) -> Unit,
    onImported: (ImportState) -> Unit,
    packStore: PackStore,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The system file picker: no storage permission, and works on release builds.
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onPicked(uri)
        scope.launch { onImported(importPackFrom(context, packStore, uri)) }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(
                stringResource(
                    if (status is PackStatus.Invalid) {
                        R.string.explore_pack_invalid_title
                    } else {
                        R.string.explore_pack_missing_title
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.explore_pack_body), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
                Text(stringResource(R.string.explore_import))
            }
            Text(
                stringResource(R.string.explore_pack_share_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.explore_pack_adb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                status.file.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry, enabled = !importing) {
                Text(stringResource(R.string.explore_pack_retry))
            }
        }
    }
}
