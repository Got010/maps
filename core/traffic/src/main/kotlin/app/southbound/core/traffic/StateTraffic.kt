package app.southbound.core.traffic

/**
 * One state's 511 besides 511NY, as [TrafficFeed] fetches it: the whole state each time, every request through the
 * state's own toggle ([TrafficSource.feature]). [events] every few minutes; [cameras] (daily) and [signs] (every
 * [signsTtlMillis]) only for the map's traffic layer. Each may throw an IOException, or an IllegalArgumentException
 * for an answer that isn't what the 511 sends; [TrafficFeed] keeps the last good ones and says why.
 */
interface StateTraffic {
    val source: TrafficSource
    val signsTtlMillis: Long get() = TrafficFeed.SIGNS_TTL_MILLIS

    fun events(now: Long): List<Incident>

    fun cameras(): List<TrafficCamera>

    fun signs(): List<MessageSign>
}
