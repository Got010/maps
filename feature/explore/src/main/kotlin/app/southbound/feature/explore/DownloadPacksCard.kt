package app.southbound.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.updates.UpdateScheduler
import app.southbound.core.updates.UpdateState
import app.southbound.core.updates.UpdateStatus

/**
 * First run: Southbound fetches the NYC packs from its latest release by
 * itself (on Wi-Fi), or right away on any network when asked.
 */
@Composable
internal fun DownloadPacksCard(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val state by UpdateStatus.state.collectAsState()
    val toggles by PreferenceNetworkToggles.get(context).state.collectAsState()
    val downloadsOn = toggles[NetworkFeature.PACK_DOWNLOADS] == true

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = state) {
            is UpdateState.Downloading -> {
                Text(stringResource(R.string.explore_download_progress, s.overallDone shr 20, s.overallTotal shr 20))
                LinearProgressIndicator(
                    progress = { if (s.overallTotal > 0) s.overallDone.toFloat() / s.overallTotal else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            UpdateState.Checking -> {
                Text(stringResource(R.string.explore_download_checking))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            else -> Text(
                stringResource(if (downloadsOn) R.string.explore_download_wifi else R.string.explore_download_off),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val busy = state is UpdateState.Downloading || state == UpdateState.Checking
        if (downloadsOn) {
            Button(onClick = { UpdateScheduler.checkNow(context, anyNetwork = true) }, enabled = !busy) {
                Text(stringResource(R.string.explore_download_now))
            }
        }
        if (!downloadsOn || (state as? UpdateState.Failed)?.needsToken == true) {
            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.explore_open_settings)) }
        }
    }
}
