package org.nysmaps.app

import android.app.Application
import org.maplibre.android.MapLibre

class NysMapsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}
