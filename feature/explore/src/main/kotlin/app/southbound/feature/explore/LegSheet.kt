package app.southbound.feature.explore

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.userdata.Distance
import app.southbound.core.userdata.Leg
import app.southbound.core.userdata.LegLog
import app.southbound.core.userdata.LegMode
import kotlinx.coroutines.launch

/**
 * Under the route options, above Start: **Log this leg**, or, once this route was logged, "Logged to <trip> · 212.4 mi"
 * with Edit. Logging is only ever this tap; Start, reroutes, Pause and Resume never write to the log.
 */
@Composable
internal fun LogLegRow(logged: Leg?, onLog: () -> Unit, onEdit: (Leg) -> Unit) {
    if (logged == null) {
        OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Receipt, contentDescription = null)
            Text(stringResource(R.string.leg_log_this), Modifier.padding(start = 8.dp))
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.leg_logged, logged.trip, Distance.format(Distance.tenthsMiles(logged.meters))),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = { onEdit(logged) }) { Text(stringResource(R.string.leg_logged_edit)) }
        }
    }
}

/**
 * The leg sheet, for a new leg from the route preview or a leg from the log: trip (the trips used as chips, the last
 * one preselected), date, miles (the chosen option's, prefilled; a change marks the leg edited), mode, From and To. The
 * labels are never filled from the map or a pack: the user types them or taps a chip, the log's own labels or, for To,
 * [searched] ("What I searched: ..."), offered only when this route's destination is the place just picked from a
 * typed search. Autocorrect is off on every field.
 *
 * A modal bottom sheet (its own window, over the map and anything on it). The draft lives in `remember` only: the
 * activity handles rotation itself, so it survives one, and nothing typed here is saved state. [onSave] writes on IO and
 * answers null when the leg was kept, or the sentence that says why not; the sheet stays open with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LegSheet(
    start: LegDraft,
    log: LegLog,
    searched: String?,
    onSave: suspend (LegDraft) -> String?,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(start) { mutableStateOf(start) }
    var error by remember(start) { mutableStateOf<String?>(null) }
    var saving by remember(start) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val searchedText = LegDraft.searchedChip(searched)
    val noLearning = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(if (start.legId == null) R.string.leg_sheet_new else R.string.leg_sheet_edit),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.leg_sheet_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = draft.trip,
                onValueChange = { draft = draft.copy(trip = it) },
                label = { Text(stringResource(R.string.leg_trip)) },
                singleLine = true,
                keyboardOptions = noLearning,
                modifier = Modifier.fillMaxWidth(),
            )
            Chips(LegDraft.tripChips(log)) { draft = draft.copy(trip = it) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft.day,
                    onValueChange = { draft = draft.copy(day = it) },
                    label = { Text(stringResource(R.string.leg_date)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { LegDraft.shiftDay(draft.day, -1)?.let { draft = draft.copy(day = it) } }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.leg_date_earlier))
                }
                IconButton(onClick = { LegDraft.shiftDay(draft.day, 1)?.let { draft = draft.copy(day = it) } }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.leg_date_later))
                }
            }

            OutlinedTextField(
                value = draft.miles,
                onValueChange = { draft = draft.copy(miles = it) },
                label = { Text(stringResource(R.string.leg_miles)) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.leg_miles_planned, draft.plannedMiles)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in LegMode.entries) {
                    FilterChip(
                        selected = draft.mode == mode,
                        onClick = { draft = draft.copy(mode = mode) },
                        label = { Text(legModeLabel(mode)) },
                    )
                }
            }

            OutlinedTextField(
                value = draft.from,
                onValueChange = { draft = draft.copy(from = it) },
                label = { Text(stringResource(R.string.leg_from)) },
                singleLine = true,
                keyboardOptions = noLearning,
                modifier = Modifier.fillMaxWidth(),
            )
            Chips(LegDraft.fromChips(log, draft.trip)) { draft = draft.copy(from = it) }

            OutlinedTextField(
                value = draft.to,
                onValueChange = { draft = draft.copy(to = it) },
                label = { Text(stringResource(R.string.leg_to)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (searchedText != null) {
                    SuggestionChip(
                        onClick = { draft = draft.copy(to = searchedText) },
                        label = { Text(stringResource(R.string.leg_searched_chip, searchedText), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp)) },
                    )
                }
                for (label in LegDraft.toChips(log)) {
                    SuggestionChip(
                        onClick = { draft = draft.copy(to = label) },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                    )
                }
            }
            if (searchedText != null) {
                // The consequence of the chip, said once: search text usually names the place.
                Text(stringResource(R.string.leg_searched_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.leg_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !saving,
                    onClick = {
                        // Checked here first so a field out of bounds says so at once; the store checks again.
                        val problem = draft.problem()
                        if (problem != null) {
                            error = problem
                        } else {
                            saving = true
                            error = null
                            val sent = draft
                            scope.launch {
                                val refused = onSave(sent)
                                saving = false
                                if (refused == null) onDone() else error = refused
                            }
                        }
                    },
                ) { Text(stringResource(R.string.leg_save)) }
            }
        }
    }
}

/** A row of chips that scrolls sideways; a tap puts the chip's text in its field. */
@Composable
private fun Chips(labels: List<String>, onPick: (String) -> Unit) {
    if (labels.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (label in labels) {
            SuggestionChip(
                onClick = { onPick(label) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
            )
        }
    }
}

@Composable
internal fun legModeLabel(mode: LegMode): String = stringResource(
    when (mode) {
        LegMode.DRIVE -> R.string.leg_mode_drive
        LegMode.WALK -> R.string.leg_mode_walk
        LegMode.BIKE -> R.string.leg_mode_bike
    },
)
