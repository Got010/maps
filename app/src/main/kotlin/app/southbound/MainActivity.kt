package app.southbound

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.southbound.feature.explore.ExploreScreen
import app.southbound.feature.explore.ExploreServices
import app.southbound.feature.settings.SettingsScreen
import app.southbound.theme.SouthboundTheme

class MainActivity : ComponentActivity() {

    /** A pack file shared to the app, waiting to be imported. */
    private var sharedPack by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After process death Android re-delivers the original intent; it was already imported.
        if (savedInstanceState == null) sharedPack = intent.sharedPackUri()
        setContent {
            SouthboundTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                // Settings opens over the map, so the map (and any trip) stays as it was.
                Box {
                    ExploreScreen(
                        sharedPack = sharedPack,
                        onSharedPackHandled = { sharedPack = null },
                        onOpenSettings = { showSettings = true },
                    )
                    if (showSettings) {
                        val services = ExploreServices.get(this@MainActivity)
                        val settings by services.voiceSettings.collectAsState()
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            services.voiceStore,
                            settings,
                            services.voice,
                            onBack = { showSettings = false },
                            network = services.networkToggles,
                            secrets = services.secrets,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedPackUri()?.let { sharedPack = it }
    }
}

// getParcelableExtra(String, Class) needs API 33; minSdk is 31.
@Suppress("DEPRECATION")
private fun Intent.sharedPackUri(): Uri? = when (action) {
    Intent.ACTION_SEND -> getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    Intent.ACTION_VIEW -> data
    else -> null
}
