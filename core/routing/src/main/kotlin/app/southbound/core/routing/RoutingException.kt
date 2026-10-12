package app.southbound.core.routing

/** No route: a place off the road network or outside the routing pack, or every way excluded. */
class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)
