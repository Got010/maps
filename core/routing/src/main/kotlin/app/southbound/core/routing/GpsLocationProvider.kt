package app.southbound.core.routing

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Feeds navigation from the GPS provider only.
 *
 * Ferrostar's own Android provider prefers the fused provider; CLAUDE.md
 * requires GPS, so this replaces it. Callers must hold fine location.
 */
@SuppressLint("MissingPermission")
class GpsLocationProvider(context: Context) : NavigationLocationProviding {

    private val locationManager = context.getSystemService(LocationManager::class.java)

    override suspend fun lastLocation(): Location? =
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)

    override fun locationUpdates(intervalMillis: Long): Flow<Location> = callbackFlow {
        val listener = LocationListener { trySend(it) }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMillis,
            0f,
            listener,
            Looper.getMainLooper(),
        )
        awaitClose { locationManager.removeUpdates(listener) }
    }
}
