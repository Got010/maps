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
import app.southbound.core.packs.RoadSelection
import app.southbound.core.updates.ManifestCache
import app.southbound.core.updates.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROAD_REGION = PackKind.ROAD_REGION

/**
 * Settings > Maps > Road pack: off by default, so no phone downloads it unasked. On turns it on and starts an
 * update round the way ticking a region does; off deletes the pack from the phone. The size is from the last release
 * manifest an update round read (the cache `Get for this trip` reads), "size unknown" before the first.
 */
@Composable
internal fun RoadPackRow(packStore: PackStore, packDownloadsOn: Boolean) {
    val context = LocalContext.current
    val selection = remember { RoadSelection.get(context) }
    val wanted by selection.wanted.collectAsState()
    val scope = rememberCoroutineScope()
    var sizeBytes by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<PackStatus?>(null) }
    LaunchedEffect(packStore) {
        sizeBytes = withContext(Dispatchers.IO) { ManifestCache.load(context)?.pack(PackKind.ROAD, ROAD_REGION)?.file?.size }
        status = withContext(Dispatchers.IO) { packStore.status(PackKind.ROAD, ROAD_REGION) }
        // A round landing the pack, or this switch removing it, shows at once.
        merge(PackEvents.installed.map { }, PackEvents.removed.map { }).collect {
            status = withContext(Dispatchers.IO) { packStore.status(PackKind.ROAD, ROAD_REGION) }
        }
    }
    val size = sizeBytes?.let { stringResource(R.string.road_pack_size, megabytes(it)) } ?: stringResource(R.string.road_pack_size_unknown)
    JumpTarget(R.string.road_pack_title) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.road_pack_title)) },
            supportingContent = {
                Note(stringResource(R.string.road_pack_note, size))
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
                                withContext(Dispatchers.IO) { packStore.deleteAll(PackKind.ROAD) }
                                PackEvents.emitRemoved(PackKind.ROAD, PackKind.ROAD_REGION)
                                status = withContext(Dispatchers.IO) { packStore.status(PackKind.ROAD, ROAD_REGION) }
                            }
                        }
                    },
                )
            },
            colors = rowColors(),
        )
    }
    val line = when (val s = status) {
        is PackStatus.Installed -> stringResource(R.string.road_pack_installed, megabytes(s.file.length()))
        // There, but not a Road pack (or cut short): left alone, and an update round puts a good one over it.
        is PackStatus.Invalid -> stringResource(R.string.road_pack_invalid)
        else -> if (!wanted) null else stringResource(if (packDownloadsOn) R.string.road_pack_pending else R.string.road_pack_pending_off)
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
