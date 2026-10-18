package app.southbound.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.southbound.core.map.WifiColors
import app.southbound.core.wifi.WifiFilter
import app.southbound.core.wifi.WifiKind
import app.southbound.core.wifi.WifiTier

/**
 * Keeps the Wi-Fi filter through a rotation as the line [WifiFilter.encode] writes. View state only: it lives in the
 * saved-instance state, never in the preferences, and a killed app starts with everything shown.
 */
internal val WifiFilterSaver: Saver<WifiFilter, String> = Saver(save = { it.encode() }, restore = { WifiFilter.decode(it) })

/**
 * "Show on the map": what the Free Wi-Fi layer draws. The choices are a draft: Done applies them (and turns the layer
 * on), Reset ticks everything again, and closing the sheet any other way leaves the map as it was.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun WifiChooserSheet(current: WifiFilter, onDone: (WifiFilter) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(stateSaver = WifiFilterSaver) { mutableStateOf(current) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.explore_wifi_chooser_title), style = MaterialTheme.typography.titleMedium)

            Text(
                stringResource(R.string.explore_wifi_confidence),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            for (tier in WifiTier.entries) {
                ChooserRow(
                    checked = tier in draft.tiers,
                    onChange = { draft = draft.withTier(tier, it) },
                    role = Role.Checkbox,
                    control = { Checkbox(checked = tier in draft.tiers, onCheckedChange = null) },
                ) {
                    Box(Modifier.size(12.dp).background(Color(WifiColors.argb(tier.code)), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(tierLabel(tier)))
                }
            }

            ChooserRow(
                checked = draft.customersOnly,
                onChange = { draft = draft.copy(customersOnly = it) },
                role = Role.Switch,
                control = { Switch(checked = draft.customersOnly, onCheckedChange = null) },
                controlAtEnd = true,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.explore_wifi_customers_only))
                    Text(
                        stringResource(R.string.explore_wifi_customers_only_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                stringResource(R.string.explore_wifi_kinds),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                FilterChip(
                    selected = draft.kinds == null,
                    onClick = { draft = draft.copy(kinds = null) },
                    label = { Text(stringResource(R.string.explore_wifi_kind_all)) },
                )
                for (kind in WifiKind.entries) {
                    FilterChip(
                        selected = draft.kinds?.contains(kind) == true,
                        onClick = { draft = draft.toggleKind(kind) },
                        label = { Text(stringResource(kindLabel(kind))) },
                    )
                }
            }

            if (draft.showsNothing) {
                Text(
                    stringResource(R.string.explore_wifi_nothing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = WifiFilter.DEFAULT }) { Text(stringResource(R.string.explore_wifi_reset)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onDone(draft) }) { Text(stringResource(R.string.explore_wifi_done)) }
            }
        }
    }
}

/** A whole row that toggles: the control is drawn by the row's own click, so it has no click of its own. */
@Composable
private fun ChooserRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    role: Role,
    control: @Composable () -> Unit,
    controlAtEnd: Boolean = false,
    label: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = role, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!controlAtEnd) {
            control()
            Spacer(Modifier.width(8.dp))
        }
        label()
        if (controlAtEnd) {
            Spacer(Modifier.width(8.dp))
            control()
        }
    }
}

private fun tierLabel(tier: WifiTier): Int = when (tier) {
    WifiTier.TAGGED_FREE -> R.string.explore_wifi_tier_free
    WifiTier.TAGGED_UNSTATED -> R.string.explore_wifi_tier_unstated
    WifiTier.CHAIN -> R.string.explore_wifi_tier_chain
    WifiTier.LIKELY -> R.string.explore_wifi_tier_likely
}

private fun kindLabel(kind: WifiKind): Int = when (kind) {
    WifiKind.CAFES_FOOD -> R.string.explore_wifi_kind_food
    WifiKind.SHOPS -> R.string.explore_wifi_kind_shops
    WifiKind.LIBRARIES -> R.string.explore_wifi_kind_libraries
    WifiKind.HOTELS -> R.string.explore_wifi_kind_hotels
    WifiKind.REST_AREAS -> R.string.explore_wifi_kind_rest
    WifiKind.FUEL -> R.string.explore_wifi_kind_fuel
    WifiKind.OTHER -> R.string.explore_wifi_kind_other
}
