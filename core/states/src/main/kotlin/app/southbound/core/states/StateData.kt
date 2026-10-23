package app.southbound.core.states

/**
 * The two data files in this module's resources, read on first use and kept parsed in memory after that. The first
 * call reads about 350 KB and parses it: call it off the main thread (the outlines take the longer). A file that
 * cannot be read or parsed throws every time; the tests keep that from happening in a build.
 */
object StateData {
    private const val DIR = "/app/southbound/core/states/"

    private val outlinesOnce = lazy { StateOutlines.parse(read("state-outlines.geojson")) }
    private val cardsOnce = lazy { StateCards.parse(read("state-cards.json")) }

    /** The state outlines, parsed once. Blocking on the first call. */
    fun outlines(): StateOutlines = outlinesOnce.value

    /** The cards, parsed once. Blocking on the first call. */
    fun cards(): StateCards = cardsOnce.value

    internal fun read(name: String): String {
        val stream = StateData::class.java.getResourceAsStream(DIR + name) ?: throw IllegalStateException("missing resource $name")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }
}
