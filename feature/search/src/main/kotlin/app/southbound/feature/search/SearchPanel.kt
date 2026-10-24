package app.southbound.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RvHookup
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.map.Units
import app.southbound.core.road.RoadAlongResult
import app.southbound.core.road.RoadFilter
import app.southbound.core.road.RoadPack
import app.southbound.core.road.RoadResult
import app.southbound.core.road.RoadSpot
import app.southbound.core.search.LatLon
import app.southbound.core.search.Place
import app.southbound.core.search.PlaceCategory
import app.southbound.core.search.PlaceKind
import app.southbound.core.search.PlaceSearch
import app.southbound.core.search.SearchQuery
import app.southbound.core.search.SearchRanking
import app.southbound.core.search.SearchResult
import app.southbound.core.wifi.WifiPack
import app.southbound.core.wifi.WifiResult
import app.southbound.core.wifi.WifiResults
import app.southbound.core.wifi.WifiSpot
import app.southbound.core.wifi.WifiTierFilter
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A named group of places shown while the search box is empty: a saved list, or recents. */
data class PlaceGroup(val title: String, val places: List<Place>, val recents: Boolean = false)

/** Phase 6: the two one-tap places above the saved lists. */
enum class ShortcutSlot { HOME, WORK }

/**
 * Full-screen search: type, see results as you go, pick one. While the box is
 * empty, the user's saved lists and recent places are offered instead, and a
 * row of chips (Gas, Food, Hotels, ...) lists those places nearby, nearest first.
 * Both look around [near], or around [area] once the map has been moved.
 *
 * With [fieldAtBottom] the field sits on the keyboard and everything grows up from it, nearest first: the best match
 * (or Home, Work and the latest recent) right above the field, within the thumb's reach (docs/bottom-search.md).
 * Otherwise the field is at the top with the lists running down from it.
 *
 * Every lookup runs against the search pack on the phone; nothing typed here
 * leaves the device. [index] is null when no search pack is installed.
 */
@Composable
fun SearchPanel(
    index: PlaceSearch?,
    /** The user's position: results show their distance from it. */
    near: LatLon?,
    onPick: (SearchResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    groups: List<PlaceGroup> = emptyList(),
    onClearRecents: (() -> Unit)? = null,
    // Shown under the box while it is empty, e.g. when the saved lists could not be read.
    unreadableNote: String? = null,
    // Phase 6: Home and Work chips. Null [onOpenShortcut] hides them (e.g. while picking a stop).
    home: Place? = null,
    work: Place? = null,
    onOpenShortcut: ((Place) -> Unit)? = null,
    onSetShortcut: ((ShortcutSlot, Place) -> Unit)? = null,
    /**
     * The current weather at a place (the icon and the temperature), drawn at the end of its row above the
     * distance, for a weather search ("weather albany") on towns. Null shows no weather (the weather toggle off).
     */
    weatherFor: (@Composable (Place) -> Unit)? = null,
    /** The field at the bottom, on the keyboard (Settings > Driving > Search bar at the bottom). */
    fieldAtBottom: Boolean = false,
    /**
     * Where to look when it isn't around [near]: the middle of the map once it has been moved off the user. Results
     * still show their distance from [near].
     */
    area: LatLon? = null,
    /**
     * The Wi-Fi pack, when one is installed: adds the Wi-Fi chip, which lists the nearest free Wi-Fi from the same
     * place the other chips look. Null hides the chip.
     */
    wifi: WifiPack? = null,
    /** A Wi-Fi row was picked: its spot (with its tier, for the sheet). */
    onPickWifi: ((WifiSpot) -> Unit)? = null,
    /** The user's own "worked" / "didn't work" marks on Wi-Fi spots, by OSM reference (they override the label and sort the list). */
    wifiMarks: Map<String, WifiMarkInfo> = emptyMap(),
    /**
     * The Road pack, when one is installed: adds the Road chip, which lists the nearest campsites, rest areas, fuel and
     * the rest from the same place the other chips look. Null hides the chip.
     */
    road: RoadPack? = null,
    /** A Road row was picked (a nearest one): its spot, for the sheet. */
    onPickRoad: ((RoadSpot) -> Unit)? = null,
    /**
     * The route being planned, its points in order, while the panel was opened to add a stop to it; null otherwise. With
     * it the Road list offers "Along my route": the spots beside the route, in the order they come, with the distance
     * ahead. The route is read on the phone and never leaves it.
     */
    routeLine: List<LatLon>? = null,
    /** An "along my route" row was picked: the spot, to become a stop of the route being planned. */
    onPickRoadStop: ((RoadSpot) -> Unit)? = null,
) {
    // Phase 6: the chip that was tapped while unset; the next pick sets it.
    var settingSlot by remember { mutableStateOf<ShortcutSlot?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // A category chip's places ("Gas", "Hotels"), its name in the box until the box is edited.
    var browsing by rememberSaveable { mutableStateOf<PlaceCategory?>(null) }
    // The Wi-Fi chip's list, and its two filters.
    var wifiMode by rememberSaveable { mutableStateOf(false) }
    var wifiTier by rememberSaveable { mutableStateOf(WifiTierFilter.ALL) }
    var wifiOpenNow by rememberSaveable { mutableStateOf(false) }
    var wifiHideDidntWork by rememberSaveable { mutableStateOf(false) }
    // Null while looking, then the rows (maybe none); an error shows instead of "None".
    var wifiResults by remember { mutableStateOf<List<WifiResult>?>(null) }
    var wifiFailed by remember { mutableStateOf(false) }
    // The Road chip's list, near the user or along the route being planned, and its own category choice (kept through a
    // rotation as the line RoadFilter.encode writes; it is view state, never stored).
    var roadMode by rememberSaveable { mutableStateOf(false) }
    var roadAlong by rememberSaveable { mutableStateOf(false) }
    var roadFilterLine by rememberSaveable { mutableStateOf(RoadFilter.DEFAULT.encode()) }
    val roadFilter = remember(roadFilterLine) { RoadFilter.decode(roadFilterLine) }
    // Null while looking, then the rows (maybe none); an error shows instead of "None".
    var roadResults by remember { mutableStateOf<List<RoadResult>?>(null) }
    var roadAlongResults by remember { mutableStateOf<List<RoadAlongResult>?>(null) }
    var roadFailed by remember { mutableStateOf(false) }
    val roadAlongNow = roadAlong && routeLine != null
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    // The query [results] answer: the Search key only ever opens a result for what is typed now.
    var resultsFor by remember { mutableStateOf<String?>(null) }
    // The Search key was pressed before the results for what is typed now were in: open the top one when they are.
    var pickWhenReady by remember { mutableStateOf(false) }
    // Shown instead of "No matches" when the lookup itself failed, so a broken pack is visible.
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    // One minute-tick for every row's Open/Closed label.
    val now = rememberNow()
    val keyboard = LocalSoftwareKeyboardController.current
    val groupsState = rememberLazyListState()
    val resultsState = rememberLazyListState()

    LaunchedEffect(Unit) { focus.requestFocus() }
    // Again once there is a position: results looked up before the first fix carry no distances and rank as if far.
    // Again when the map moves to somewhere else to look (beside the search on a wide screen).
    LaunchedEffect(query, browsing, index, near != null, area, wifiMode, roadMode) {
        val asked = query
        val category = browsing
        val center = area ?: near
        // The Wi-Fi chip has its own list below; its name in the box is not a search.
        if (index == null || asked.isBlank() || wifiMode || roadMode || (category != null && center == null)) {
            results = emptyList()
            error = null
            resultsFor = asked
            return@LaunchedEffect
        }
        if (category == null) delay(TYPING_DEBOUNCE_MS)
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val found = if (category != null) index.browse(category, center!!) else index.search(asked, center)
                // Looked for around the map: the distances are still the user's.
                if (area == null) {
                    found
                } else {
                    found.map { r -> r.copy(distanceMeters = near?.let { SearchRanking.distanceMeters(it, r.place.lat, r.place.lon) }) }
                }
            }
        }
        results = outcome.getOrDefault(emptyList())
        error = outcome.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
        resultsFor = asked
    }
    // The nearest free Wi-Fi: from the same place the category chips look, again when a filter changes (and, with
    // Open now, as the minute turns).
    LaunchedEffect(wifiMode, wifiTier, wifiOpenNow, wifi, near != null, area, if (wifiOpenNow) now else null) {
        val center = area ?: near
        if (!wifiMode || wifi == null || center == null) {
            wifiResults = null
            wifiFailed = false
            return@LaunchedEffect
        }
        val outcome = withContext(Dispatchers.IO) {
            runCatching { wifi.nearest(center, WIFI_RADIUS_KM, wifiTier.tiers, wifiOpenNow, now, WIFI_LIMIT) }
        }
        wifiFailed = outcome.isFailure
        wifiResults = outcome.getOrDefault(emptyList())
    }
    // The Road list: the nearest spots from the same place the category chips look, or the spots beside the route being
    // planned. Again when the kinds change; a search still running when they do is stopped between route pieces.
    LaunchedEffect(roadMode, roadFilterLine, road, near != null, area, roadAlongNow, routeLine) {
        val center = area ?: near
        val line = routeLine
        roadResults = null
        roadAlongResults = null
        roadFailed = false
        if (!roadMode || road == null || roadFilter.showsNothing) return@LaunchedEffect
        if (roadAlongNow && line != null) {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { road.alongRoute(line, ROAD_CORRIDOR_KM, roadFilter.categories, ROAD_ALONG_LIMIT, isActive = { this@withContext.isActive }) }
            }
            roadFailed = outcome.isFailure
            roadAlongResults = outcome.getOrDefault(emptyList())
        } else if (center != null) {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { road.nearest(center, ROAD_RADIUS_KM, roadFilter.categories, ROAD_LIMIT) }
            }
            roadFailed = outcome.isFailure
            roadResults = outcome.getOrDefault(emptyList())
        }
    }
    // With the field at the bottom, dragging a list puts the keyboard away so more of it shows.
    LaunchedEffect(fieldAtBottom) {
        if (!fieldAtBottom) return@LaunchedEffect
        for (state in listOf(groupsState, resultsState)) {
            launch { state.interactionSource.interactions.collect { if (it is DragInteraction.Start) keyboard?.hide() } }
        }
    }

    // Phase 6: a pick while a Home or Work chip is waiting sets that shortcut first.
    val pick: (SearchResult) -> Unit = { result ->
        settingSlot?.let { slot ->
            onSetShortcut?.invoke(slot, result.place)
            settingSlot = null
        }
        onPick(result)
    }

    // The Search key: the top result for what is typed now, once it is in.
    LaunchedEffect(pickWhenReady, resultsFor) {
        if (pickWhenReady && resultsFor == query) {
            pickWhenReady = false
            results.firstOrNull()?.let(pick)
        }
    }
    fun searchKey() {
        keyboard?.hide()
        if (resultsFor == query) {
            results.firstOrNull()?.let(pick)
        } else {
            pickWhenReady = true
        }
    }

    val field: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.search_back))
            }
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    // Typing over a chip's name searches for what is typed.
                    browsing = null
                    wifiMode = false
                    roadMode = false
                    pickWhenReady = false
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchKey() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            browsing = null
                            wifiMode = false
                            roadMode = false
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
    }
    // Phase 6: Home and Work chips while the box is empty.
    val chips: @Composable () -> Unit = {
        if (query.isBlank() && onOpenShortcut != null) {
            ShortcutChips(
                home = home,
                work = work,
                pending = settingSlot,
                onTap = { slot, place ->
                    if (place != null) {
                        keyboard?.hide()
                        onOpenShortcut(place)
                    } else {
                        settingSlot = slot
                        focus.requestFocus()
                    }
                },
            )
        }
    }
    // Places by kind without typing: around the user, or around the map once it has been moved off them.
    val categories: @Composable () -> Unit = {
        if (query.isBlank() && index != null) {
            CategoryChips(
                onTap = { category, label ->
                    keyboard?.hide()
                    query = label
                    browsing = category
                    wifiMode = false
                    roadMode = false
                    pickWhenReady = false
                },
                onWifi = if (wifi != null) {
                    { label ->
                        keyboard?.hide()
                        query = label
                        browsing = null
                        wifiMode = true
                        roadMode = false
                        pickWhenReady = false
                    }
                } else {
                    null
                },
                onRoad = if (road != null) {
                    { label ->
                        keyboard?.hide()
                        query = label
                        browsing = null
                        wifiMode = false
                        roadMode = true
                        pickWhenReady = false
                    }
                } else {
                    null
                },
            )
        }
    }
    val note: @Composable () -> Unit = {
        if (query.isBlank() && unreadableNote != null) {
            Text(
                unreadableNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    Surface(modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            if (!fieldAtBottom) {
                field()
                HorizontalDivider()
                chips()
                categories()
                note()
            }
            // The lists and messages: running down from the field at the top, or growing up from it at the bottom.
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = if (fieldAtBottom) Alignment.BottomStart else Alignment.TopStart,
            ) {
                when {
                    query.isBlank() && groups.any { it.places.isNotEmpty() } -> LazyColumn(
                        Modifier.fillMaxWidth(),
                        state = groupsState,
                        reverseLayout = fieldAtBottom,
                    ) {
                        // At the bottom the recents come nearest the field, each group's title above its places.
                        val shown = groups.filter { it.places.isNotEmpty() }
                            .let { g -> if (fieldAtBottom) g.sortedByDescending { it.recents } else g }
                        for (group in shown) {
                            if (!fieldAtBottom) item(key = "title:${group.title}") { GroupTitle(group, onClearRecents) }
                            // Keyed by position: several places can share a point (Overture puts a building's
                            // businesses on one coordinate, and pins have no osmRef), and a repeated key crashes.
                            itemsIndexed(group.places, key = { i, _ -> "${group.title}:$i" }) { _, place ->
                                ResultRow(
                                    SearchResult(place, near?.let { SearchRanking.distanceMeters(it, place.lat, place.lon) }),
                                    now = now,
                                    icon = if (group.recents) Icons.Filled.History else Icons.Filled.Bookmark,
                                    onClick = {
                                        keyboard?.hide()
                                        pick(SearchResult(place, null))
                                    },
                                )
                            }
                            if (fieldAtBottom) item(key = "title:${group.title}") { GroupTitle(group, onClearRecents) }
                        }
                    }
                    wifiMode && wifi != null -> Column(Modifier.fillMaxSize()) {
                        WifiFilters(wifiTier, { wifiTier = it }, wifiOpenNow, { wifiOpenNow = it }, wifiHideDidntWork, { wifiHideDidntWork = it })
                        val found = wifiResults
                        // The spots marked "didn't work" go last (or out): the marks change under an open list, so this is
                        // done here and not when the pack is read.
                        val rows = remember(found, wifiMarks, wifiHideDidntWork) {
                            found?.let {
                                WifiResults.arrange(it, wifiMarks.filterValues { m -> !m.worked }.keys, hide = wifiHideDidntWork)
                            }
                        }
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = if (fieldAtBottom) Alignment.BottomStart else Alignment.TopStart,
                        ) {
                            when {
                                (area ?: near) == null -> Message(stringResource(R.string.wifi_no_place))
                                wifiFailed -> Message(stringResource(R.string.wifi_failed))
                                rows == null -> Unit
                                rows.isEmpty() -> Message(
                                    stringResource(
                                        when {
                                            !found.isNullOrEmpty() -> R.string.wifi_none_hidden
                                            wifiOpenNow -> R.string.wifi_none_open_now
                                            else -> R.string.wifi_none
                                        },
                                    ),
                                )
                                else -> LazyColumn(Modifier.fillMaxWidth(), reverseLayout = fieldAtBottom) {
                                    items(rows, key = { it.spot.id }) { row ->
                                        // Looked for around the map: the distance is still the user's.
                                        val distance = if (area == null) {
                                            row.distanceMeters
                                        } else {
                                            near?.let { SearchRanking.distanceMeters(it, row.spot.lat, row.spot.lon) }
                                        }
                                        WifiRow(row, distance, wifiMarks[row.spot.osmRef], now, onClick = {
                                            keyboard?.hide()
                                            onPickWifi?.invoke(row.spot)
                                        })
                                    }
                                }
                            }
                        }
                    }
                    roadMode && road != null -> Column(Modifier.fillMaxSize()) {
                        RoadFilters(
                            along = roadAlongNow,
                            alongAvailable = routeLine != null,
                            onAlong = { roadAlong = it },
                            filter = roadFilter,
                            onFilter = { roadFilterLine = it.encode() },
                        )
                        Box(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = if (fieldAtBottom) Alignment.BottomStart else Alignment.TopStart,
                        ) {
                            val nearRows = roadResults
                            val alongRows = roadAlongResults
                            when {
                                roadFilter.showsNothing -> Message(stringResource(R.string.road_no_kinds))
                                !roadAlongNow && (area ?: near) == null -> Message(stringResource(R.string.road_no_place))
                                roadFailed -> Message(stringResource(R.string.road_failed))
                                roadAlongNow -> when {
                                    alongRows == null -> Unit
                                    alongRows.isEmpty() -> Message(stringResource(R.string.road_none_along))
                                    else -> LazyColumn(Modifier.fillMaxWidth(), reverseLayout = fieldAtBottom) {
                                        items(alongRows, key = { it.spot.id }) { row ->
                                            RoadAlongRow(row, now, onClick = {
                                                keyboard?.hide()
                                                onPickRoadStop?.invoke(row.spot)
                                            })
                                        }
                                    }
                                }
                                nearRows == null -> Unit
                                nearRows.isEmpty() -> Message(stringResource(R.string.road_none))
                                else -> LazyColumn(Modifier.fillMaxWidth(), reverseLayout = fieldAtBottom) {
                                    items(nearRows, key = { it.spot.id }) { row ->
                                        // Looked for around the map: the distance is still the user's.
                                        val distance = if (area == null) {
                                            row.distanceMeters
                                        } else {
                                            near?.let { SearchRanking.distanceMeters(it, row.spot.lat, row.spot.lon) }
                                        }
                                        RoadRow(row, distance, now, onClick = {
                                            keyboard?.hide()
                                            onPickRoad?.invoke(row.spot)
                                        })
                                    }
                                }
                            }
                        }
                    }
                    index == null -> Message(stringResource(R.string.search_no_pack))
                    error != null -> Message(stringResource(R.string.search_failed, error!!))
                    query.isNotBlank() && results.isEmpty() && browsing == null -> Message(stringResource(R.string.search_no_results))
                    query.isNotBlank() && results.isEmpty() && (area ?: near) == null ->
                        Message(stringResource(R.string.search_category_no_place))
                    // Not while a chip's places are still being looked up: "Food" in a city takes a moment.
                    query.isNotBlank() && results.isEmpty() && resultsFor == query -> Message(stringResource(R.string.search_category_none))
                    else -> {
                        // At the bottom the list starts at the field: the best match stays there as the results change.
                        if (fieldAtBottom) LaunchedEffect(results) { resultsState.scrollToItem(0) }
                        LazyColumn(Modifier.fillMaxWidth(), state = resultsState, reverseLayout = fieldAtBottom) {
                            // A weather search shows each town's weather in its row.
                            val asksWeather = weatherFor != null && SearchQuery.parse(query).asksWeather
                            items(results, key = { it.place.id }) { result ->
                                val weather = weatherFor?.takeIf { asksWeather && result.place.kind == PlaceKind.LOCALITY }
                                ResultRow(result, now = now, onClick = {
                                    keyboard?.hide()
                                    pick(result)
                                }, weather = weather?.let { w -> { w(result.place) } })
                            }
                        }
                    }
                }
            }
            if (fieldAtBottom) {
                note()
                categories()
                chips()
                HorizontalDivider()
                field()
            }
        }
    }
}

/** A saved list's or the recents' title, with Clear for the recents. */
@Composable
private fun GroupTitle(group: PlaceGroup, onClearRecents: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (group.recents && onClearRecents != null) {
            TextButton(onClick = onClearRecents) { Text(stringResource(R.string.search_clear_recents)) }
        }
    }
}

// Phase 6: Home and Work chips.
@Composable
private fun ShortcutChips(home: Place?, work: Place?, pending: ShortcutSlot?, onTap: (ShortcutSlot, Place?) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((slot, place) in listOf(ShortcutSlot.HOME to home, ShortcutSlot.WORK to work)) {
                AssistChip(
                    onClick = { onTap(slot, place) },
                    label = { Text(stringResource(if (slot == ShortcutSlot.HOME) R.string.search_home else R.string.search_work)) },
                    leadingIcon = {
                        Icon(
                            if (slot == ShortcutSlot.HOME) Icons.Filled.Home else Icons.Filled.Work,
                            contentDescription = null,
                            Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        if (pending != null) {
            Text(
                stringResource(if (pending == ShortcutSlot.HOME) R.string.search_set_home_hint else R.string.search_set_work_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Gas, Hotels, Food, Coffee and the rest, one tap each, in a row that scrolls sideways. */
@Composable
private fun CategoryChips(onTap: (PlaceCategory, String) -> Unit, onWifi: ((String) -> Unit)?, onRoad: ((String) -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (category in PlaceCategory.entries) {
            val label = stringResource(category.label())
            AssistChip(
                onClick = { onTap(category, label) },
                label = { Text(label) },
                leadingIcon = { Icon(category.icon(), contentDescription = null, Modifier.size(18.dp)) },
            )
        }
        // After the others, and only with the Wi-Fi pack installed.
        if (onWifi != null) {
            val label = stringResource(R.string.wifi_chip)
            AssistChip(
                onClick = { onWifi(label) },
                label = { Text(label) },
                leadingIcon = { Icon(Icons.Filled.Wifi, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
        // After the Wi-Fi chip, and only with the Road pack installed.
        if (onRoad != null) {
            val label = stringResource(R.string.road_chip)
            AssistChip(
                onClick = { onRoad(label) },
                label = { Text(label) },
                leadingIcon = { Icon(Icons.Filled.RvHookup, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
    }
}

private fun PlaceCategory.label(): Int = when (this) {
    PlaceCategory.GAS -> R.string.search_category_gas
    PlaceCategory.FOOD -> R.string.search_category_food
    PlaceCategory.COFFEE -> R.string.search_category_coffee
    PlaceCategory.HOTELS -> R.string.search_category_hotels
    PlaceCategory.GROCERIES -> R.string.search_category_groceries
    PlaceCategory.PHARMACY -> R.string.search_category_pharmacy
    PlaceCategory.PARKING -> R.string.search_category_parking
    PlaceCategory.EV_CHARGING -> R.string.search_category_ev_charging
}

private fun PlaceCategory.icon(): ImageVector = when (this) {
    PlaceCategory.GAS -> Icons.Filled.LocalGasStation
    PlaceCategory.FOOD -> Icons.Filled.Restaurant
    PlaceCategory.COFFEE -> Icons.Filled.LocalCafe
    PlaceCategory.HOTELS -> Icons.Filled.Hotel
    PlaceCategory.GROCERIES -> Icons.Filled.LocalGroceryStore
    PlaceCategory.PHARMACY -> Icons.Filled.LocalPharmacy
    PlaceCategory.PARKING -> Icons.Filled.LocalParking
    PlaceCategory.EV_CHARGING -> Icons.Filled.EvStation
}

@Composable
private fun ResultRow(
    result: SearchResult,
    now: LocalDateTime,
    onClick: () -> Unit,
    icon: ImageVector = result.place.kind.icon(),
    /** Drawn above the distance at the end of the row: a town's weather in a weather search. */
    weather: (@Composable () -> Unit)? = null,
) {
    val place = result.place
    val approximate = if (place.approximate) stringResource(R.string.search_approximate) else null
    val openLabel = rowOpenLabel(place.openingHours, now)
    val supporting = listOfNotNull(approximate, place.secondaryLine(), openLabel).joinToString(" · ").ifEmpty { null }
    ListItem(
        headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = if (weather != null) {
            {
                Column(horizontalAlignment = Alignment.End) {
                    weather()
                    result.distanceMeters?.let { Text(Units.distance(it), style = MaterialTheme.typography.labelSmall) }
                }
            }
        } else {
            result.distanceMeters?.let { { Text(Units.distance(it), style = MaterialTheme.typography.labelMedium) } }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

/**
 * "Cafe · 224 West 20th Street, New York 10011"; for an address, the rest of
 * its full address after the name: "Nyack, NY 10960" under "123 Main Street".
 */
fun Place.secondaryLine(): String? = when (kind) {
    PlaceKind.ADDRESS -> address?.removePrefix("$name, ")?.takeIf { it.isNotBlank() && it != name }
    else -> listOfNotNull(categoryLabel, address).joinToString(" · ").ifEmpty { null }
}

fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.LOCALITY -> Icons.Filled.LocationCity
    PlaceKind.PLACE -> Icons.Filled.Place
    PlaceKind.STREET -> Icons.Filled.AddRoad
    PlaceKind.ADDRESS -> Icons.Filled.Home
}

private const val TYPING_DEBOUNCE_MS = 120L

/** How far the Wi-Fi chip looks, as the other chips do: 30 miles. */
private const val WIFI_RADIUS_KM = PlaceCategory.RADIUS_KM
private const val WIFI_LIMIT = PlaceCategory.LIMIT

/** How far the Road chip looks, as the other chips do: 30 miles. */
private const val ROAD_RADIUS_KM = PlaceCategory.RADIUS_KM
private const val ROAD_LIMIT = PlaceCategory.LIMIT

/** How wide "along my route" looks to either side of the route: 5 miles. */
private const val ROAD_CORRIDOR_KM = 8.0

/** How many spots "along my route" lists: the first ones the driver meets. */
private const val ROAD_ALONG_LIMIT = 40
