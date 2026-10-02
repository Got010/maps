package app.southbound.feature.explore

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.southbound.core.map.SouthboundMap
import app.southbound.core.map.gpsUpdates
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStatus
import app.southbound.core.packs.PackStore
import kotlinx.coroutines.launch
import java.io.File

private const val REGION = "nyc"

/**
 * The first screen: the map, or the setup screen while no map is installed.
 *
 * [sharedPack] is a file another app shared to Southbound (Share > Southbound).
 * It is imported as soon as it arrives, then [onSharedPackHandled] clears it.
 */
@Composable
fun ExploreScreen(sharedPack: Uri? = null, onSharedPackHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val packStore = remember { PackStore(context.filesDir, context.getExternalFilesDir(null)) }
    var packStatus by remember { mutableStateOf(packStore.basemapStatus(REGION)) }
    var importState by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    // Bumped when a new basemap replaces the old one at the same path, so the map reloads.
    var basemapGeneration by remember { mutableIntStateOf(0) }
    fun onImportFinished(result: ImportState) {
        importState = result
        if (result is ImportState.Done && result.pack.kind == PackKind.BASEMAP) basemapGeneration++
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
            is PackStatus.Installed -> key(basemapGeneration) { MapWithLocation(status.file) }
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
private fun MapWithLocation(basemap: File) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(context.hasFineLocation()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        hasPermission = context.hasFineLocation()
    }
    fun requestPermission() {
        asked = true
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
    LaunchedEffect(Unit) {
        if (!hasPermission && !asked) requestPermission()
    }
    LifecycleResumeEffect(Unit) {
        hasPermission = context.hasFineLocation()
        onPauseOrDispose { }
    }

    var location by remember { mutableStateOf<Location?>(null) }
    var following by rememberSaveable { mutableStateOf(true) }

    // GPS runs only while the screen is visible.
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val locationManager = context.getSystemService(LocationManager::class.java)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            locationManager.collectGps { location = it }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SouthboundMap(
            basemap = basemap,
            darkTheme = isSystemInDarkTheme(),
            location = location,
            following = following,
            onFollowingDismissed = { following = false },
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.systemBars.asPaddingValues(),
        )

        // Once Android stops showing the dialog, only app settings can grant it.
        val settingsOnly = asked && context.findActivity()
            ?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == false
        if (!hasPermission || location == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                if (!hasPermission) {
                    LocationPermissionPrompt(
                        settingsOnly = settingsOnly,
                        onAllow = { if (settingsOnly) context.openAppSettings() else requestPermission() },
                    )
                } else {
                    Text(stringResource(R.string.explore_finding_location), Modifier.padding(12.dp))
                }
            }
        }

        if (location != null) {
            FloatingActionButton(
                onClick = { following = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = if (following) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_my_location),
                    contentDescription = stringResource(R.string.explore_my_location),
                )
            }
        }
    }
}

@Composable
private fun LocationPermissionPrompt(settingsOnly: Boolean, onAllow: () -> Unit) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.explore_location_needed),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onAllow) {
            Text(
                stringResource(
                    if (settingsOnly) R.string.explore_location_settings else R.string.explore_location_allow,
                ),
            )
        }
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

// Callers check hasFineLocation() first.
@SuppressLint("MissingPermission")
private suspend fun LocationManager.collectGps(onFix: (Location) -> Unit) {
    gpsUpdates().collect { onFix(it) }
}

private fun Context.hasFineLocation(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
