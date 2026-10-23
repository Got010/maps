package app.southbound.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.map.RoadColors
import app.southbound.core.road.RoadCategory
import app.southbound.core.road.RoadFilter
import app.southbound.feature.search.icon

/**
 * Keeps the Road filter through a rotation as the line [RoadFilter.encode] writes. View state only: it lives in the
 * saved-instance state, never in the preferences, and a killed app starts with the default seven shown.
 */
internal val RoadFilterSaver: Saver<RoadFilter, String> = Saver(save = { it.encode() }, restore = { RoadFilter.decode(it) })

/**
 * Whether the choices can be applied: they show something. Done is enabled only then, and closing the sheet any other
 * way (swipe down, a tap outside, Back) applies the draft exactly when this holds; otherwise it discards.
 */
internal val RoadFilter.canApply: Boolean
    get() = !showsNothing

/**
 * "Show on the map": which kinds of place the Road layer draws, one chip each with the map's colour and glyph. The
 * choices are a draft: Done applies them (and turns the layer on), Reset goes back to the everyday seven (campsites, RV
 * sites, rest areas, dump stations, water, fuel and truck stops), All ticks all sixteen, and closing the sheet any other
 * way applies them too (as Done does) when they show something; when they show nothing, closing it leaves the map as it was.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RoadChooserSheet(current: RoadFilter, onDone: (RoadFilter) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(stateSaver = RoadFilterSaver) { mutableStateOf(current) }
    // The one way out: Done and every dismissal go through here, so they cannot drift apart.
    val finish by rememberUpdatedState { if (draft.canApply) onDone(draft) else onDismiss() }
    ModalBottomSheet(onDismissRequest = { finish() }) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.explore_road_chooser_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.explore_road_kinds),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                for (category in RoadCategory.entries) {
                    FilterChip(
                        selected = draft.matches(category),
                        onClick = { draft = draft.toggle(category) },
                        label = { Text(category.displayName) },
                        leadingIcon = {
                            Icon(category.icon(), contentDescription = null, Modifier.size(18.dp), tint = Color(RoadColors.argb(category.code)))
                        },
                    )
                }
            }

            if (draft.showsNothing) {
                Text(
                    stringResource(R.string.explore_road_nothing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = RoadFilter.ALL }) { Text(stringResource(R.string.explore_road_all)) }
                TextButton(onClick = { draft = RoadFilter.DEFAULT }) { Text(stringResource(R.string.explore_road_reset)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { finish() }, enabled = draft.canApply) { Text(stringResource(R.string.explore_road_done)) }
            }
        }
    }
}
