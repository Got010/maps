package app.southbound.feature.car

import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.southbound.core.routing.TripBroadcast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tells the car's NavigationManager when a trip starts and ends and feeds it the next turn and
 * arrival, so the car can show them in its cluster and notifications and knows which app is
 * navigating. The phone's foreground service (Ferrostar) is what keeps the trip alive meanwhile.
 *
 * The host's "stop navigation" (another app took over) ends the trip on the phone.
 */
internal class TripReporter(private val carContext: CarContext, lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private val manager = carContext.getCarService(NavigationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    init {
        // The callback must be set before navigationStarted().
        manager.setNavigationManagerCallback(
            carContext.mainExecutor,
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    TripBroadcast.endTrip?.invoke()
                }

                override fun onAutoDriveEnabled() = Unit
            },
        )
        lifecycle.addObserver(this)
        scope.launch {
            TripBroadcast.snapshot
                .map { (CarModel.of(it, System.currentTimeMillis()) as? CarModel.Trip)?.display }
                .distinctUntilChanged()
                .collect { report(it) }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
        if (started) runCatching { manager.navigationEnded() }
        started = false
        runCatching { manager.clearNavigationManagerCallback() }
    }

    // The host can refuse a call (not the active navigation app, say); the car's screen still works then.
    private fun report(display: CarTripDisplay?) {
        if (display == null) {
            if (started) runCatching { manager.navigationEnded() }
            started = false
            return
        }
        if (!started) {
            runCatching { manager.navigationStarted() }.onSuccess { started = true }
        }
        if (!started) return
        runCatching { manager.updateTrip(trip(display)) }
    }

    private fun trip(display: CarTripDisplay): Trip {
        val builder = Trip.Builder()
        if (display.rerouting) return builder.setLoading(true).build()
        builder
            .addStep(display.step.toStep(carContext), display.stepEstimate())
            .addDestination(
                Destination.Builder().setName(carContext.getString(R.string.car_destination)).build(),
                display.travelEstimate(),
            )
        display.road?.let { builder.setCurrentRoad(it) }
        return builder.setLoading(false).build()
    }
}
