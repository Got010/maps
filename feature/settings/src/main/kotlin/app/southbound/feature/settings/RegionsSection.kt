package app.southbound.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.packs.Coverage
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
 * The region picker: one checkbox per basemap region, under its state's
 * heading. Ticking a region queues its download; unticking deletes its map
 * from the phone, and the next update round its directions and search.
 * A region fetched for a trip from the route preview says so, with Keep to
 * make it one of the user's own. Shown on the first-run screen and in
 * Settings > Updates.
 */
@Composable
fun RegionPicker(packStore: PackStore, modifier: Modifier = Modifier, showNote: Boolean = true) {
    val context = LocalContext.current
    val selection = remember { RegionSelection.get(context) }
    val selected by selection.regions.collectAsState()
    val trip by selection.tripRegions.collectAsState()
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
        for ((state, regions) in Regions.byState) {
            // A heading where a state has several regions; a one-region state reads as itself.
            if (regions.size > 1) {
                Text(
                    state,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            // What this state's maps come without (no traffic feed, or one that needs a key), before it is picked.
            Coverage.pickerNote(state)?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (regions.size > 1) 0.dp else 8.dp),
                )
            }
            for (region in regions) {
                val pack = installed.firstOrNull { it.region == region.id }
                val checked = region.id in selected
                ListItem(
                    headlineContent = { Text(region.name) },
                    supportingContent = {
                        Column {
                            Text(
                                when {
                                    pack != null -> stringResource(R.string.regions_installed, pack.file.length() shr 20)
                                    checked -> stringResource(R.string.regions_pending)
                                    else -> stringResource(R.string.regions_not_installed)
                                },
                            )
                            if (region.id in trip) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.regions_trip), modifier = Modifier.weight(1f))
                                    TextButton(onClick = { selection.set(region.id, true) }) { Text(stringResource(R.string.regions_keep)) }
                                }
                            }
                        }
                    },
                    trailingContent = {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                selection.set(region.id, on)
                                if (!on) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { packStore.delete(PackKind.BASEMAP, region.id) }
                                        installed = withContext(Dispatchers.IO) { packStore.installedBasemaps() }
                                        PackEvents.emitRemoved(PackKind.BASEMAP, region.id)
                                    }
                                }
                                // Ticked: its packs download. Unticked: its directions and search go in the same
                                // round (the updater keeps only the picked regions' packs).
                                UpdateScheduler.checkNow(context, anyNetwork = false)
                            },
                        )
                    },
                )
            }
        }
    }
}
