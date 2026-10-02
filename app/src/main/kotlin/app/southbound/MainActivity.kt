package app.southbound

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.southbound.feature.explore.ExploreScreen
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
                ExploreScreen(sharedPack = sharedPack, onSharedPackHandled = { sharedPack = null })
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
