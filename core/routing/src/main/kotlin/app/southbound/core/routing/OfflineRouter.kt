package app.southbound.core.routing

import android.content.Context
import com.valhalla.config.models.ValhallaConfig
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaException
import com.valhalla.valhalla.config.ValhallaConfigFactory
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WellKnownRouteProvider
import java.io.Closeable
import java.io.File

/**
 * Routes on the phone with Valhalla, reading a routing pack: a folder of tiles
 * (core/packs TileFolder: routing-main.tar and the packs of the phone's
 * regions, unpacked together) or a one-graph tar (routing-nys.tar).
 *
 * Ferrostar's Valhalla adapter builds each request and parses the response,
 * so routes come out exactly as its navigation engine expects; only the
 * transport differs. The adapter is never given an HTTP client: its request
 * body goes straight to the on-device engine, so no request leaves the phone.
 */
class OfflineRouter(context: Context, routingPack: File) : Closeable {

    private val valhalla: Valhalla = Valhalla(
        context,
        configFor(routingPack),
        // One config file per pack, so a router for a new pack can't clobber an open one.
        ValhallaConfigManager(context, ValhallaFile(context, "valhalla-${routingPack.nameWithoutExtension}.json")),
    )

    private val adapters = TravelMode.entries.associateWith { mode ->
        RouteAdapter.fromWellKnownRouteProvider(
            WellKnownRouteProvider.Valhalla(
                // Never contacted; Ferrostar requires an endpoint to build the request.
                endpointUrl = "http://valhalla.invalid/route",
                profile = mode.valhallaCosting,
                optionsJson = RouteOptions.json(mode),
            ),
        )
    }

    /**
     * Routes from [from] through [waypoints], best first, with up to two
     * alternatives when Valhalla finds them. [prefs] are the driver's route options. [avoid] holds polygons (rings of
     * lon, lat) the route must not cross, e.g. around closures; [excludePoints] roads it must not use (a spot on
     * each, beside a plate reader, [CameraAvoidance]). [useDistance]
     * weighs distance into the choice and [alternates] false asks for the
     * best route only (the preview's shorter way, [ShorterRoute]). Blocking:
     * call off the main thread.
     *
     * @throws RoutingException when there is no route, e.g. a destination
     *   outside the routing pack.
     */
    fun routes(
        from: UserLocation,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        avoid: List<List<Pair<Double, Double>>> = emptyList(),
        excludePoints: List<CameraAvoidance.Spot> = emptyList(),
        // Phase 6: tolls, highways, ferries and the vehicle profile.
        prefs: RoutePrefs = RoutePrefs.DEFAULT,
        useDistance: Double? = null,
        alternates: Boolean = true,
    ): List<Route> {
        val adapter = adapters.getValue(mode)
        val request = adapter.generateRequest(from, waypoints)
        fun body(strictTolls: Boolean): String = when (request) {
            is RouteRequest.HttpPost -> {
                val withPrefs = RouteOptions.withPrefs(request.body.decodeToString(), mode, prefs, useDistance, strictTolls)
                val withPolygons = RouteOptions.withExcludePolygons(if (alternates) withPrefs else RouteOptions.withoutAlternates(withPrefs), avoid)
                RouteOptions.withExcludeLocations(withPolygons, excludePoints)
            }
            is RouteRequest.HttpGet -> throw RoutingException("Unexpected GET route request")
        }
        val response = try {
            synchronized(valhalla) { valhalla.routeRaw(body(strictTolls = true)) }
        } catch (e: ValhallaException) {
            // "Avoid tolls" and "Avoid plate readers" leave out every toll road; when there is no way around one (an
            // island, a toll bridge across the only river crossing in reach), the route keeps as few tolls as it can.
            val tollsOnly = mode == TravelMode.DRIVE && prefs.avoidsTolls
            try {
                if (!tollsOnly) throw e
                synchronized(valhalla) { valhalla.routeRaw(body(strictTolls = false)) }
            } catch (e: ValhallaException) {
                // e.g. "No path could be found for input": a place off the road
                // network or outside the routing pack.
                throw RoutingException("No route found", e)
            }
        }
        val routes = adapter.parseResponse(response.encodeToByteArray())
        if (routes.isEmpty()) throw RoutingException("No route found")
        return routes
    }

    override fun close() = synchronized(valhalla) { valhalla.close() }

    companion object {
        /**
         * Tiles read from a folder are held in memory, Valhalla's default allows 1 GB; a long trip touches the
         * main roads' tiles (about 120 MB for every state) and the local tiles at its ends.
         */
        private const val TILE_CACHE_BYTES = 256 * 1024 * 1024

        /**
         * Valhalla allows 50 `exclude_locations` by default; "Avoid plate readers" sends one per camera met, up to
         * [CameraAvoidance.MAX_CAMERAS], and closures' polygons keep their own limit. Loki finds each point's road
         * from the tile's bins, so a few hundred cost tens of milliseconds, not the seconds that as many polygons would.
         */
        private const val MAX_EXCLUDE_LOCATIONS = CameraAvoidance.MAX_CAMERAS + 50

        /**
         * A folder of tiles is a phone's part of the graph: Loki's connectivity check colours connected areas by
         * the local tiles present, so with the regions between two places missing it would call them unconnected
         * though the main roads join them (pipeline/routing/route_check.py found it); it's off, as valhalla-mobile
         * turns it off for any tile set that isn't whole.
         */
        fun configFor(routingPack: File): ValhallaConfig {
            if (!routingPack.isDirectory) {
                val config = ValhallaConfigFactory.usingTileExtract(routingPack.absolutePath)
                return config.copy(serviceLimits = config.serviceLimits?.copy(maxExcludeLocations = MAX_EXCLUDE_LOCATIONS))
            }
            val config = ValhallaConfigFactory.usingTilesDir(routingPack.absolutePath)
            return config.copy(
                loki = config.loki?.copy(useConnectivity = false),
                mjolnir = config.mjolnir?.copy(maxCacheSize = TILE_CACHE_BYTES),
                serviceLimits = config.serviceLimits?.copy(maxExcludeLocations = MAX_EXCLUDE_LOCATIONS),
            )
        }
    }
}
