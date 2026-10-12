package app.southbound.feature.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.southbound.core.traffic.FasterRoute

/**
 * What a trip keeps while the map screen is rebuilt under it (a map region arriving mid-trip, ExploreScreen): the
 * plan Pause needs, a paused trip, the alerts already spoken (so none is said twice and a closure isn't routed around
 * again) and the faster routes turned down. Held by [ExploreServices]; touched on the main thread only.
 */
internal class TripMemory {
    /** The trip being driven, as planned: Pause keeps what is left of it. */
    var plan by mutableStateOf<Panel.Directions?>(null)

    /** A paused trip, until it is resumed or ended. */
    var paused by mutableStateOf<PausedTrip?>(null)

    /** Ids of the closures, police reports, incidents and weather alerts already spoken this trip. */
    val announcedClosures = mutableSetOf<String>()
    val announcedPolice = mutableSetOf<String>()
    val announcedIncidents = mutableSetOf<String>()
    val announcedWeather = mutableSetOf<String>()

    /** The faster routes the driver turned down, not offered again for a while. */
    val dismissedRoutes = FasterRoute.Dismissals()
}
