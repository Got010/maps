package app.southbound.core.routing

import com.stadiamaps.ferrostar.core.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.ferrostar.TripState

/**
 * What a second screen (the car) shows of the trip, as plain values with no Ferrostar types in
 * the way: the current turn, the one after it, and what is left of the trip.
 *
 * [maneuverType] and [maneuverModifier] are the names of Ferrostar's ManeuverType and
 * ManeuverModifier values (e.g. "TURN", "SLIGHT_RIGHT"); the reader normalises them.
 */
data class TripSnapshot(
    val instruction: String,
    /** The banner's second line, e.g. what the exit is toward. */
    val detail: String?,
    val maneuverType: String?,
    val maneuverModifier: String?,
    val distanceToManeuverMeters: Double,
    /** The road the car is on now. */
    val roadName: String?,
    val nextInstruction: String?,
    val nextManeuverType: String?,
    val nextManeuverModifier: String?,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val rerouting: Boolean,
    /** Ferrostar completed the trip; the phone's arrival card is up. */
    val arrived: Boolean,
)

/**
 * The trip as the car sees it. The navigator's state is followed here for the whole process
 * (see ExploreServices.openNavigator), not by the Activity, so the car keeps working when the
 * phone's screen is off or the app is in the background. Null means no trip.
 */
object TripBroadcast {
    private val mutableSnapshot = MutableStateFlow<TripSnapshot?>(null)
    val snapshot: StateFlow<TripSnapshot?> = mutableSnapshot.asStateFlow()

    /** Ends the trip, as the End button does on the phone. Set along with [follow]. */
    @Volatile
    var endTrip: (() -> Unit)? = null
        private set

    private var following: Job? = null

    /** Publishes [state]'s trips from now on, replacing whatever was followed before. */
    @Synchronized
    fun follow(scope: CoroutineScope, state: StateFlow<NavigationState>, end: () -> Unit) {
        following?.cancel()
        endTrip = end
        following = scope.launch {
            state.collect { mutableSnapshot.value = runCatching { it.toTripSnapshot() }.getOrNull() }
        }
    }

    /** For tests and previews. */
    fun publish(snapshot: TripSnapshot?) {
        mutableSnapshot.value = snapshot
    }
}

internal fun NavigationState.toTripSnapshot(): TripSnapshot? = when (val trip = tripState) {
    is TripState.Navigating -> {
        val visual = trip.visualInstruction
        val primary = visual?.primaryContent
        // The step after this one: the "then" in "then turn left".
        val next = trip.remainingSteps.getOrNull(1)?.visualInstructions?.firstOrNull()?.primaryContent
        TripSnapshot(
            instruction = primary?.text ?: trip.remainingSteps.firstOrNull()?.instruction.orEmpty(),
            detail = visual?.secondaryContent?.text?.takeIf { it.isNotBlank() },
            maneuverType = primary?.maneuverType?.name,
            maneuverModifier = primary?.maneuverModifier?.name,
            distanceToManeuverMeters = trip.progress.distanceToNextManeuver,
            roadName = trip.remainingSteps.firstOrNull()?.roadName?.takeIf { it.isNotBlank() },
            nextInstruction = next?.text?.takeIf { it.isNotBlank() },
            nextManeuverType = next?.maneuverType?.name,
            nextManeuverModifier = next?.maneuverModifier?.name,
            remainingMeters = trip.progress.distanceRemaining,
            remainingSeconds = trip.progress.durationRemaining,
            rerouting = isCalculatingNewRoute,
            arrived = false,
        )
    }
    is TripState.Complete -> TripSnapshot(
        instruction = "", detail = null, maneuverType = null, maneuverModifier = null,
        distanceToManeuverMeters = 0.0, roadName = null, nextInstruction = null,
        nextManeuverType = null, nextManeuverModifier = null,
        remainingMeters = 0.0, remainingSeconds = 0.0, rerouting = false, arrived = true,
    )
    is TripState.Idle -> null
}
