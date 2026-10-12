package app.southbound.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.southbound.core.network.ApiKey
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackStore
import app.southbound.core.packs.Regions
import app.southbound.core.packs.TileFolder
import app.southbound.core.updates.UpdateScheduler
import app.southbound.core.updates.UpdateState
import app.southbound.core.updates.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/**
 * Updates from Southbound's GitHub releases: map data (on by default), the
 * app itself (off until turned on), when to use mobile data, an optional
 * GitHub token, and what the updater is doing now.
 */
@Composable
internal fun UpdatesSection(network: PreferenceNetworkToggles, secrets: SecretStore) {
    val context = LocalContext.current
    val toggles by network.state.collectAsState()
    val state by UpdateStatus.state.collectAsState()
    val queue by remember { UpdateScheduler.queue(context) }.collectAsState(UpdateScheduler.Queue.IDLE)
    var networkPermitted by remember { mutableStateOf(UpdateScheduler.networkPermitted(context)) }
    val packStore = remember { PackStore(context.filesDir, context.getExternalFilesDir(null)) }
    val tiles = remember { TileFolder(context.filesDir) }
    var packs by remember { mutableStateOf(installedPacks(packStore, tiles)) }
    var mobileData by remember { mutableStateOf(UpdateScheduler.allowMobileData(context)) }
    var canInstall by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    LifecycleResumeEffect(Unit) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        networkPermitted = UpdateScheduler.networkPermitted(context)
        packs = installedPacks(packStore, tiles)
        onPauseOrDispose { }
    }
    // A pack that lands or goes while Settings is open (an update round, an import, a region unticked) shows at once.
    LaunchedEffect(Unit) {
        merge(PackEvents.installed.map { }, PackEvents.removed.map { }).collect {
            packs = withContext(Dispatchers.IO) { installedPacks(packStore, tiles) }
        }
    }

    Section(R.string.settings_updates)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(versionLine(context), style = MaterialTheme.typography.bodyMedium)
        if (!networkPermitted) {
            Text(UpdateScheduler.NO_NETWORK_PERMISSION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        UpdateProgress(state)
        if (queue == UpdateScheduler.Queue.WAITING && state !is UpdateState.Downloading && state != UpdateState.Checking) {
            Text(stringResource(R.string.updates_waiting), style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { UpdateScheduler.checkNow(context, anyNetwork = true) },
            enabled = state !is UpdateState.Downloading && state != UpdateState.Checking,
        ) { Text(stringResource(R.string.updates_check_now)) }
    }

    Toggle(R.string.updates_packs, toggles.getValue(NetworkFeature.PACK_DOWNLOADS), R.string.updates_packs_note) {
        network.set(NetworkFeature.PACK_DOWNLOADS, it)
    }
    Toggle(R.string.updates_app, toggles.getValue(NetworkFeature.APP_UPDATES), R.string.updates_app_note) {
        network.set(NetworkFeature.APP_UPDATES, it)
        if (it) UpdateScheduler.checkNow(context, anyNetwork = false)
    }
    if (toggles.getValue(NetworkFeature.APP_UPDATES) && !canInstall) {
        OutlinedButton(
            onClick = { context.openInstallPermission() },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) { Text(stringResource(R.string.updates_allow_install)) }
    }
    Toggle(R.string.updates_mobile, mobileData, R.string.updates_mobile_note) {
        mobileData = it
        UpdateScheduler.setAllowMobileData(context, it)
    }
    GitHubToken(secrets)
    Section(R.string.regions_title)
    RegionPicker(packStore)
    InstalledPacks(packs)
}

private data class PackLine(val name: String, val sizeMb: Long, val modified: Long)

private fun installedPacks(store: PackStore, tiles: TileFolder): List<PackLine> =
    store.installed().map { PackLine("${it.file.name} (${Regions.nameOf(it.region)})", it.file.length() shr 20, it.file.lastModified()) } +
        // Directions from southbound-v2.json's packs live unpacked in one folder: one line for all of them.
        listOfNotNull(tiles.summary()?.let { PackLine("routing (${it.packs} packs)", it.bytes shr 20, it.modified) })

/** What's on the phone now, so a missing or stale pack is easy to spot. */
@Composable
private fun InstalledPacks(packs: List<PackLine>) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.updates_packs_installed), style = MaterialTheme.typography.labelLarge)
        if (packs.isEmpty()) {
            Text(stringResource(R.string.updates_packs_none), style = MaterialTheme.typography.bodySmall)
        }
        for (pack in packs) {
            Text(
                stringResource(
                    R.string.updates_pack_line,
                    pack.name,
                    pack.sizeMb,
                    DateUtils.formatDateTime(LocalContext.current, pack.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UpdateProgress(state: UpdateState) {
    when (state) {
        UpdateState.Checking -> {
            Text(stringResource(R.string.updates_checking), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is UpdateState.Downloading -> {
            Text(
                stringResource(R.string.updates_downloading, state.file, state.overallDone shr 20, state.overallTotal shr 20),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(
                progress = { if (state.overallTotal > 0) state.overallDone.toFloat() / state.overallTotal else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is UpdateState.AwaitingInstall -> Text(stringResource(R.string.updates_awaiting, state.version), style = MaterialTheme.typography.bodySmall)
        is UpdateState.UpToDate -> Text(stringResource(R.string.updates_up_to_date), style = MaterialTheme.typography.bodySmall)
        is UpdateState.Updated -> Text(
            stringResource(R.string.updates_installed, state.packs.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
        )
        is UpdateState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        UpdateState.Idle -> Unit
    }
}

/** An optional GitHub token (the repository is public; one only lifts the rate limit). Stored encrypted. */
@Composable
private fun GitHubToken(secrets: SecretStore) {
    var hasToken by remember { mutableStateOf(secrets.has(ApiKey.GITHUB_RELEASES)) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.updates_token_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(stringResource(if (hasToken) R.string.updates_token_saved else R.string.updates_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                secrets.set(ApiKey.GITHUB_RELEASES, draft)
                hasToken = secrets.has(ApiKey.GITHUB_RELEASES)
                draft = ""
                UpdateScheduler.checkNow(context, anyNetwork = false)
            }, enabled = draft.isNotBlank()) { Text(stringResource(R.string.updates_token_save)) }
            if (hasToken) {
                TextButton(onClick = {
                    secrets.set(ApiKey.GITHUB_RELEASES, null)
                    hasToken = false
                }) { Text(stringResource(R.string.updates_token_remove)) }
            }
        }
    }
}

@Composable
private fun versionLine(context: Context): String {
    val installed = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    val latest = UpdateScheduler.latestVersion(context)
    val checked = UpdateScheduler.lastCheck(context)
    return if (latest == null || checked == 0L) {
        stringResource(R.string.updates_version_never, installed)
    } else {
        stringResource(
            R.string.updates_version,
            installed,
            latest,
            DateUtils.getRelativeTimeSpanString(checked, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    }
}

private fun Context.openInstallPermission() {
    startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
