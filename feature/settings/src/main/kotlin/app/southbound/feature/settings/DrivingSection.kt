package app.southbound.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.southbound.core.routing.DrivingPrefs

// Phase 6: Settings > Driving.
@Composable
internal fun DrivingSection() {
    val prefs = DrivingPrefs.get(LocalContext.current)
    val night by prefs.nightBySunset.collectAsState()
    Section(R.string.settings_driving)
    Toggle(R.string.driving_night, night, R.string.driving_night_note) { prefs.setNightBySunset(it) }
}
