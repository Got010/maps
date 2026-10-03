package app.southbound.feature.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.southbound.core.routing.TripBroadcast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The car's screen: the current turn with its distance, the one after it, and the arrival time
 * and distance left, in the host's navigation template; a short message when there is no trip.
 * It mirrors the trip running on the phone ([TripBroadcast]); starting one is done on the phone.
 */
internal class TripScreen(carContext: CarContext) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var model: CarModel = CarModel.of(TripBroadcast.snapshot.value, System.currentTimeMillis())

    init {
        scope.launch {
            TripBroadcast.snapshot
                .map { CarModel.of(it, System.currentTimeMillis()) }
                .distinctUntilChanged()
                .collect {
                    model = it
                    invalidate()
                }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template = when (val current = model) {
        CarModel.Idle -> message(carContext.getString(R.string.car_idle))
        CarModel.Arrived -> message(carContext.getString(R.string.car_arrived))
        is CarModel.Trip -> navigation(current.display)
    }

    private fun message(text: String): Template =
        MessageTemplate.Builder(text)
            .setTitle(carContext.applicationInfo.loadLabel(carContext.packageManager).toString())
            .setHeaderAction(Action.APP_ICON)
            .build()

    private fun navigation(display: CarTripDisplay): Template {
        val info = if (display.rerouting) {
            RoutingInfo.Builder().setLoading(true).build()
        } else {
            val builder = RoutingInfo.Builder()
                .setCurrentStep(display.step.toStep(carContext), display.distanceToStep.toDistance())
            display.next?.let { builder.setNextStep(it.toStep(carContext)) }
            builder.build()
        }
        return NavigationTemplate.Builder()
            .setNavigationInfo(info)
            .setDestinationTravelEstimate(display.travelEstimate())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_stop))
                            .setOnClickListener { TripBroadcast.endTrip?.invoke() }
                            .build(),
                    )
                    .build(),
            )
            .build()
    }
}

internal fun CarStep.toStep(context: CarContext): Step =
    Step.Builder(cue)
        .setManeuver(
            Maneuver.Builder(maneuver.carType)
                .setIcon(CarIcon.Builder(IconCompat.createWithResource(context, maneuver.arrow.drawable())).build())
                .build(),
        )
        .build()

/** Arrival and distance left, for the trip as a whole. */
internal fun CarTripDisplay.travelEstimate(): TravelEstimate =
    TravelEstimate.Builder(remaining.toDistance(), DateTimeWithZone.create(arrivalMillis, TimeZone.getDefault()))
        .setRemainingTimeSeconds(remainingSeconds)
        .build()

/** The next manoeuvre's distance and time, for the car's cluster and widgets. */
internal fun CarTripDisplay.stepEstimate(): TravelEstimate =
    TravelEstimate.Builder(distanceToStep.toDistance(), DateTimeWithZone.create(stepArrivalMillis, TimeZone.getDefault()))
        .setRemainingTimeSeconds(((stepArrivalMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
        .build()

private fun Arrow.drawable(): Int = when (this) {
    Arrow.STRAIGHT -> R.drawable.car_arrow_straight
    Arrow.SLIGHT_RIGHT -> R.drawable.car_arrow_slight_right
    Arrow.RIGHT -> R.drawable.car_arrow_right
    Arrow.SHARP_RIGHT -> R.drawable.car_arrow_sharp_right
    Arrow.U_TURN -> R.drawable.car_arrow_u_turn
    Arrow.SHARP_LEFT -> R.drawable.car_arrow_sharp_left
    Arrow.LEFT -> R.drawable.car_arrow_left
    Arrow.SLIGHT_LEFT -> R.drawable.car_arrow_slight_left
    Arrow.ARRIVE -> R.drawable.car_arrow_arrive
}
