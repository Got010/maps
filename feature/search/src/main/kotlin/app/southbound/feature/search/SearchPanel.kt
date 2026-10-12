package app.southbound.feature.search

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Place
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
import app.southbound.core.search.LatLon
import app.southbound.core.search.Place
import app.southbound.core.search.PlaceKind
import app.southbound.core.search.PlaceSearch
import app.southbound.core.search.SearchQuery
import app.southbound.core.search.SearchRanking
import app.southbound.core.search.SearchResult
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A named group of places shown while the search box is empty: a saved list, or recents. */
data class PlaceGroup(val title: String, val places: List<Place>, val recents: Boolean = false)

/** Phase 6: the two one-tap places above the saved lists. */
enum class ShortcutSlot { HOME, WORK }

/**
 * Full-screen search: type, see results as you go, pick one. While the box is
 * empty, the user's saved lists and recent places are offered instead.
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
) {
    // Phase 6: the chip that was tapped while unset; the next pick sets it.
    var settingSlot by remember { mutableStateOf<ShortcutSlot?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
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
    LaunchedEffect(query, index, near != null) {
        val asked = query
        if (index == null || asked.isBlank()) {
            results = emptyList()
            error = null
            resultsFor = asked
            return@LaunchedEffect
        }
        delay(TYPING_DEBOUNCE_MS)
        val outcome = withContext(Dispatchers.IO) { runCatching { index.search(asked, near) } }
        results = outcome.getOrDefault(emptyList())
        error = outcome.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
        resultsFor = asked
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
                    pickWhenReady = false
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchKey() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
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
                    index == null -> Message(stringResource(R.string.search_no_pack))
                    error != null -> Message(stringResource(R.string.search_failed, error!!))
                    query.isNotBlank() && results.isEmpty() -> Message(stringResource(R.string.search_no_results))
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
