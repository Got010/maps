package app.southbound.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import app.southbound.core.packs.ImportedPack
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStore
import app.southbound.core.packs.RegionSelection
import app.southbound.core.packs.Regions
import app.southbound.core.updates.UpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The region picker: one checkbox per basemap region. Ticking a region
 * queues its download; unticking deletes its map from the phone. Shown on
 * the first-run screen and in Settings > Updates.
 */
@Composable
fun RegionPicker(packStore: PackStore, modifier: Modifier = Modifier, showNote: Boolean = true) {
    val context = LocalContext.current
    val selection = remember { RegionSelection.get(context) }
    val selected by selection.regions.collectAsState()
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<ImportedPack>>(emptyList()) }
    LaunchedEffect(packStore) {
        installed = withContext(Dispatchers.IO) { packStore.installedBasemaps() }
        PackEvents.installed.collect { if (it.kind == PackKind.BASEMAP) installed = withContext(Dispatchers.IO) { packStore.installedBasemaps() } }
    }

    Column(modifier.fillMaxWidth()) {
        if (showNote) {
            Text(
                stringResource(R.string.regions_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        for (region in Regions.basemaps) {
            val pack = installed.firstOrNull { it.region == region.id }
            val checked = region.id in selected
            ListItem(
                headlineContent = { Text(region.name) },
                supportingContent = {
                    Text(
                        when {
                            pack != null -> stringResource(R.string.regions_installed, pack.file.length() shr 20)
                            checked -> stringResource(R.string.regions_pending)
                            else -> stringResource(R.string.regions_not_installed)
                        },
                    )
                },
                trailingContent = {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { on ->
                            selection.set(region.id, on)
                            if (on) {
                                UpdateScheduler.checkNow(context, anyNetwork = false)
                            } else {
                                scope.launch {
                                    withContext(Dispatchers.IO) { packStore.delete(PackKind.BASEMAP, region.id) }
                                    installed = withContext(Dispatchers.IO) { packStore.installedBasemaps() }
                                    PackEvents.emitRemoved(PackKind.BASEMAP, region.id)
                                }
                            }
                        },
                    )
                },
            )
        }
    }
}
