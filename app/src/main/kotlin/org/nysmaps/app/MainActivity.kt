package org.nysmaps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.nysmaps.app.theme.NysMapsTheme
import org.nysmaps.feature.explore.ExploreScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NysMapsTheme {
                ExploreScreen()
            }
        }
    }
}
