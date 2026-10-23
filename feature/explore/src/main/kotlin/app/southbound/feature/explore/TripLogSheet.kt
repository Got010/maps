package app.southbound.feature.explore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.userdata.Distance
import app.southbound.core.userdata.Leg
import app.southbound.core.userdata.LegCsv
import app.southbound.core.userdata.LegLog
import app.southbound.core.userdata.TripTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** What the log asks to confirm or type before it changes anything. */
private sealed interface LogAsk {
    data class DeleteLeg(val leg: Leg) : LogAsk
    data class DeleteTrip(val trip: TripTotals) : LogAsk
    data class Rename(val trip: String) : LogAsk
}

/**
 * Map options > Trip log: the trips with their totals and day range, newest first, and under each its legs (date,
 * From to To, mode, distance, "edited"), each with Edit and Delete; per trip Share CSV, Share mileage line, Save CSV
 * file, Rename and Delete. Deletes ask first. Never opened during guidance (the caller closes it when a trip starts).
 *
 * A modal bottom sheet, its own window over the map. The list is a LazyColumn, so it scrolls in landscape too. Which
 * trips are open, the pending CSV and the dialogs are `remember` only; the log itself is [log], read from the sealed
 * places file. Sharing is text through the share sheet ([LegShare.shareText]); a CSV file is written only to the
 * document the system file picker returns, read back, and removed if that fails.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TripLogSheet(
    log: LegLog,
    onEditLeg: (Leg) -> Unit,
    /** Each answers null when it was done, or the sentence that says why not. */
    onDeleteLeg: suspend (Leg) -> String?,
    onDeleteTrip: suspend (String) -> String?,
    onRenameTrip: suspend (String, String) -> String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<Set<String>?>(null) }
    var ask by remember { mutableStateOf<LogAsk?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // The CSV waiting for the file picker's answer: memory only.
    var pendingCsv by remember { mutableStateOf<String?>(null) }

    val savedText = stringResource(R.string.trip_log_saved_file)
    val removedText = stringResource(R.string.trip_log_save_file_failed)
    val leftText = stringResource(R.string.trip_log_save_file_not_removed)
    val shareTitle = stringResource(R.string.trip_log_share_title)
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pendingCsv
        pendingCsv = null
        if (uri != null && csv != null) {
            scope.launch {
                val written = withContext(Dispatchers.IO) { LegShare.writeCsv(context.contentResolver, uri, csv) }
                message = when (written) {
                    LegShare.Written.DONE -> savedText
                    LegShare.Written.FAILED_REMOVED -> removedText
                    LegShare.Written.FAILED_LEFT -> leftText
                }
            }
        }
    }

    val trips = log.trips
    // The newest trip starts open; the rest open with a tap on their name.
    val shownOpen = open ?: setOfNotNull(trips.firstOrNull()?.trip)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.trip_log_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.trip_log_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (trips.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.trip_log_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            for (trip in trips) {
                val isOpen = trip.trip in shownOpen
                item(key = "trip:${trip.trip}") {
                    // One column per item: a lazy item's children would otherwise be laid over each other.
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        TripHeader(
                            trip = trip,
                            isOpen = isOpen,
                            onToggle = { open = if (isOpen) shownOpen - trip.trip else shownOpen + trip.trip },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { LegShare.shareText(context, LegCsv.csv(trip.legs), shareTitle) }) {
                                Text(stringResource(R.string.trip_log_share_csv))
                            }
                            TextButton(onClick = { LegShare.shareText(context, LegCsv.mileageLine(trip), shareTitle) }) {
                                Text(stringResource(R.string.trip_log_share_line))
                            }
                            TextButton(onClick = {
                                pendingCsv = LegCsv.csv(trip.legs)
                                saveFile.launch(LegCsv.fileName(LocalDate.now()))
                            }) { Text(stringResource(R.string.trip_log_save_csv)) }
                            TextButton(onClick = { ask = LogAsk.Rename(trip.trip) }) { Text(stringResource(R.string.trip_log_rename)) }
                            TextButton(onClick = { ask = LogAsk.DeleteTrip(trip) }) {
                                Text(stringResource(R.string.trip_log_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (isOpen) {
                    items(trip.legs, key = { "leg:${it.id}" }) { leg ->
                        LegRow(leg, onEdit = { onEditLeg(leg) }, onDelete = { ask = LogAsk.DeleteLeg(leg) })
                    }
                }
            }
        }
    }

    // The dialogs: each its own window, over the sheet.
    when (val asking = ask) {
        null -> Unit
        is LogAsk.DeleteLeg -> ConfirmDialog(
            title = stringResource(R.string.trip_log_delete_leg_title),
            body = stringResource(
                R.string.trip_log_delete_leg_body,
                asking.leg.on,
                stringResource(R.string.trip_log_leg_route, asking.leg.from, asking.leg.to),
            ),
            onConfirm = {
                ask = null
                scope.launch { message = onDeleteLeg(asking.leg) }
            },
            onDismiss = { ask = null },
        )
        is LogAsk.DeleteTrip -> ConfirmDialog(
            title = stringResource(R.string.trip_log_delete_trip_title, asking.trip.trip),
            body = stringResource(R.string.trip_log_delete_trip_body, legCount(asking.trip.count)),
            onConfirm = {
                ask = null
                scope.launch { message = onDeleteTrip(asking.trip.trip) }
            },
            onDismiss = { ask = null },
        )
        is LogAsk.Rename -> RenameDialog(
            current = asking.trip,
            onRename = { name ->
                ask = null
                scope.launch { message = onRenameTrip(asking.trip, name) }
            },
            onDismiss = { ask = null },
        )
    }
}

@Composable
private fun TripHeader(trip: TripTotals, isOpen: Boolean, onToggle: () -> Unit) {
    val toggle = stringResource(if (isOpen) R.string.trip_log_collapse else R.string.trip_log_expand, trip.trip)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = toggle, onClick = onToggle)
            .semantics { contentDescription = toggle }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(trip.trip, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val range = if (trip.firstDay == trip.lastDay) trip.firstDay else stringResource(R.string.trip_log_range, trip.firstDay, trip.lastDay)
            Text(
                stringResource(
                    R.string.trip_log_totals,
                    Distance.format(trip.tenthsMiles),
                    Distance.format(trip.tenthsKm),
                    legCount(trip.count),
                    range,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun LegRow(leg: Leg, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            val miles = Distance.format(Distance.tenthsMiles(leg.meters))
            Text(
                stringResource(if (leg.edited) R.string.trip_log_leg_edited else R.string.trip_log_leg_line, leg.on, legModeLabel(leg.mode), miles),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.trip_log_leg_route, leg.from, leg.to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.trip_log_edit_leg)) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.trip_log_delete_leg)) }
    }
}

/** "1 leg", "5 legs". */
@Composable
private fun legCount(n: Int): String = if (n == 1) stringResource(R.string.trip_log_one_leg) else stringResource(R.string.trip_log_legs, n)

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.trip_log_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.leg_cancel)) } },
    )
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    val cleaned = Leg.clean(name, Leg.MAX_TRIP)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trip_log_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.leg_trip)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(cleaned) }, enabled = Leg.oneLine(cleaned, Leg.MAX_TRIP)) { Text(stringResource(R.string.trip_log_rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.leg_cancel)) } },
    )
}
