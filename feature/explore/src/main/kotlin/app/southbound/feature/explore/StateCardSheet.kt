package app.southbound.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import app.southbound.core.states.Field
import app.southbound.core.states.StateCard
import app.southbound.core.states.StateCards
import app.southbound.core.states.StateNames

/**
 * Which state's card to show, and what it is compared with. [from] is the state the road came from: with one, the
 * sheet opens on what changes ("Welcome to Ohio"); without ([from] null, the Map options "This state" entry), it is
 * the plain card. Held as one short text so the open sheet survives a rotation ([rememberSaveable]).
 */
internal data class StateCardRequest(val code: String, val from: String? = null) {
    fun encode(): String = if (from == null) code else "$code>$from"

    companion object {
        fun decode(text: String?): StateCardRequest? {
            if (text.isNullOrEmpty()) return null
            val parts = text.split('>')
            return StateCardRequest(parts[0], parts.getOrNull(1)?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * A state's card as a sheet: the 12 fields (time zone, handheld-phone law, speed limits, tolls, the state police short
 * code, move over, radar detectors, open container, cannabis, sales tax, rest-area overnight, daylight time), each with
 * the date it was compiled and a small "verify" tag where the value says to. With [changed] (the fields that differ from
 * the state the road came from) the sheet opens on those, highlighted, and "All fields" shows the rest; without, all
 * fields show. Static data from the app: nothing is fetched or kept. Opens full height and scrolls, so a phone on its
 * side shows all of it; the open/closed state of "All fields" survives a rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StateCardSheet(card: StateCard, changed: List<Field>?, fromCode: String?, onDismiss: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(changed == null) }
    val changedKeys = changed?.map { it.key }?.toSet().orEmpty()
    val shown = if (showAll) card.fields else card.fields.filter { it.key in changedKeys }
    val fromName = fromCode?.let { StateNames.of(it) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (changed != null) stringResource(R.string.explore_state_welcome, card.name) else card.name,
                style = MaterialTheme.typography.titleLarge,
            )
            if (changed != null && fromName != null) {
                Text(
                    if (changed.isEmpty()) stringResource(R.string.explore_state_nothing_changed, fromName) else stringResource(R.string.explore_state_changes, fromName),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (field in shown) FieldRow(field, highlighted = changed != null && field.key in changedKeys)
            if (changed != null && changed.size < card.fields.size) {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(if (showAll) R.string.explore_state_only_changes else R.string.explore_state_all_fields))
                }
            }
            Text(
                stringResource(R.string.explore_state_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.explore_state_close)) }
        }
    }
}

/** One field: its label (with the "verify" tag where the value says so), the value, and when it was compiled. */
@Composable
private fun FieldRow(field: Field, highlighted: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(field.label, style = MaterialTheme.typography.labelLarge)
                if (field.needsVerify) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            stringResource(R.string.explore_state_verify),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(field.value, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.explore_state_as_of, field.asOf),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How long the guidance banner stays before it goes by itself. */
internal const val STATE_BANNER_MS = 12_000L

/**
 * During guidance the card does not take the screen: this small banner over the map says "Entering Ohio" and the two most
 * important fields that changed, goes by itself after [STATE_BANNER_MS], and a tap on it opens the card as the sheet.
 * Non-modal: no scrim, the maneuver banner and the trip controls stay in reach. Nothing is kept.
 */
@Composable
internal fun StateCardBanner(card: StateCard, changed: List<Field>, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(card.code) {
        delay(STATE_BANNER_MS)
        latestDismiss()
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 420.dp).fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.explore_state_entering, card.name), style = MaterialTheme.typography.titleMedium)
            for (field in StateCards.headline(changed)) {
                val brief = StateCards.brief(field) ?: continue
                Text(stringResource(R.string.explore_state_banner_line, field.label, brief), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.explore_state_banner_tap),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
