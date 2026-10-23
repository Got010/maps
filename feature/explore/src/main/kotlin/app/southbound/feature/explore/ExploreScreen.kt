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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.southbound.core.packs.ImportedPack
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.RoadSelection
import app.southbound.core.packs.WifiSelection
import app.southbound.core.packs.PackStore
import app.southbound.feature.settings.RegionPicker
import kotlinx.coroutines.launch
import java.io.File

/**
 * The first screen: the map, or the setup screen while no map is installed.
 *
 * [sharedPack] is a file another app shared to Southbound (Share > Southbound).
 * It is copied and recognised as soon as it arrives, but installed only when the
 * person says yes to a question naming the pack and the app it came from; then
 * [onSharedPackHandled] clears it.
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
    // The installed basemaps, one per region; the map draws all of them.
    var basemaps by remember { mutableStateOf(packStore.installedBasemaps()) }
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
            // A Wi-Fi pack brought in by hand is wanted: the updater would otherwise drop it as unasked for.
            if (result.pack.kind == PackKind.WIFI) WifiSelection.get(context).choose(true)
            // The same for a Road pack: the updater would drop it as unasked for, since the switch is off.
            if (result.pack.kind == PackKind.ROAD) RoadSelection.get(context).choose(true)
        }
        basemaps = packStore.installedBasemaps()
    }

    // Packs the updater installed while the app is open: switch to them.
    LaunchedEffect(packStore) {
        PackEvents.installed.collect { pack ->
            packGeneration++
            if (pack.kind == PackKind.BASEMAP) basemapGeneration++
            basemaps = packStore.installedBasemaps()
        }
    }
    // A region unticked in Settings: its map goes away.
    LaunchedEffect(packStore) {
        PackEvents.removed.collect { basemaps = packStore.installedBasemaps() }
    }

    // Pick up a pack pushed with adb while the app was in the background.
    LifecycleResumeEffect(packStore) {
        basemaps = packStore.installedBasemaps()
        onPauseOrDispose { }
    }

    // A shared pack waiting for the person's answer, copied but not installed.
    var sharedOffer by remember { mutableStateOf<SharedPackOffer?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sharedPack) {
        val uri = sharedPack ?: return@LaunchedEffect
        // A newer share replaces one still waiting for an answer, which is then not installed.
        sharedOffer?.let { discardSharedPack(packStore, it) }
        sharedOffer = null
        importState = ImportState.Importing
        stageSharedPack(context, packStore, uri).fold(
            onSuccess = {
                importState = ImportState.Idle
                sharedOffer = it
            },
            onFailure = {
                onImportFinished(importFailure(it))
                onSharedPackHandled()
            },
        )
    }
    // Gone with a question open (the activity recreated): the copy goes, nothing was installed. The share is still
    // the activity's, so the question comes back.
    DisposableEffect(packStore) {
        onDispose { sharedOffer?.let { packStore.discardStaged(it.staged) } }
    }

    Box(Modifier.fillMaxSize()) {
        if (basemaps.isNotEmpty()) {
            // A new set of regions is a new style; the key rebuilds the map for it.
            key(basemapGeneration, basemaps.map { it.file.path }) {
                ExploreMap(basemaps, services, packGeneration, onOpenSettings)
            }
        } else {
            MissingPack(
                importDir = packStore.importPacksDir ?: packStore.packsDir,
                importing = importState is ImportState.Importing,
                onPicked = { importState = ImportState.Importing },
                onImported = ::onImportFinished,
                packStore = packStore,
                onRetry = { basemaps = packStore.installedBasemaps() },
                onOpenSettings = onOpenSettings,
            )
        }
        sharedOffer?.let { offer ->
            SharedPackDialog(
                offer = offer,
                // Answered: the share is handled at once, so a screen torn down mid-install never asks again.
                onInstall = {
                    sharedOffer = null
                    onSharedPackHandled()
                    importState = ImportState.Importing
                    scope.launch { onImportFinished(installSharedPack(packStore, offer)) }
                },
                onDecline = {
                    sharedOffer = null
                    onSharedPackHandled()
                    scope.launch {
                        discardSharedPack(packStore, offer)
                        importState = ImportState.Declined
                    }
                },
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
    importDir: File,
    importing: Boolean,
    onPicked: (Uri) -> Unit,
    onImported: (ImportState) -> Unit,
    packStore: PackStore,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.explore_pack_missing_title), style = MaterialTheme.typography.headlineSmall)
            RegionPicker(packStore, showNote = false)
            DownloadPacksCard(onOpenSettings)
            Text(stringResource(R.string.explore_pack_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing) {
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
                File(importDir, PackStore.basemapFileName("<region>")).absolutePath,
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
