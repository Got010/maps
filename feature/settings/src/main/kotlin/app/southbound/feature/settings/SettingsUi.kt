package app.southbound.feature.settings

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The pieces every Settings tab is built from (docs/settings-tabs.md): rows in square-cornered cards (owner, October
 * 2026: "Keep cards rectangular"), groups that stay closed until tapped with a line saying what's inside, notes cut to
 * one line, choices as segmented buttons or a one-line menu, and the search's jump to a row.
 */

/** The setting a search result points at: its tab shows, its group opens, and its row is scrolled to and lit. */
internal class SettingsJump {
    var target by mutableStateOf<Int?>(null)
}

internal val LocalSettingsJump = staticCompositionLocalOf { SettingsJump() }

/** How long a jump waits for the tab's page and the group's rows to be laid out before scrolling to the row. */
private const val JUMP_SETTLE_MS = 350L

/** How long the row the search jumped to stays lit. */
private const val JUMP_LIT_MS = 1_600L

/** Rows sit on the card's colour, not on a surface of their own. */
@Composable
internal fun rowColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

/** A card of rows under an optional [title]: square corners, set off from the page by its colour. */
@Composable
internal fun SettingsCard(@StringRes title: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        title?.let { CardTitle(it) }
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun CardTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    )
}

/**
 * A group that stays closed until tapped, its [summary] saying what's set inside ("4 of 13 on", "System · Amy ·
 * chimes off"). A search result for one of its [titles] opens it. [inCard]: a group inside a [SettingsCard] (the
 * other states under Incidents and closures) is a divided part of that card instead of a card of its own.
 */
@Composable
internal fun SettingsGroup(
    @StringRes title: Int,
    summary: String,
    titles: Set<Int> = emptySet(),
    inCard: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val jump = LocalSettingsJump.current
    var open by rememberSaveable { mutableStateOf(false) }
    val target = jump.target
    LaunchedEffect(target) { if (target != null && target in titles) open = true }
    val body: @Composable () -> Unit = {
        Column {
            if (inCard) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListItem(
                headlineContent = { Text(stringResource(title)) },
                supportingContent = { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null) },
                colors = rowColors(),
                modifier = Modifier.clickable(
                    onClickLabel = stringResource(if (open) R.string.settings_group_close else R.string.settings_group_open),
                ) { open = !open },
            )
            if (open) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(vertical = 4.dp), content = content)
            }
        }
    }
    if (inCard) {
        body()
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Surface(shape = RectangleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                body()
            }
        }
    }
}

/**
 * An explanation cut to one line, with an ⓘ when there is more; tapping it shows the rest (owner, October 2026:
 * "Short, tap ⓘ for more"). A screen reader reads it whole either way.
 */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    val more = cut || expanded
    Row(
        modifier.then(
            if (more) {
                Modifier.clickable(
                    onClickLabel = stringResource(if (expanded) R.string.settings_note_less else R.string.settings_note_more),
                ) { expanded = !expanded }
            } else {
                Modifier
            },
        ),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
            modifier = Modifier.weight(1f, fill = false),
        )
        if (more) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, top = 1.dp).size(16.dp),
            )
        }
    }
}

/** A [Note] on its own line in a card, outside any row. */
@Composable
internal fun CardNote(text: String) {
    Note(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
internal fun Subheading(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
internal fun Toggle(@StringRes title: Int, checked: Boolean, @StringRes note: Int? = null, onChange: (Boolean) -> Unit) {
    JumpTarget(title) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = note?.let { { Note(stringResource(it)) } },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
            colors = rowColors(),
        )
    }
}

/**
 * A choice. Short ones are a row of segmented buttons, one tap away; with [menu] (long labels: the voice engine,
 * the output) it is one row showing the current value, and tapping it opens the list (owner, October 2026: "Mix by
 * length").
 */
@Composable
internal fun <T> Choice(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    note: String? = null,
    menu: Boolean = false,
    onSelect: (T) -> Unit,
) {
    JumpTarget(title) {
        if (menu) {
            MenuChoice(title, options, selected, label, note, onSelect)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                        ) { Text(label(option), maxLines = 1) }
                    }
                }
                note?.let { Note(it) }
            }
        }
    }
}

@Composable
private fun <T> MenuChoice(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    note: String?,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val checkMark: @Composable () -> Unit = { Icon(Icons.Filled.Check, contentDescription = null) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = {
                Column {
                    Text(label(selected), color = MaterialTheme.colorScheme.primary)
                    note?.let { Note(it) }
                }
            },
            trailingContent = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            colors = rowColors(),
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    trailingIcon = if (option == selected) checkMark else null,
                )
            }
        }
    }
}

/** A row a search result can point at: scrolled into view and lit for a moment when it is the jump's target. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JumpTarget(@StringRes title: Int, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable () -> Unit) {
    val jump = LocalSettingsJump.current
    val requester = remember { BringIntoViewRequester() }
    val hit = jump.target == title
    val lit by animateColorAsState(
        if (hit) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "settings-jump",
    )
    LaunchedEffect(hit) {
        if (!hit) return@LaunchedEffect
        delay(JUMP_SETTLE_MS)
        requester.bringIntoView()
        delay(JUMP_LIT_MS)
        if (jump.target == title) jump.target = null
    }
    Box(modifier.bringIntoViewRequester(requester).background(lit)) { content() }
}
