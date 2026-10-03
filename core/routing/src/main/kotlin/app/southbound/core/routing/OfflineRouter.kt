package app.southbound.core.routing

import android.content.Context
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
 * Routes on the phone with Valhalla, reading a routing pack (routing-<region>.tar).
 *
 * Ferrostar's Valhalla adapter builds each request and parses the response,
 * so routes come out exactly as its navigation engine expects; only the
 * transport differs. The adapter is never given an HTTP client: its request
 * body goes straight to the on-device engine, so no request leaves the phone.
 */
class OfflineRouter(context: Context, routingPack: File) : Closeable {

    private val valhalla: Valhalla = Valhalla(
        context,
        ValhallaConfigFactory.usingTileExtract(routingPack.absolutePath),
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
     * alternatives when Valhalla finds them. Blocking: call off the main thread.
     *
     * @throws RoutingException when there is no route, e.g. a destination
     *   outside the routing pack.
     */
    fun routes(from: UserLocation, waypoints: List<Waypoint>, mode: TravelMode): List<Route> {
        val adapter = adapters.getValue(mode)
        val request = adapter.generateRequest(from, waypoints)
        val body = when (request) {
            is RouteRequest.HttpPost -> request.body.decodeToString()
            is RouteRequest.HttpGet -> throw RoutingException("Unexpected GET route request")
        }
        val response = try {
            synchronized(valhalla) { valhalla.routeRaw(body) }
        } catch (e: ValhallaException) {
            // e.g. "No path could be found for input": a place off the road
            // network or outside the routing pack.
            throw RoutingException("No route found", e)
        }
        val routes = adapter.parseResponse(response.encodeToByteArray())
        if (routes.isEmpty()) throw RoutingException("No route found")
        return routes
    }

    override fun close() = synchronized(valhalla) { valhalla.close() }
}

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)
