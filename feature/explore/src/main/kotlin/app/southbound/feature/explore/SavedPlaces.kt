package app.southbound.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import app.southbound.core.search.Place
import app.southbound.core.search.PlaceKind
import app.southbound.core.userdata.PlaceRef
import app.southbound.core.userdata.Shortcut
import app.southbound.core.userdata.UserData
import app.southbound.feature.search.PlaceGroup

/** A place from the search pack or a long press, as the user data keeps it. */
fun Place.toRef(): PlaceRef = PlaceRef(name, lat, lon, address, category, osmRef)

/** A kept place back as a [Place] for the card and directions (no search row, so id -1). */
fun PlaceRef.toPlace(): Place =
    Place(id = -1, kind = PlaceKind.PLACE, name = name, category = category, address = address, lat = lat, lon = lon, osmRef = osmRef)

/** The saved lists and recents as the search panel shows them while the box is empty. */
@Composable
fun UserData.placeGroups(): List<PlaceGroup> =
    lists.map { list -> PlaceGroup(list.name, placesIn(list.id).map { it.place.toPlace() }) } +
        PlaceGroup(stringResource(R.string.explore_recents), recents.map { it.place.toPlace() }, recents = true)

/**
 * Save to lists: a checkbox per list and a field for a new one. Changes apply
 * at once, so there is only Done.
 */
@Composable
fun SaveDialog(
    place: Place,
    data: UserData,
    onToggle: (listId: Long, saved: Boolean) -> Unit,
    onCreateList: (String) -> Unit,
    onDismiss: () -> Unit,
    // Phase 6: Set as Home / Set as Work; called with null to clear it.
    onSetShortcut: (Shortcut, Boolean) -> Unit = { _, _ -> },
) {
    val ref = place.toRef()
    val inLists = data.listsContaining(ref).map { it.id }.toSet()
    var newList by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explore_save_title, place.name)) },
        text = {
            Column {
                for (list in data.lists) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = list.id in inLists, onCheckedChange = { onToggle(list.id, it) })
                        Text(list.name, Modifier.weight(1f))
                    }
                }
                // Phase 6: Home and Work.
                val shortcuts = data.shortcutsOf(ref)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (kind in Shortcut.entries) {
                        val isSet = kind in shortcuts
                        OutlinedButton(onClick = { onSetShortcut(kind, !isSet) }) {
                            Text(
                                stringResource(
                                    when {
                                        kind == Shortcut.HOME && isSet -> R.string.explore_shortcut_clear_home
                                        kind == Shortcut.HOME -> R.string.explore_shortcut_set_home
                                        isSet -> R.string.explore_shortcut_clear_work
                                        else -> R.string.explore_shortcut_set_work
                                    },
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newList,
                        onValueChange = { newList = it },
                        label = { Text(stringResource(R.string.explore_save_new_list)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onCreateList(newList)
                            newList = ""
                        },
                        enabled = newList.isNotBlank(),
                    ) { Text(stringResource(R.string.explore_save_add)) }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.explore_save_done)) } },
    )
}

/**
 * Shown when the saved places file exists but could not be read. "Keep trying" leaves the file
 * alone; "Start fresh" sets it aside under a new name (never deleting it) and begins empty.
 */
@Composable
fun UnreadableDataDialog(onKeepTrying: () -> Unit, onStartFresh: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepTrying,
        title = { Text(stringResource(R.string.explore_unreadable_title)) },
        text = { Text(stringResource(R.string.explore_unreadable_body)) },
        confirmButton = { TextButton(onClick = onKeepTrying) { Text(stringResource(R.string.explore_unreadable_keep)) } },
        dismissButton = { TextButton(onClick = onStartFresh) { Text(stringResource(R.string.explore_unreadable_fresh)) } },
    )
}
