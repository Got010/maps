package app.southbound.feature.explore

import android.content.Context
import android.os.SystemClock
import app.southbound.core.map.Eta
import app.southbound.core.routing.LaneWording
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.StepLanes
import app.southbound.core.routing.TravelMode
import app.southbound.core.routing.TripStops
import app.southbound.core.search.AlertKind
import app.southbound.core.search.LatLon
import app.southbound.core.search.Landmark
import app.southbound.core.search.Landmarks
import app.southbound.core.search.RouteAlert
import app.southbound.core.search.RouteAlerts
import app.southbound.core.search.RouteLine
import app.southbound.core.search.RouteProgress
import app.southbound.core.search.SearchIndex
import app.southbound.core.voice.GuidanceContext
import app.southbound.core.voice.GuidancePolicy
import app.southbound.core.voice.GuidanceVoice
import app.southbound.core.voice.InstructionText
import app.southbound.core.voice.LanePrompt
import app.southbound.core.voice.NextInstruction
import app.southbound.core.voice.SpokenUnits
import app.southbound.core.voice.TurnTiming
import app.southbound.core.voice.UtteranceKind
import app.southbound.core.voice.VoiceSettings
import com.stadiamaps.ferrostar.core.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.TripState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guidance that needs the route and the search pack together, wired between
 * the navigator and the voice:
 *
 * - landmarks for turns ("Turn right after the next light"), from the
 *   pack's landmark table, fetched once per route and reroute;
 * - a quick second turn chained onto the first ("Take exit 14S, then turn
 *   left"), the second then not announced again in full ([TurnTiming]);
 * - lane advice ("..., use the right two lanes") on one early prompt per
 *   turn, from Valhalla's lanes in the route ([StepLanes], [LaneWording]);
 * - the "now" prompt said a few seconds ahead at speed, and prompts for a
 *   turn already under way dropped;
 * - the arrival time as the trip starts ([startTrip]), each stop and the
 *   destination by name on arrival, and the trip ended a few seconds after
 *   "You have arrived";
 * - the next prompt's audio made ahead of time;
 * - speed camera, licence-plate reader and school zone alerts about 300 m
 *   before each, and [routeAlerts] for the map to draw.
 *
 * Everything runs off the main thread and from files on the phone.
 */
class GuidanceSupport(
    context: Context,
    private val voice: GuidanceVoice,
    private val settings: StateFlow<VoiceSettings>,
    private val search: () -> SearchIndex?,
) : GuidanceContext {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watching: Job? = null

    private val _routeAlerts = MutableStateFlow<List<RouteAlert>>(emptyList())

    /** Cameras and school zones on the active route, in driving order; empty between trips. */
    val routeAlerts: StateFlow<List<RouteAlert>> = _routeAlerts.asStateFlow()

    /** The trip's remaining steps, as of the latest navigation state. */
    @Volatile
    private var steps: List<RouteStep> = emptyList()

    /** Landmarks near the current route, once loaded. */
    @Volatile
    private var nearRoute: List<Landmark> = emptyList()

    /** Metres to the current step's maneuver and the GPS speed (m/s), as of the latest state. */
    @Volatile
    private var distanceToManeuver = Double.MAX_VALUE

    @Volatile
    private var speedMps: Double? = null

    /** Waypoints left (stops, then the destination); 1 on the way to the destination. */
    @Volatile
    private var waypointsLeft = -1

    /** Prompts not to say: their turn was already said as a follow-up. Touched by the voice too. */
    private val chainedAway: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // The "now" prompts of turns already said as a follow-up, with their lanes.
    private val chainedNow: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The navigator being followed, to end a trip after arriving. */
    @Volatile
    private var navigator: Navigator? = null

    /** Names of the trip's stops then its destination, from [startTrip]. */
    @Volatile
    private var tripNames: List<String> = emptyList()

    /** Counts [startTrip]s, so a late arrival can't end the next trip. */
    @Volatile
    private var tripSerial = 0

    /** "You have arrived" said (or being said) for this trip, and the trip's end under way. */
    private val arrivalSaid = AtomicBoolean(false)
    private val ending = AtomicBoolean(false)

    @Volatile
    private var arrivalSpeech: Job? = null

    // Touched only from the state collector.
    private var routeKey: RouteKey? = null
    private var loading: Job? = null
    @Volatile
    private var progress: RouteProgress? = null
    private var stepCount = -1
    private var prepared: String? = null
    private val announced = mutableSetOf<Long>()
    private val spokenEarly = mutableSetOf<String>()

    /** Follows [navigator]'s trips. Called once per navigator. */
    fun attach(navigator: Navigator) {
        watching?.cancel()
        this.navigator = navigator
        watching = scope.launch { navigator.state.collect { runCatching { onState(it) } } }
    }

    /**
     * Starts [route] on [navigator] and says "Starting. You'll arrive at about
     * 1:11 PM, 12 miles." ahead of the first turn. [places] names the trip's
     * stops then its destination, for the arrival sentences. [durationSeconds]
     * (with any traffic delay) and [distanceMeters] are the route preview's.
     */
    fun startTrip(
        navigator: Navigator,
        route: Route,
        mode: TravelMode,
        places: List<String>,
        durationSeconds: Double,
        distanceMeters: Double,
    ) {
        tripSerial++
        tripNames = places
        val announcement = appContext.getString(
            R.string.explore_trip_start_spoken,
            // The same clock time the trip screen shows.
            Eta.clock(System.currentTimeMillis(), durationSeconds),
            SpokenUnits.distance(distanceMeters),
        )
        voice.startTrip(announcement) { navigator.start(route, mode) }
    }

    private fun onState(state: NavigationState) {
        val trip = state.tripState as? TripState.Navigating
        if (trip == null) {
            // Ferrostar's own arrival: within 30 m of the end with a good fix.
            if (state.tripState is TripState.Complete) arrive(end = true)
            if (routeKey != null || stepCount >= 0) endTrip()
            return
        }
        if (stepCount < 0) {
            arrivalSaid.set(false)
            ending.set(false)
            arrivalSpeech = null
        }
        voice.guidanceContext = this
        distanceToManeuver = trip.progress.distanceToNextManeuver
        speedMps = trip.userLocation.speed?.value
        steps = trip.remainingSteps

        // Passing a maneuver ends "mute until the next turn".
        if (stepCount >= 0 && trip.remainingSteps.size < stepCount) voice.maneuverPassed()
        stepCount = trip.remainingSteps.size

        val key = RouteKey.of(state)
        // A stop reached on the same route; a skipped stop comes with a new route and says nothing.
        val left = trip.remainingWaypoints.size
        if (key != null && key == routeKey && waypointsLeft > left) stopReached(waypointsLeft, left)
        waypointsLeft = left

        if (key != null && key != routeKey) {
            routeKey = key
            progress = null
            nearRoute = emptyList()
            _routeAlerts.value = emptyList()
            chainedAway.clear()
            chainedNow.clear()
            spokenEarly.clear()
            loading?.cancel()
            val line = RouteLine(state.routeGeometry.map { LatLon(it.lat, it.lng) })
            loading = scope.launch(Dispatchers.IO) { load(line) }
        }

        // Arrived without Ferrostar noticing, e.g. a poor fix between buildings.
        if (left <= 1 && !state.isCalculatingNewRoute && trip.progress.distanceRemaining <= ARRIVAL_NEAR_M) arrive(end = true)

        alerts(trip)
        speakNowEarly(trip)
        prepareNext(trip)
    }

    private fun endTrip() {
        routeKey = null
        loading?.cancel()
        progress = null
        stepCount = -1
        prepared = null
        announced.clear()
        spokenEarly.clear()
        chainedAway.clear()
        chainedNow.clear()
        steps = emptyList()
        nearRoute = emptyList()
        distanceToManeuver = Double.MAX_VALUE
        speedMps = null
        waypointsLeft = -1
        _routeAlerts.value = emptyList()
        voice.guidanceContext = null
        voice.maneuverPassed()
    }

    /** "Arrived at Joe's Pizza, continuing to Grandma's." for a stop on the way. */
    private fun stopReached(before: Int, after: Int) {
        val (stop, next) = TripStops.reached(tripNames, before, after) ?: return
        voice.announce(UtteranceKind.ARRIVAL, appContext.getString(R.string.explore_arrived_stop_spoken, stop, next))
    }

    /**
     * Says "You have arrived at Joe's Pizza." once per trip. With [end] (the
     * trip is over: Ferrostar completed it, or the route's end is a few metres
     * away) the trip then ends: once the sentence has played and the arrival
     * card has shown for [ARRIVAL_MIN_SHOW_MS], back to browsing. Without it
     * (the route's own "You have arrived" prompt came due), only the words.
     * Any thread.
     */
    @Synchronized
    private fun arrive(end: Boolean) {
        val nav = navigator ?: return
        if (arrivalSaid.compareAndSet(false, true)) {
            val name = tripNames.lastOrNull()?.takeIf { it.isNotBlank() }
            val text = if (name != null) {
                appContext.getString(R.string.explore_arrived_spoken, name)
            } else {
                appContext.getString(R.string.explore_arrived_plain_spoken)
            }
            arrivalSpeech = scope.launch { voice.announceAndWait(UtteranceKind.ARRIVAL, text, ARRIVAL_SPEECH_TIMEOUT_MS) }
        }
        if (!end || !ending.compareAndSet(false, true)) return
        val serial = tripSerial
        val speech = arrivalSpeech
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            // Ending the trip clears the voice queue: let the sentence finish first.
            speech?.join()
            val shown = SystemClock.elapsedRealtime() - started
            if (shown < ARRIVAL_MIN_SHOW_MS) delay(ARRIVAL_MIN_SHOW_MS - shown)
            withContext(Dispatchers.Main) {
                // Not when the user ended it already, or started another trip meanwhile.
                if (tripSerial == serial && navigator === nav && nav.state.value.tripState !is TripState.Idle) nav.stop()
            }
        }
    }

    /** At speed, says the current step's "now" prompt a few seconds out instead of at Ferrostar's trigger. */
    private fun speakNowEarly(trip: TripState.Navigating) {
        val step = trip.remainingSteps.firstOrNull() ?: return
        val index = TurnTiming.speakNowEarly(prompts(step), trip.progress.distanceToNextManeuver, speedMps) ?: return
        val instruction = step.spokenInstructions.getOrNull(index) ?: return
        if (spokenEarly.add(instruction.utteranceId.toString())) voice.speakEarly(instruction)
    }

    private fun prompts(step: RouteStep): List<TurnTiming.Prompt> =
        step.spokenInstructions.map { TurnTiming.Prompt(it.text, it.triggerDistanceBeforeManeuver) }

    /** Reads the landmarks along [line] in small boxes, keeping those close to it. */
    private fun load(line: RouteLine) {
        val index = search() ?: return
        val found = line.boxes().flatMap { box -> index.landmarks(box) }.distinctBy { it.id }
        val close = found.filter { mark ->
            val loc = line.locate(mark.point)
            loc != null && loc.offset <= NEAR_ROUTE_M
        }
        nearRoute = close
        progress = RouteProgress(line)
        _routeAlerts.value = RouteAlerts.onRoute(line, close)
    }

    private fun alerts(trip: TripState.Navigating) {
        val alerts = _routeAlerts.value
        val tracker = progress ?: return
        if (alerts.isEmpty()) return
        val here = trip.snappedUserLocation.coordinates
        val along = tracker.update(LatLon(here.lat, here.lng)) ?: return
        val due = RouteAlerts.due(alerts, along, announced, enabledKinds(settings.value))
        announced += due.covered
        for (alert in due.speak) {
            val text = appContext.getString(
                when (alert.kind) {
                    AlertKind.SPEED_CAMERA -> R.string.explore_alert_speed_camera
                    AlertKind.SCHOOL_ZONE -> R.string.explore_alert_school_zone
                    AlertKind.ALPR_CAMERA -> R.string.explore_alert_alpr_camera
                },
            )
            voice.announce(UtteranceKind.HAZARD_ALERT, text)
        }
    }

    private fun enabledKinds(s: VoiceSettings): Set<AlertKind> = buildSet {
        if (s.speedCameraAlerts) add(AlertKind.SPEED_CAMERA)
        if (s.alprCameraAlerts) add(AlertKind.ALPR_CAMERA)
        if (s.schoolZoneAlerts) add(AlertKind.SCHOOL_ZONE)
    }

    /** Makes the audio of the prompt Ferrostar will trigger next. */
    private fun prepareNext(trip: TripState.Navigating) {
        val current = trip.remainingSteps.firstOrNull() ?: return
        val following = trip.remainingSteps.getOrNull(1)
        val pick = NextInstruction.pick(
            current.spokenInstructions.map { it.triggerDistanceBeforeManeuver },
            trip.progress.distanceToNextManeuver,
            following?.spokenInstructions?.map { it.triggerDistanceBeforeManeuver },
        ) ?: return
        val step = if (pick.stepOffset == 0) current else following ?: return
        val next = step.spokenInstructions.getOrNull(pick.index) ?: return
        val id = next.utteranceId.toString()
        if (id == prepared) return
        prepared = id
        voice.prepare(next)
    }

    // GuidanceContext: called by the voice, off the main thread.

    private fun stepOf(utteranceId: String): Int? = stepIn(steps, utteranceId)

    private fun stepIn(list: List<RouteStep>, utteranceId: String): Int? {
        val i = list.indexOfFirst { step -> step.spokenInstructions.any { it.utteranceId.toString() == utteranceId } }
        return i.takeIf { it >= 0 }
    }

    override fun landmarkFor(instruction: SpokenInstruction): String? {
        val current = steps
        val i = stepIn(current, instruction.utteranceId.toString()) ?: return null
        val step = current.getOrNull(i) ?: return null
        // The step ends at the turn; the one before it too, since the prompt can be heard before this step starts
        // (a light there is still ahead of the driver).
        val own = step.geometry.map { LatLon(it.lat, it.lng) }
        val before = current.getOrNull(i - 1)?.geometry?.map { LatLon(it.lat, it.lng) }.orEmpty()
        val approach = if (before.isNotEmpty() && own.isNotEmpty()) before.dropLast(1) + own else own
        val turn = approach.lastOrNull() ?: return null
        // Where the driver hears it: the trigger distance, or further out for a "now" prompt said early at speed.
        val index = step.spokenInstructions.indexOfFirst { it.utteranceId == instruction.utteranceId }
        val early = if (index >= 0 && index == TurnTiming.nowIndex(prompts(step))) (speedMps ?: 0.0) * TurnTiming.NOW_LEAD_SECONDS else 0.0
        val heard = maxOf(instruction.triggerDistanceBeforeManeuver, early)
        val nearby = Landmarks.near(turn, nearRoute, heard)
        if (nearby.isEmpty()) return null
        return Landmarks.phraseFor(approach, nearby, heard)
    }

    /** The step whose turn comes so soon after the one [instruction] announces that both are said together. */
    private fun chainedNext(instruction: SpokenInstruction): RouteStep? {
        // "Continue for 2 miles" isn't about the turn ahead; "..., then turn left" would skip it.
        if (TurnTiming.isProgress(instruction.text)) return null
        val current = steps
        val i = stepIn(current, instruction.utteranceId.toString()) ?: return null
        // This prompt's turn ends step i; the next turn ends step i + 1, its length further on.
        val next = current.getOrNull(i + 1) ?: return null
        return next.takeIf { TurnTiming.chains(it.distance, speedMps, voice.guidanceMode) }
    }

    override fun followUpLanesFor(instruction: SpokenInstruction): String? =
        chainedNext(instruction)?.let { LaneWording.phrase(StepLanes.of(it)) }

    override fun followUpFor(instruction: SpokenInstruction): String? {
        val next = chainedNext(instruction) ?: return null
        // The prompt Valhalla gives right at that turn is its plainest wording.
        val now = TurnTiming.nowIndex(prompts(next)) ?: return null
        val text = next.spokenInstructions.getOrNull(now)?.text ?: return null
        // Arriving isn't chained ("..., then you have arrived"), so its own prompts stay.
        return text.takeIf { GuidancePolicy.kindOf(it) != UtteranceKind.ARRIVAL }
    }

    override fun lanesFor(instruction: SpokenInstruction): String? {
        val current = steps
        val id = instruction.utteranceId.toString()
        val step = current.getOrNull(stepIn(current, id) ?: return null) ?: return null
        // One early prompt per turn says it; the "now" prompt stays short...
        val index = step.spokenInstructions.indexOfFirst { it.utteranceId.toString() == id }
        if (index < 0) return null
        val lanePrompt = LanePrompt.indexFor(prompts(step))
        val onNow = lanePrompt == null && index == TurnTiming.nowIndex(prompts(step)) && id !in chainedNow
        // ...unless the step has no earlier prompt (a short one, often right after an exit) and its lanes weren't
        // said as a follow-up: then the "now" prompt is the only chance.
        if (index != lanePrompt && !onNow) return null
        return LaneWording.phrase(StepLanes.of(step))
    }

    override fun isCurrent(utteranceId: String): Boolean {
        val i = stepOf(utteranceId) ?: return false
        // The current step's turn is under the car: too late to announce it.
        return !(i == 0 && TurnTiming.tooLate(distanceToManeuver, speedMps))
    }

    override fun shouldSpeak(instruction: SpokenInstruction): Boolean {
        if (InstructionText.isArrived(instruction.text)) {
            // The app says its own, with the place's name: for a stop as its
            // waypoint is passed, for the destination now (the trip ends once
            // Ferrostar completes it).
            if (waypointsLeft in 0..1) arrive(end = false)
            return false
        }
        val id = instruction.utteranceId.toString()
        if (id in chainedAway) return false
        // Unknown yet (a new route before its first state): let it through, it is checked again before playing.
        val i = stepOf(id) ?: return true
        return !(i == 0 && TurnTiming.tooLate(distanceToManeuver, speedMps))
    }

    override fun followUpSpoken(instruction: SpokenInstruction) {
        val current = steps
        val i = stepIn(current, instruction.utteranceId.toString()) ?: return
        val next = current.getOrNull(i + 1) ?: return
        for (index in TurnTiming.skippedAfterChain(prompts(next))) {
            next.spokenInstructions.getOrNull(index)?.let { chainedAway += it.utteranceId.toString() }
        }
        // Its lanes were said with it; the "now" prompt at that turn stays short.
        TurnTiming.nowIndex(prompts(next))?.let { now -> next.spokenInstructions.getOrNull(now)?.let { chainedNow += it.utteranceId.toString() } }
    }

    /** Tells routes apart cheaply: a reroute changes the shape. */
    private data class RouteKey(val size: Int, val first: Pair<Double, Double>, val last: Pair<Double, Double>) {
        companion object {
            fun of(state: NavigationState): RouteKey? {
                val g = state.routeGeometry
                if (g.size < 2) return null
                return RouteKey(g.size, g.first().lat to g.first().lng, g.last().lat to g.last().lng)
            }
        }
    }

    private companion object {
        /** Landmarks further than this from the route never matter to it. */
        const val NEAR_ROUTE_M = 60.0

        /**
         * On the way to the destination, this close to the end of the route
         * counts as arrived even if Ferrostar hasn't completed the trip (it
         * wants 30 m with a fix better than 32 m).
         */
        const val ARRIVAL_NEAR_M = 25.0

        /** The longest the trip waits for "You have arrived" to finish playing before it ends. */
        const val ARRIVAL_SPEECH_TIMEOUT_MS = 10_000L

        /** The arrival card (side of the street, parking) stays at least this long before the trip ends. */
        const val ARRIVAL_MIN_SHOW_MS = 4_000L
    }
}
