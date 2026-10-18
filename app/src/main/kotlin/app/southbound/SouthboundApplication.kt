package app.southbound

import android.app.Application
import app.southbound.core.packs.PackStore
import app.southbound.core.updates.UpdateScheduler
import app.southbound.core.voice.GuidanceAudioFiles
import org.maplibre.android.MapLibre

class SouthboundApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        // Spoken prompts a process death left in the cache (street names, as audio) go before anything else runs.
        GuidanceAudioFiles.sweep(cacheDir)
        // Pack copies a share left waiting for an answer (or a newer share cut off): no import runs yet.
        PackStore(filesDir, getExternalFilesDir(null)).sweepStaged()
        // Map data (and, if turned on, the app) update from the latest release.
        UpdateScheduler.onAppStart(this)
    }
}
