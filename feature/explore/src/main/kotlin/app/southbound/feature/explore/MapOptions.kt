package app.southbound.feature.explore

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.RvHookup
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Cloud as CloudOutlined
import androidx.compose.material.icons.outlined.Layers as LayersOutlined
import androidx.compose.material.icons.outlined.RvHookup as RvHookupOutlined
import androidx.compose.material.icons.outlined.Traffic as TrafficOutlined
import androidx.compose.material.icons.outlined.Wifi as WifiOutlined
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.southbound.core.map.WifiColors

/**
 * The map's one floating button, "Map options", and the list it opens above itself (speed-dial style): Re-center,
 * Traffic, Weather, Free Wi-Fi and Road, each shown under the condition the separate button it replaced had (Road: the
 * installed pack), and "This state" (the card for the state the phone is in) with state-line cards on. Re-center and
 * "This state" act and close the list; a layer's row toggles it and keeps the list open so several can be set. A long
 * press on the Free Wi-Fi row opens the types chooser through [onChooseWifi], and one on the Road row the kinds chooser
 * through [onChooseRoad]. "Trip log", the last row, opens the leg log ([onTripLog]) and closes the list. New controls for
 * the map join this list.
 *
 * Open or closed is view state ([rememberSaveable]): kept through a rotation, gone with the screen. A tap outside
 * the list (a scrim that has no accessibility node, so it never traps TalkBack) or the back gesture closes it.
 * Nothing here is stored or exported.
 */
@Composable
internal fun BoxScope.MapOptions(
    state: MapOptionsState,
    onRecenter: () -> Unit,
    onToggleTraffic: () -> Unit,
    onToggleWeather: () -> Unit,
    onToggleWifi: () -> Unit,
    onChooseWifi: () -> Unit,
    onToggleRoad: () -> Unit,
    onChooseRoad: () -> Unit,
    onThisState: () -> Unit,
    bottomInset: Dp,
    onTripLog: () -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Every row can disappear (the fix is lost, the toggles are off): then there is nothing to open, and the list must
    // not open by itself when rows come back.
    val empty = state.rows.isEmpty()
    LaunchedEffect(empty) { if (empty) open = false }
    if (empty) return
    val shown = open

    BackHandler(enabled = shown) { open = false }
    if (shown) {
        // Over the rest of the screen, the pin cards and the traffic notes included (they are drawn after this
        // composable), and under the list.
        Box(Modifier.zIndex(1f).fillMaxSize().pointerInput(Unit) { detectTapGestures { open = false } })
    }
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .zIndex(2f)
            .padding(bottom = bottomInset)
            .safeDrawingPadding()
            .padding(16.dp)
            // The button first, then the rows it opens: TalkBack reads what a tap on the button just revealed next.
            .semantics { isTraversalGroup = true },
        horizontalAlignment = Alignment.End,
    ) {
        AnimatedVisibility(
            visible = shown,
            // Measured after the button, so a list taller than the screen (landscape) takes only the height that is
            // left and the button keeps its place at the bottom.
            modifier = Modifier.weight(1f, fill = false),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                Modifier
                    .padding(bottom = 4.dp)
                    // The scroll clips to its own bounds, which would cut the rows' shadows flat at the sides: it gets
                    // 8 dp of room on each side and bottom (the offset and the smaller gap above put the rows where
                    // they were), and scrolls when the rows are taller than the height that is left.
                    .offset(x = 8.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 1f
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                for (row in state.rows) {
                    when (row) {
                        MapOption.RECENTER -> RecenterRow(state.panned) {
                            onRecenter()
                            open = false
                        }
                        MapOption.TRAFFIC -> ToggleRow(
                            label = stringResource(R.string.explore_options_traffic),
                            on = state.trafficVisible,
                            icon = {
                                Icon(if (state.trafficVisible) Icons.Filled.Traffic else Icons.Outlined.TrafficOutlined, contentDescription = null)
                            },
                            actionLabel = stringResource(if (state.trafficVisible) R.string.explore_traffic_hide else R.string.explore_traffic_show),
                            onClick = onToggleTraffic,
                        )
                        MapOption.WEATHER -> ToggleRow(
                            label = stringResource(R.string.explore_options_weather),
                            on = state.weatherVisible,
                            icon = {
                                Icon(if (state.weatherVisible) Icons.Filled.Cloud else Icons.Outlined.CloudOutlined, contentDescription = null)
                            },
                            actionLabel = stringResource(if (state.weatherVisible) R.string.explore_weather_hide else R.string.explore_weather_show),
                            onClick = onToggleWeather,
                        )
                        MapOption.WIFI -> ToggleRow(
                            label = stringResource(R.string.explore_options_wifi),
                            on = state.wifiVisible,
                            icon = {
                                Icon(if (state.wifiVisible) Icons.Filled.Wifi else Icons.Outlined.WifiOutlined, contentDescription = null)
                            },
                            actionLabel = stringResource(if (state.wifiVisible) R.string.explore_wifi_hide else R.string.explore_wifi_show),
                            onClick = onToggleWifi,
                            filtered = state.wifiRowFiltered,
                            longClickLabel = stringResource(R.string.explore_wifi_choose),
                            onLongClick = {
                                // The chooser covers the screen; the list would only sit behind it.
                                open = false
                                onChooseWifi()
                            },
                        )
                        MapOption.ROAD -> ToggleRow(
                            label = stringResource(R.string.explore_options_road),
                            on = state.roadVisible,
                            icon = {
                                Icon(if (state.roadVisible) Icons.Filled.RvHookup else Icons.Outlined.RvHookupOutlined, contentDescription = null)
                            },
                            actionLabel = stringResource(if (state.roadVisible) R.string.explore_road_hide else R.string.explore_road_show),
                            onClick = onToggleRoad,
                            filtered = state.roadRowFiltered,
                            filteredDescription = stringResource(R.string.explore_road_filtered),
                            longClickLabel = stringResource(R.string.explore_road_choose),
                            onLongClick = {
                                open = false
                                onChooseRoad()
                            },
                        )
                        MapOption.THIS_STATE -> ThisStateRow {
                            onThisState()
                            open = false
                        }
                        MapOption.TRIP_LOG -> TripLogRow {
                            // The log is a sheet over everything; the list would only sit behind it.
                            open = false
                            onTripLog()
                        }
                    }
                }
            }
        }
        MapOptionsButton(open = shown, dot = state.dot, onClick = { open = !shown })
    }
}

/** The button itself: a layers icon, and a dot on its corner while a layer is showing. */
@Composable
private fun MapOptionsButton(open: Boolean, dot: MapOptionsDot, onClick: () -> Unit) {
    val container = if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val state = stringResource(if (open) R.string.explore_options_expanded else R.string.explore_options_collapsed) +
        if (dot != MapOptionsDot.NONE) ", " + stringResource(R.string.explore_options_layer_on) else ""
    Box(
        Modifier.semantics {
            isTraversalGroup = true
            traversalIndex = 0f
        },
    ) {
        FloatingActionButton(
            onClick = onClick,
            // On reads clearly in dynamic dark mode too, where primary and onSurfaceVariant look alike.
            containerColor = container,
            contentColor = if (open) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { stateDescription = state },
        ) {
            Icon(
                if (open) Icons.Filled.Layers else Icons.Outlined.LayersOutlined,
                contentDescription = stringResource(R.string.explore_options),
            )
        }
        if (dot != MapOptionsDot.NONE) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(DOT_SIZE)
                    .background(dotColor(dot), CircleShape)
                    .border(1.dp, container, CircleShape),
            )
        }
    }
}

@Composable
private fun dotColor(dot: MapOptionsDot): Color =
    if (dot == MapOptionsDot.WIFI_FILTERED) Color(WifiColors.customersArgb) else MaterialTheme.colorScheme.primary

/** "Re-center" while the map is away from the puck, "My location" while it follows. Acts, and the caller closes the list. */
@Composable
private fun RecenterRow(panned: Boolean, onClick: () -> Unit) {
    OptionRow(
        label = stringResource(if (panned) R.string.explore_recenter else R.string.explore_options_my_location),
        highlighted = panned,
        icon = { Icon(painterResource(R.drawable.ic_my_location), contentDescription = null) },
        trailing = null,
        stateText = null,
        onClick = onClick,
        actionLabel = stringResource(R.string.explore_my_location),
        role = Role.Button,
    )
}

/** "This state": opens the card for the state the phone is in. Acts, and the caller closes the list. */
@Composable
private fun ThisStateRow(onClick: () -> Unit) {
    OptionRow(
        label = stringResource(R.string.explore_options_this_state),
        highlighted = false,
        icon = { Icon(Icons.Filled.Flag, contentDescription = null) },
        trailing = null,
        stateText = null,
        onClick = onClick,
        actionLabel = stringResource(R.string.explore_options_this_state_action),
        role = Role.Button,
    )
}

/** "Trip log": opens the legs logged for mileage claims. Acts, and the caller closes the list. */
@Composable
private fun TripLogRow(onClick: () -> Unit) {
    OptionRow(
        label = stringResource(R.string.explore_options_trip_log),
        highlighted = false,
        icon = { Icon(Icons.Filled.Receipt, contentDescription = null) },
        trailing = null,
        stateText = null,
        onClick = onClick,
        actionLabel = stringResource(R.string.explore_options_trip_log_action),
        role = Role.Button,
    )
}

/** A layer: a tap switches it, and the row says On or Off. A long press (the Free Wi-Fi row's chooser) is optional. */
@Composable
private fun ToggleRow(
    label: String,
    on: Boolean,
    icon: @Composable () -> Unit,
    actionLabel: String,
    onClick: () -> Unit,
    filtered: Boolean = false,
    /** What a screen reader says while [filtered]; the Wi-Fi row's wording when not given. */
    filteredDescription: String? = null,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val onText = stringResource(R.string.explore_options_on)
    val offText = stringResource(R.string.explore_options_off)
    val filteredText = filteredDescription ?: stringResource(R.string.explore_wifi_filtered)
    OptionRow(
        label = label,
        highlighted = on,
        icon = {
            Box {
                icon()
                if (filtered) {
                    // Some types are hidden (Road: some added or hidden): the same gold as the main button's dot.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(DOT_SIZE)
                            .background(dotColor(MapOptionsDot.WIFI_FILTERED), CircleShape)
                            .border(1.dp, if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, CircleShape),
                    )
                }
            }
        },
        trailing = { Switch(checked = on, onCheckedChange = null) },
        stateText = (if (on) onText else offText) + if (filtered) ", $filteredText" else "",
        onClick = onClick,
        actionLabel = actionLabel,
        role = Role.Switch,
        longClickLabel = longClickLabel,
        onLongClick = onLongClick,
    )
}

/**
 * One pill: icon, label, an optional trailing control. The whole pill is one focus stop for TalkBack, reading its
 * label and state. A surface with a combined click, because a Material surface has no long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionRow(
    label: String,
    highlighted: Boolean,
    icon: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)?,
    stateText: String?,
    onClick: () -> Unit,
    actionLabel: String,
    role: Role,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier
                .clip(shape)
                .combinedClickable(
                    onClickLabel = actionLabel,
                    onClick = onClick,
                    onLongClickLabel = longClickLabel,
                    onLongClick = onLongClick?.let { longClick ->
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            longClick()
                        }
                    },
                    role = role,
                )
                .semantics { if (stateText != null) stateDescription = stateText }
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, end = if (trailing != null) 12.dp else 20.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
            if (trailing != null) {
                Spacer(Modifier.width(16.dp))
                // Pushes the switch to the pill's right edge when the pills are stretched to a common width.
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
    }
}

private val DOT_SIZE = 8.dp
