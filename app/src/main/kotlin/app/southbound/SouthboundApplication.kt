package app.southbound

import android.app.Application
import app.southbound.core.updates.UpdateScheduler
import org.maplibre.android.MapLibre

class SouthboundApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Map data (and, if turned on, the app) update from the latest release.
        UpdateScheduler.onAppStart(this)
    }
}
