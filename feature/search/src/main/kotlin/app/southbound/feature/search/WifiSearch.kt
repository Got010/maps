package app.southbound.feature.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.map.Units
import app.southbound.core.map.WifiColors
import app.southbound.core.wifi.WifiLikelyGroup
import app.southbound.core.wifi.WifiResult
import app.southbound.core.wifi.WifiSpot
import app.southbound.core.wifi.WifiTier
import app.southbound.core.wifi.WifiTierFilter
import java.time.LocalDateTime

/** The short confidence label on a list row: "Tagged free", "Tagged, fee not stated", "Chain policy", "Likely". */
@StringRes
internal fun WifiTier.shortLabel(): Int = when (this) {
    WifiTier.TAGGED_FREE -> R.string.wifi_short_tagged_free
    WifiTier.TAGGED_UNSTATED -> R.string.wifi_short_tagged_unstated
    WifiTier.CHAIN -> R.string.wifi_short_chain
    WifiTier.LIKELY -> R.string.wifi_short_likely
}

/** The full label on the sheet; a "Likely" spot says what kind of place it is. */
@StringRes
internal fun WifiSpot.fullLabel(): Int = when (tier) {
    WifiTier.TAGGED_FREE -> R.string.wifi_label_tagged_free
    WifiTier.TAGGED_UNSTATED -> R.string.wifi_label_tagged_unstated
    WifiTier.CHAIN -> R.string.wifi_label_chain
    WifiTier.LIKELY -> when (WifiLikelyGroup.of(category)) {
        WifiLikelyGroup.LIBRARIES -> R.string.wifi_label_likely_libraries
        WifiLikelyGroup.HOTEL_LOBBIES -> R.string.wifi_label_likely_hotels
        WifiLikelyGroup.REST_AREAS -> R.string.wifi_label_likely_rest_areas
        WifiLikelyGroup.VISITOR_CENTERS -> R.string.wifi_label_likely_visitor_centers
        WifiLikelyGroup.OTHER -> R.string.wifi_label_likely_other
    }
}

@StringRes
private fun WifiTierFilter.label(): Int = when (this) {
    WifiTierFilter.ALL -> R.string.wifi_filter_all
    WifiTierFilter.TAGGED -> R.string.wifi_filter_tagged
    WifiTierFilter.CHAIN -> R.string.wifi_filter_chain
    WifiTierFilter.LIKELY -> R.string.wifi_filter_likely
}

/** A filled badge in the tier's colour (the map's), white text. */
@Composable
internal fun WifiBadge(text: String, tier: WifiTier, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.small, color = Color(WifiColors.argb(tier.code))) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The sheet's Wi-Fi rows, for [PlaceCard]'s `extra` slot: how sure the pack is, free for customers when it says so,
 * the chain for a chain's spot, where the data is from, and the captive-portal note. Hours are the card's own line.
 * [extractDate] is the pack's (YYYY-MM-DD), null when it doesn't say.
 */
@Composable
fun WifiDetails(spot: WifiSpot, extractDate: String?, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            WifiBadge(stringResource(spot.fullLabel()), spot.tier)
            if (spot.freeForCustomers) WifiBadge(stringResource(R.string.wifi_fee_customers), spot.tier)
        }
        if (spot.tier == WifiTier.CHAIN) {
            spot.name?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.wifi_chain_name, it), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            if (extractDate != null) stringResource(R.string.wifi_data_from, extractDate) else stringResource(R.string.wifi_data_from_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.wifi_captive_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Tier: All / Tagged / Chain / Likely" and the Open now toggle, above the nearest-Wi-Fi list. */
@Composable
internal fun WifiFilters(
    tier: WifiTierFilter,
    onTier: (WifiTierFilter) -> Unit,
    openNow: Boolean,
    onOpenNow: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.wifi_tier_heading), style = MaterialTheme.typography.labelLarge)
        for (filter in WifiTierFilter.entries) {
            FilterChip(selected = tier == filter, onClick = { onTier(filter) }, label = { Text(stringResource(filter.label())) })
        }
        FilterChip(selected = openNow, onClick = { onOpenNow(!openNow) }, label = { Text(stringResource(R.string.wifi_open_now)) })
    }
}

/** One nearest-Wi-Fi row: name, distance, the short confidence label and the hours summary. */
@Composable
internal fun WifiRow(result: WifiResult, distanceMeters: Double?, now: LocalDateTime, onClick: () -> Unit) {
    val spot = result.spot
    val hours = rememberOpeningHours(spot.openingHours)
    val summary = remember(hours, now) { hours?.summary(now) }
    val hoursLine = summary?.let { hoursText(it) }
    val supporting = listOfNotNull(stringResource(spot.tier.shortLabel()), hoursLine).joinToString(" · ")
    ListItem(
        headlineContent = { Text(spot.name ?: WifiSpot.categoryName(spot.category), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(supporting, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null, tint = Color(WifiColors.argb(spot.tier.code))) },
        trailingContent = distanceMeters?.let { { Text(Units.distance(it), style = MaterialTheme.typography.labelMedium) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
