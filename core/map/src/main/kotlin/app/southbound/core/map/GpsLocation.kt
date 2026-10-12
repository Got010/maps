package app.southbound.core.map

import android.Manifest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
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
    // The last fix only while it is recent: one from an earlier drive (yesterday's parking spot) would place the puck,
    // set night mode and start routes there until the first real fix.
    getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= LAST_KNOWN_MAX_AGE_NANOS }
        ?.let { trySend(it) }

    val listener = LocationListener { location -> trySend(location) }
    val request = LocationRequest.Builder(intervalMillis)
        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
        .build()
    // trySend is thread-safe, so deliver on the binder thread.
    val direct = Executor { it.run() }
    requestLocationUpdates(LocationManager.GPS_PROVIDER, request, direct, listener)

    awaitClose { removeUpdates(listener) }
}

/** How old the phone's last GPS fix may be to stand in until the first new one: two minutes. */
private const val LAST_KNOWN_MAX_AGE_NANOS = 120_000_000_000L
