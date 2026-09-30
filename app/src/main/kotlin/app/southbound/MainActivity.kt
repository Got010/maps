package app.southbound

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.southbound.theme.SouthboundTheme
import app.southbound.feature.explore.ExploreScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SouthboundTheme {
                ExploreScreen()
            }
        }
    }
}
