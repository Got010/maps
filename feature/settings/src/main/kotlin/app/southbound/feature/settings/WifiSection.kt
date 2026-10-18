package app.southbound.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStatus
import app.southbound.core.packs.PackStore
import app.southbound.core.packs.WifiSelection
import app.southbound.core.updates.ManifestCache
import app.southbound.core.updates.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Wi-Fi pack's region: it is one national file, `wifi-us.sqlite`. */
private const val WIFI_REGION = "us"

/** Whole megabytes for the row, never "0 MB" for a pack that has some bytes. */
internal fun megabytes(bytes: Long): Long = ((bytes + (1L shl 19)) shr 20).coerceAtLeast(1)

/**
 * Settings > Maps > Free Wi-Fi pack: off by default, so no phone downloads it unasked. On turns it on and starts an
 * update round the way ticking a region does; off deletes the pack from the phone. The size is from the last release
 * manifest an update round read (the cache `Get for this trip` reads), "size unknown" before the first.
 */
@Composable
internal fun WifiPackRow(packStore: PackStore) {
    val context = LocalContext.current
    val selection = remember { WifiSelection.get(context) }
    val wanted by selection.wanted.collectAsState()
    val scope = rememberCoroutineScope()
    var sizeBytes by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<PackStatus?>(null) }
    LaunchedEffect(packStore) {
        sizeBytes = withContext(Dispatchers.IO) { ManifestCache.load(context)?.pack(PackKind.WIFI, WIFI_REGION)?.file?.size }
        status = withContext(Dispatchers.IO) { packStore.status(PackKind.WIFI, WIFI_REGION) }
        // A round landing the pack, or this switch removing it, shows at once.
        merge(PackEvents.installed.map { }, PackEvents.removed.map { }).collect {
            status = withContext(Dispatchers.IO) { packStore.status(PackKind.WIFI, WIFI_REGION) }
        }
    }
    val size = sizeBytes?.let { stringResource(R.string.wifi_pack_size, megabytes(it)) } ?: stringResource(R.string.wifi_pack_size_unknown)
    JumpTarget(R.string.wifi_pack_title) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.wifi_pack_title)) },
            supportingContent = {
                Note(stringResource(R.string.wifi_pack_note, size))
            },
            trailingContent = {
                Switch(
                    checked = wanted,
                    onCheckedChange = { on ->
                        selection.choose(on)
                        if (on) {
                            // Its download starts in the next update round, as for a ticked region.
                            UpdateScheduler.checkNow(context, anyNetwork = false)
                        } else {
                            // Off: the file goes and the map screen is told, even if Settings is left at once.
                            scope.launch(NonCancellable) {
                                withContext(Dispatchers.IO) { packStore.delete(PackKind.WIFI, WIFI_REGION) }
                                PackEvents.emitRemoved(PackKind.WIFI, WIFI_REGION)
                                status = withContext(Dispatchers.IO) { packStore.status(PackKind.WIFI, WIFI_REGION) }
                            }
                        }
                    },
                )
            },
            colors = rowColors(),
        )
    }
    val line = when (val s = status) {
        is PackStatus.Installed -> stringResource(R.string.wifi_pack_installed, megabytes(s.file.length()))
        // There, but not a Wi-Fi pack (or cut short): left alone, and an update round puts a good one over it.
        is PackStatus.Invalid -> stringResource(R.string.wifi_pack_invalid)
        else -> if (wanted) stringResource(R.string.wifi_pack_pending) else null
    }
    if (line != null) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (status is PackStatus.Invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}
