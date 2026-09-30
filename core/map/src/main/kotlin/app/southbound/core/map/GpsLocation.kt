package app.southbound.core.map

import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executor

/**
 * Fixes from the GPS provider only: no Google fused location and no network
 * location. Updates stop when the collector is cancelled.
 */
@RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
fun LocationManager.gpsUpdates(intervalMillis: Long = 1_000L): Flow<Location> = callbackFlow {
    getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { trySend(it) }

    val listener = LocationListener { location -> trySend(location) }
    val request = LocationRequest.Builder(intervalMillis)
        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
        .build()
    // trySend is thread-safe, so deliver on the binder thread.
    val direct = Executor { it.run() }
    requestLocationUpdates(LocationManager.GPS_PROVIDER, request, direct, listener)

    awaitClose { removeUpdates(listener) }
}
