package app.southbound.feature.directions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.routing.RoutePrefs
import app.southbound.core.routing.VehicleKind
import java.util.Locale

// Phase 6: pieces of the route preview sheet that Phase 6 adds, kept out of RoutePreview.kt.

/** The "Options" chip that opens [RouteOptionsSheet]. */
@Composable
fun RouteOptionsChip(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(stringResource(if (active) R.string.options_chip_active else R.string.options_chip)) },
        leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, Modifier.size(18.dp)) },
    )
}

/**
 * The stops between here and the destination, each with up, down and remove,
 * and Add stop. Shows nothing when there are no stops and [onAdd] is null.
 */
@Composable
fun StopsEditor(
    stops: List<String>,
    onMove: (index: Int, delta: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (stops.isEmpty() && onAdd == null) return
    Column(modifier.fillMaxWidth()) {
        stops.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.stop_row, i + 1, name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(i, -1) }, enabled = i > 0) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.stop_up, name))
                }
                IconButton(onClick = { onMove(i, 1) }, enabled = i < stops.lastIndex) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.stop_down, name))
                }
                IconButton(onClick = { onRemove(i) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.stop_remove, name))
                }
            }
        }
        if (onAdd != null) {
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.AddLocationAlt, contentDescription = null, Modifier.size(18.dp))
                Text(stringResource(R.string.stop_add), Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** "Map for X isn't installed" with a Download button, shown when the route leaves the installed regions. */
@Composable
fun MissingRegionWarning(regionNames: List<String>, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    if (regionNames.isEmpty()) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.region_missing, regionNames.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDownload) { Text(stringResource(R.string.region_download)) }
    }
}

/**
 * Route options: avoid tolls, highways and ferries, and the vehicle (car, or
 * truck/RV with its size and weight). Every change applies at once; the
 * caller saves it and recomputes the routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteOptionsSheet(prefs: RoutePrefs, onChange: (RoutePrefs) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.options_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.options_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OptionSwitch(R.string.options_avoid_tolls, prefs.avoidTolls) { onChange(prefs.copy(avoidTolls = it)) }
            OptionSwitch(R.string.options_avoid_highways, prefs.avoidHighways) { onChange(prefs.copy(avoidHighways = it)) }
            OptionSwitch(R.string.options_avoid_ferries, prefs.avoidFerries) { onChange(prefs.copy(avoidFerries = it)) }

            Text(stringResource(R.string.options_vehicle), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = prefs.vehicle == VehicleKind.CAR,
                    onClick = { onChange(prefs.copy(vehicle = VehicleKind.CAR)) },
                    label = { Text(stringResource(R.string.options_car)) },
                )
                FilterChip(
                    selected = prefs.vehicle == VehicleKind.TRUCK,
                    onClick = { onChange(prefs.copy(vehicle = VehicleKind.TRUCK)) },
                    label = { Text(stringResource(R.string.options_truck)) },
                )
            }
            if (prefs.vehicle == VehicleKind.TRUCK) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(R.string.options_height, prefs.heightFt, Modifier.weight(1f)) { onChange(prefs.copy(heightFt = it)) }
                    NumberField(R.string.options_width, prefs.widthFt, Modifier.weight(1f)) { onChange(prefs.copy(widthFt = it)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(R.string.options_length, prefs.lengthFt, Modifier.weight(1f)) { onChange(prefs.copy(lengthFt = it)) }
                    NumberField(R.string.options_weight, prefs.weightTons, Modifier.weight(1f)) { onChange(prefs.copy(weightTons = it)) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.options_done)) }
        }
    }
}

@Composable
private fun OptionSwitch(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A decimal field that keeps what is typed and reports only values that parse. */
@Composable
private fun NumberField(label: Int, value: Double, modifier: Modifier, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(format(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            typed.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let(onValue)
        },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun format(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
