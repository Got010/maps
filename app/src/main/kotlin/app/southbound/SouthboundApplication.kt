package app.southbound

import android.app.Application
import org.maplibre.android.MapLibre

class SouthboundApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
