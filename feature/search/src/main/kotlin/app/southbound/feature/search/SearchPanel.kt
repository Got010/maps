package app.southbound.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.southbound.core.search.SearchIndex
import app.southbound.core.search.SearchRanking
import app.southbound.core.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A named group of places shown while the search box is empty: a saved list, or recents. */
data class PlaceGroup(val title: String, val places: List<Place>, val recents: Boolean = false)

/**
 * Full-screen search: type, see results as you go, pick one. While the box is
 * empty, the user's saved lists and recent places are offered instead.
 *
 * Every lookup runs against the search pack on the phone; nothing typed here
 * leaves the device. [index] is null when no search pack is installed.
 */
@Composable
fun SearchPanel(
    index: SearchIndex?,
    near: LatLon?,
    onPick: (SearchResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    groups: List<PlaceGroup> = emptyList(),
    onClearRecents: (() -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    // Shown instead of "No matches" when the lookup itself failed, so a broken pack is visible.
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, index) {
        if (index == null || query.isBlank()) {
            results = emptyList()
            error = null
            return@LaunchedEffect
        }
        delay(TYPING_DEBOUNCE_MS)
        val outcome = withContext(Dispatchers.IO) { runCatching { index.search(query, near) } }
        results = outcome.getOrDefault(emptyList())
        error = outcome.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
    }

    Surface(modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.search_back))
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        results.firstOrNull()?.let(onPick)
                    }),
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
            HorizontalDivider()
            when {
                query.isBlank() && groups.any { it.places.isNotEmpty() } -> LazyColumn(Modifier.fillMaxWidth()) {
                    for (group in groups.filter { it.places.isNotEmpty() }) {
                        item(key = "title:${group.title}") {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                if (group.recents && onClearRecents != null) {
                                    TextButton(onClick = onClearRecents) { Text(stringResource(R.string.search_clear_recents)) }
                                }
                            }
                        }
                        items(group.places, key = { "${group.title}:${it.osmRef ?: "${it.lat},${it.lon}"}" }) { place ->
                            ResultRow(
                                SearchResult(place, near?.let { SearchRanking.distanceMeters(it, place.lat, place.lon) }),
                                icon = if (group.recents) Icons.Filled.History else Icons.Filled.Bookmark,
                                onClick = {
                                    keyboard?.hide()
                                    onPick(SearchResult(place, null))
                                },
                            )
                        }
                    }
                }
                index == null -> Message(stringResource(R.string.search_no_pack))
                error != null -> Message(stringResource(R.string.search_failed, error!!))
                query.isNotBlank() && results.isEmpty() -> Message(stringResource(R.string.search_no_results))
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(results, key = { it.place.id }) { result ->
                        ResultRow(result, onClick = {
                            keyboard?.hide()
                            onPick(result)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: SearchResult, onClick: () -> Unit, icon: ImageVector = result.place.kind.icon()) {
    val place = result.place
    ListItem(
        headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = place.secondaryLine()?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = result.distanceMeters?.let { { Text(Units.distance(it), style = MaterialTheme.typography.labelMedium) } },
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

/** "Cafe · 224 West 20th Street, New York 10011" */
fun Place.secondaryLine(): String? =
    listOfNotNull(categoryLabel.takeIf { kind != PlaceKind.ADDRESS }, address)
        .joinToString(" · ")
        .ifEmpty { null }

fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.LOCALITY -> Icons.Filled.LocationCity
    PlaceKind.PLACE -> Icons.Filled.Place
    PlaceKind.STREET -> Icons.Filled.AddRoad
    PlaceKind.ADDRESS -> Icons.Filled.Home
}

private const val TYPING_DEBOUNCE_MS = 120L
