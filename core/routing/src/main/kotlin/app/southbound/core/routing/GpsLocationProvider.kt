package app.southbound.core.routing

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
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

    // Only a recent one: a fix from an earlier drive would put the trip's start where the car was then.
    override suspend fun lastLocation(): Location? =
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= LAST_KNOWN_MAX_AGE_NANOS }

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

/** How old the phone's last GPS fix may be to stand in for a new one: two minutes, as on the map (core:map). */
private const val LAST_KNOWN_MAX_AGE_NANOS = 120_000_000_000L
