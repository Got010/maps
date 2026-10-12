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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.southbound.core.updates.UpdateNotifications
import app.southbound.core.updates.UpdateStatus
import app.southbound.feature.explore.ExploreScreen
import app.southbound.feature.explore.ExploreServices
import app.southbound.feature.settings.SettingsScreen
import app.southbound.theme.SouthboundTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** A pack file shared to the app, waiting to be imported. */
    private var sharedPack by mutableStateOf<Uri?>(null)

    /**
     * Bumped when an import has replaced the settings (by the person, or finished in the background at start), so
     * Settings is built again and shows what is now stored. Snapshot state, so any thread may bump it.
     */
    private var settingsEpoch by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After process death Android re-delivers the intent: imported already, unless the import was still running
        // when the process went (then it is imported again, rather than dropped without a word).
        if (savedInstanceState == null || savedInstanceState.getBoolean(KEY_PACK_PENDING)) sharedPack = intent.sharedPackUri()
        showInstallPrompts()
        // An import that was checked and kept but stopped before it was applied finishes now.
        ExploreServices.get(this).finishPendingImport { settingsEpoch++ }
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
                        key(settingsEpoch) {
                            SettingsScreen(
                                services.voiceStore,
                                settings,
                                services.voice,
                                onBack = { showSettings = false },
                                network = services.networkToggles,
                                secrets = services.secrets,
                                backup = services.backup,
                                onImported = { settingsEpoch++ },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Android's "Update Southbound?" screen, shown as soon as the app is in front. */
    private fun showInstallPrompts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                UpdateStatus.confirm.collect { confirm ->
                    if (confirm != null) {
                        UpdateStatus.confirmShown()
                        UpdateNotifications.cancelConfirm(this@MainActivity)
                        runCatching { startActivity(confirm) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedPackUri()?.let {
            // Kept as the activity's intent, so a process death mid-import re-delivers this pack, not the first one.
            setIntent(intent)
            sharedPack = it
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PACK_PENDING, sharedPack != null)
    }
}

// getParcelableExtra(String, Class) needs API 33; minSdk is 31.
@Suppress("DEPRECATION")
private fun Intent.sharedPackUri(): Uri? = when (action) {
    Intent.ACTION_SEND -> getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    Intent.ACTION_VIEW -> data
    else -> null
}

/** Saved state: a shared pack was still being imported. */
private const val KEY_PACK_PENDING = "shared_pack_pending"
