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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.RvHookup
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Weekend
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.map.RoadColors
import app.southbound.core.map.Units
import app.southbound.core.road.RoadAlongResult
import app.southbound.core.road.RoadCategory
import app.southbound.core.road.RoadChip
import app.southbound.core.road.RoadFilter
import app.southbound.core.road.RoadFlag
import app.southbound.core.road.RoadResult
import app.southbound.core.road.RoadSpot
import java.time.LocalDateTime

/** The Material icon for a category's icon key ([RoadCategory.iconKey]); the map draws its own glyph by the same key. */
fun RoadCategory.icon(): ImageVector = when (iconKey) {
    "tent" -> Icons.Filled.Terrain
    "rv" -> Icons.Filled.RvHookup
    "bench" -> Icons.Filled.Weekend
    "dump" -> Icons.Filled.Delete
    "drop" -> Icons.Filled.WaterDrop
    "toilet" -> Icons.Filled.Wc
    "shower" -> Icons.Filled.Shower
    "pump" -> Icons.Filled.LocalGasStation
    "truck" -> Icons.Filled.LocalShipping
    "laundry" -> Icons.Filled.LocalLaundryService
    "book" -> Icons.Filled.LocalLibrary
    "pill" -> Icons.Filled.LocalPharmacy
    "hospital" -> Icons.Filled.LocalHospital
    "clinic" -> Icons.Filled.MedicalServices
    "cart" -> Icons.Filled.LocalGroceryStore
    else -> Icons.Filled.Park
}

@StringRes
private fun RoadChip.label(): Int = when (this) {
    RoadChip.DIESEL -> R.string.road_chip_diesel
    RoadChip.FEE -> R.string.road_chip_fee
    RoadChip.TOILETS -> R.string.road_chip_toilets
    RoadChip.WATER -> R.string.road_chip_water
    RoadChip.SHOWERS -> R.string.road_chip_showers
    RoadChip.OVERNIGHT -> R.string.road_chip_overnight
}

/** The category's colour, the map's, so a colour means the same thing on the map, in the list and on the sheet. */
private fun RoadCategory.tint(): Color = Color(RoadColors.argb(code))

/**
 * "Near me / Along my route" and the category chips above the Road list. [alongAvailable] is false when no route is
 * being planned: the first group is then left out, since there is nothing to be along. A category chip toggles that
 * category in the list only (the map's own choice is the map options' chooser).
 */
@Composable
internal fun RoadFilters(
    along: Boolean,
    alongAvailable: Boolean,
    onAlong: (Boolean) -> Unit,
    filter: RoadFilter,
    onFilter: (RoadFilter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alongAvailable) {
            FilterChip(selected = !along, onClick = { onAlong(false) }, label = { Text(stringResource(R.string.road_near_me)) })
            FilterChip(selected = along, onClick = { onAlong(true) }, label = { Text(stringResource(R.string.road_along_route)) })
        }
        for (category in RoadCategory.entries) {
            FilterChip(
                selected = filter.matches(category),
                onClick = { onFilter(filter.toggle(category)) },
                label = { Text(category.displayName) },
                leadingIcon = { Icon(category.icon(), contentDescription = null, tint = category.tint()) },
            )
        }
    }
}

/** The small chips under a row's name: diesel, fee, toilets, water, showers, overnight, each only when the pack says yes. */
@Composable
private fun RoadChips(spot: RoadSpot) {
    val chips = remember(spot) { spot.extra.chips() }
    if (chips.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (chip in chips) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    stringResource(chip.label()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** What a row says under the name: the kind of place and, when the pack has hours, whether it is open now. */
@Composable
private fun RoadSupporting(spot: RoadSpot, now: LocalDateTime, extraLine: String?) {
    val hours = rememberOpeningHours(spot.extra.hours)
    val summary = remember(hours, now) { hours?.summary(now) }
    val hoursLine = summary?.let { hoursText(it) }
    val line = listOfNotNull(spot.category.displayName, hoursLine, extraLine).joinToString(" · ")
    Column {
        Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis)
        RoadChips(spot)
    }
}

/** One nearest-spot row: category icon, name, kind and hours, the typed extras as chips, the distance at the end. */
@Composable
internal fun RoadRow(result: RoadResult, distanceMeters: Double?, now: LocalDateTime, onClick: () -> Unit) {
    val spot = result.spot
    ListItem(
        headlineContent = { Text(spot.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { RoadSupporting(spot, now, null) },
        leadingContent = { Icon(spot.category.icon(), contentDescription = null, tint = spot.category.tint()) },
        trailingContent = distanceMeters?.let { { Text(Units.distance(it), style = MaterialTheme.typography.labelMedium) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * One "along my route" row: the same as [RoadRow], with how far ahead on the route the spot is at the end and how far
 * it is off the line under the name. A tap adds it as a stop.
 */
@Composable
internal fun RoadAlongRow(result: RoadAlongResult, now: LocalDateTime, onClick: () -> Unit) {
    val spot = result.spot
    val off = stringResource(R.string.road_off_route, Units.distance(result.offsetMeters))
    ListItem(
        headlineContent = { Text(spot.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { RoadSupporting(spot, now, off) },
        leadingContent = { Icon(spot.category.icon(), contentDescription = null, tint = spot.category.tint()) },
        trailingContent = {
            Text(stringResource(R.string.road_ahead, Units.distance(result.aheadMeters)), style = MaterialTheme.typography.labelMedium)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * The sheet's Road rows, for [PlaceCard]'s `extra` slot: the other kinds the place is filed under (a truck stop's
 * pumps), the operator, what the pack says is yes or no (fee, toilets, water, showers, overnight, diesel), and where the
 * data is from. The hours are the card's own line. A fact the pack doesn't carry is left out, never shown as "no".
 * [extractDate] is the pack's (YYYY-MM-DD), null when it doesn't say.
 */
@Composable
fun RoadDetails(spot: RoadSpot, extractDate: String?, modifier: Modifier = Modifier) {
    val extra = spot.extra
    val yes = stringResource(R.string.road_yes)
    val no = stringResource(R.string.road_no)
    val known = listOf(
        R.string.road_fact_fee to extra.fee,
        R.string.road_fact_toilets to extra.toilets,
        R.string.road_fact_water to extra.water,
        R.string.road_fact_showers to extra.shower,
        R.string.road_fact_overnight to extra.overnight,
    )
    val facts = known.mapNotNull { (label, value) ->
        when (value) {
            RoadFlag.YES -> stringResource(R.string.road_fact, stringResource(label), yes)
            RoadFlag.NO -> stringResource(R.string.road_fact, stringResource(label), no)
            RoadFlag.UNKNOWN -> null
        }
    } + listOfNotNull(if (extra.diesel) stringResource(R.string.road_fact, stringResource(R.string.road_fact_diesel), yes) else null)
    Column(modifier.padding(top = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (spot.alsoCategories.isNotEmpty()) {
            Text(
                stringResource(R.string.road_also, spot.alsoCategories.joinToString(", ") { it.displayName }),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        extra.operator?.let { Text(stringResource(R.string.road_operator, it), style = MaterialTheme.typography.bodyMedium) }
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (extractDate != null) stringResource(R.string.road_data_from, extractDate) else stringResource(R.string.road_data_from_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
