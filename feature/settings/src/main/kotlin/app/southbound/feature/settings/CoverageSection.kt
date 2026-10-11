package app.southbound.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.packs.Coverage

/**
 * What each state has, and what it lacks and why ([Coverage]): the gaps at a glance, then every state on a tap. The
 * words come from Coverage, which tests hold to the readers and the build's camera lists.
 */
@Composable
internal fun CoverageSection() {
    var open by rememberSaveable { mutableStateOf(false) }
    Section(R.string.coverage_title)
    Column(Modifier.clickable { open = !open }.padding(horizontal = 16.dp, vertical = 4.dp)) {
        for (line in Coverage.summary()) {
            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
        }
        Text(
            stringResource(if (open) R.string.coverage_hide else R.string.coverage_show),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (!open) return
    Column(Modifier.padding(horizontal = 16.dp)) {
        for (line in Coverage.common()) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        for (state in Coverage.STATES) {
            Text(
                state.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
            for (line in Coverage.lines(state)) {
                Text("• $line", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }
        }
    }
}
