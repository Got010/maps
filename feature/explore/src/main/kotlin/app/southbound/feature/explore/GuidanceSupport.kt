package app.southbound.feature.explore

import android.content.Context
import app.southbound.core.routing.Navigator
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
import app.southbound.core.voice.GuidanceVoice
import app.southbound.core.voice.NextInstruction
import app.southbound.core.voice.UtteranceKind
import app.southbound.core.voice.VoiceSettings
import com.stadiamaps.ferrostar.core.NavigationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.TripState

/**
 * Guidance that needs the route and the search pack together, wired between
 * the navigator and the voice:
 *
 * - landmarks for turns ("Turn right after the next light"), from the
 *   pack's landmark table, fetched once per route and reroute;
 * - a quick second turn chained onto the first ("..., then turn left");
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

    // Touched only from the state collector.
    private var routeKey: RouteKey? = null
    private var loading: Job? = null
    @Volatile
    private var progress: RouteProgress? = null
    private var stepCount = -1
    private var prepared: String? = null
    private val announced = mutableSetOf<Long>()

    /** Follows [navigator]'s trips. Called once per navigator. */
    fun attach(navigator: Navigator) {
        watching?.cancel()
        watching = scope.launch { navigator.state.collect { runCatching { onState(it) } } }
    }

    private fun onState(state: NavigationState) {
        val trip = state.tripState as? TripState.Navigating
        if (trip == null) {
            if (routeKey != null || stepCount >= 0) endTrip()
            return
        }
        voice.guidanceContext = this
        steps = trip.remainingSteps

        // Passing a maneuver ends "mute until the next turn".
        if (stepCount >= 0 && trip.remainingSteps.size < stepCount) voice.maneuverPassed()
        stepCount = trip.remainingSteps.size

        val key = RouteKey.of(state)
        if (key != null && key != routeKey) {
            routeKey = key
            progress = null
            nearRoute = emptyList()
            _routeAlerts.value = emptyList()
            loading?.cancel()
            val line = RouteLine(state.routeGeometry.map { LatLon(it.lat, it.lng) })
            loading = scope.launch(Dispatchers.IO) { load(line) }
        }

        alerts(trip)
        prepareNext(trip)
    }

    private fun endTrip() {
        routeKey = null
        loading?.cancel()
        progress = null
        stepCount = -1
        prepared = null
        announced.clear()
        steps = emptyList()
        nearRoute = emptyList()
        _routeAlerts.value = emptyList()
        voice.guidanceContext = null
        voice.maneuverPassed()
    }

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

    private fun stepOf(utteranceId: String): Int? {
        val current = steps
        val i = current.indexOfFirst { step -> step.spokenInstructions.any { it.utteranceId.toString() == utteranceId } }
        return i.takeIf { it >= 0 }
    }

    override fun landmarkFor(instruction: SpokenInstruction): String? {
        val i = stepOf(instruction.utteranceId.toString()) ?: return null
        val approach = steps.getOrNull(i)?.geometry?.map { LatLon(it.lat, it.lng) } ?: return null
        val turn = approach.lastOrNull() ?: return null
        val nearby = nearRoute.filter { RouteLine.distanceMeters(it.point, turn) <= Landmarks.SEARCH_RADIUS_M }
        if (nearby.isEmpty()) return null
        return Landmarks.phraseFor(approach, nearby, instruction.triggerDistanceBeforeManeuver)
    }

    override fun followUpFor(instruction: SpokenInstruction): String? {
        val current = steps
        val i = stepOf(instruction.utteranceId.toString()) ?: return null
        val next = current.getOrNull(i + 1) ?: return null
        if (next.distance >= FOLLOW_UP_M) return null
        // The prompt Valhalla gives right at that turn is its plainest wording.
        return next.spokenInstructions.minByOrNull { it.triggerDistanceBeforeManeuver }?.text
    }

    override fun isCurrent(utteranceId: String): Boolean = stepOf(utteranceId) != null

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

        /** A turn this soon after the previous one is said with it. */
        const val FOLLOW_UP_M = 150.0
    }
}
