package app.southbound.core.packs

/**
 * What Southbound has for each covered state, and what it lacks and why: the list Settings > What each state has shows,
 * so a missing feed is a stated fact on the phone rather than an empty map. Kept by hand beside the readers it
 * describes (core/traffic for traffic, pipeline/search/dot_cameras.py for "Avoid DOT cameras", whose list of states
 * `CoverageTest` checks against this one). Each item has a [default] that holds wherever a state says nothing else.
 */
object Coverage {
    /** What a state can have, in the order Settings lists it. */
    enum class Item(val label: String) {
        MAPS("Map, directions and search"),
        PLACES("Businesses and house numbers in search"),
        WEATHER("Weather"),
        INCIDENTS("Incidents and closures"),
        CAMERAS("Traffic cameras"),
        SIGNS("Message signs"),
        AVOID_DOT_CAMERAS("Avoiding DOT cameras"),
        SPEEDS("Traffic speeds"),
        TRANSIT("Transit"),
        GAS_PRICE("Gas price"),
    }

    enum class Level { FULL, PARTIAL, NONE }

    /** How far a state has an [Item], and why when not fully. */
    data class Entry(val item: Item, val level: Level, val why: String? = null)

    /** One state (or DC): its code, its name as the region picker heads it, its traffic feed's name, and what differs. */
    class State(val code: String, val name: String, val trafficFeed: String?, exceptions: List<Entry>) {
        /** Every item for this state: its own entry where it has one, else the item's [default]. */
        val entries: List<Entry> = Item.entries.map { item -> exceptions.firstOrNull { it.item == item } ?: default(item) }

        fun entry(item: Item): Entry = entries.first { it.item == item }

        /** What this state lacks beyond what most states lack ([common]): the gaps Settings names under it. */
        val ownGaps: List<Entry> = entries.filter { it.level != Level.FULL && it != default(it.item) && it.level >= default(it.item).level }

        /** Where this state has more than most (New York's speeds and transit, a regional gas price). */
        val extras: List<Entry> = entries.filter { it.level < default(it.item).level }
    }

    /** Each item as it is wherever a state says nothing else. */
    fun default(item: Item): Entry = when (item) {
        Item.PLACES -> Entry(item, Level.PARTIAL, "OpenStreetMap's alone outside New York, so fewer than there")
        Item.SPEEDS -> Entry(item, Level.NONE, "New York City only; the other states' speed feeds aren't read yet")
        Item.TRANSIT -> Entry(item, Level.NONE, "New York City's subway and buses only")
        Item.GAS_PRICE -> Entry(item, Level.PARTIAL, "the US average outside New York and the Central Atlantic states (choose US under Fuel)")
        else -> Entry(item, Level.FULL)
    }

    private const val NJ_WHY = "511NJ refuses GitHub's servers, where the app's feeds are studied and its maps built"
    private const val DC_WHY = "DC runs no 511 and publishes no traffic feed or camera list"
    private const val MO_WHY = "MoDOT's traveler map publishes no feed the app can read"
    private const val OHGO_WHY = "only with your free OHGO key (Settings > Live data)"

    /** EIA's Central Atlantic average (New York, New Jersey, Pennsylvania, Maryland, Delaware and DC), the NJ choice. */
    private val CENTRAL_ATLANTIC = Entry(Item.GAS_PRICE, Level.FULL, "the Central Atlantic average (choose NJ under Fuel)")

    private fun none(why: String, vararg items: Item) = items.map { Entry(it, Level.NONE, why) }

    private val TRAFFIC = arrayOf(Item.INCIDENTS, Item.CAMERAS, Item.SIGNS, Item.AVOID_DOT_CAMERAS)

    /** Every covered state, in the region picker's order (DC after Delaware, as Maryland's region holds both). */
    val STATES: List<State> = listOf(
        State(
            "NY", "New York", "511NY",
            listOf(
                Entry(Item.PLACES, Level.FULL, "the state's address points and Overture's places beside OpenStreetMap's"),
                Entry(Item.SPEEDS, Level.PARTIAL, "New York City's (NYC DOT)"),
                Entry(Item.TRANSIT, Level.PARTIAL, "New York City's subway and buses"),
                Entry(Item.GAS_PRICE, Level.FULL, "New York's own average (choose NY under Fuel)"),
            ),
        ),
        State(
            "NJ", "New Jersey", null,
            none(NJ_WHY, *TRAFFIC) + listOf(
                Entry(Item.PLACES, Level.PARTIAL, "Northern New Jersey has Overture's places; house numbers from OpenStreetMap alone"),
                CENTRAL_ATLANTIC,
            ),
        ),
        State("CT", "Connecticut", "CTroads", emptyList()),
        State("PA", "Pennsylvania", "511PA", listOf(CENTRAL_ATLANTIC)),
        State("MD", "Maryland", "CHART", listOf(CENTRAL_ATLANTIC)),
        State("DE", "Delaware", "DelDOT", listOf(CENTRAL_ATLANTIC)),
        State("DC", "DC", null, none(DC_WHY, *TRAFFIC) + CENTRAL_ATLANTIC),
        State(
            "OH", "Ohio", "OHGO",
            listOf(Item.INCIDENTS, Item.CAMERAS, Item.SIGNS).map { Entry(it, Level.PARTIAL, OHGO_WHY) } +
                Entry(Item.AVOID_DOT_CAMERAS, Level.NONE, "OHGO's camera list needs a key, and the maps are built without one"),
        ),
        State("IN", "Indiana", "511IN", emptyList()),
        State("IL", "Illinois", "Travel Midwest", emptyList()),
        State("MI", "Michigan", "Mi Drive", none("Mi Drive gives each sign's words on a page of its own, one request per sign", Item.SIGNS)),
        State("WI", "Wisconsin", "511WI", emptyList()),
        State("MN", "Minnesota", "511MN", emptyList()),
        State("IA", "Iowa", "511IA", emptyList()),
        State("MO", "Missouri", null, none(MO_WHY, *TRAFFIC)),
        State("ND", "North Dakota", "NDDOT", none("NDDOT publishes no message signs", Item.SIGNS)),
        State("SD", "South Dakota", "SD511", none("SD511 publishes no message signs", Item.SIGNS)),
        State("NE", "Nebraska", "Nebraska 511", emptyList()),
        State("KS", "Kansas", "KanDrive", emptyList()),
    )

    fun of(code: String): State? = STATES.firstOrNull { it.code == code }

    /** The lines over the list: what every state has, then what most lack and why. */
    fun common(): List<String> {
        val everywhere = Item.entries.filter { item -> STATES.all { it.entry(item).level == Level.FULL } }.map { it.label.lowercaseFirst() }
        val most = Item.entries.filter { default(it).level != Level.FULL }
        return listOf(
            "Every state: ${everywhere.joinToString("; ")}.",
            "Plate readers and speed cameras: OpenStreetMap's in every state, as complete as its mappers made them.",
        ) + most.map { item -> "${item.label}: ${default(item).why}." }
    }

    /**
     * A state's own lines for Settings: its traffic feed, then each of its gaps grouped by reason ("No incidents and
     * closures, traffic cameras, message signs or avoiding DOT cameras: 511NJ refuses ..."), then where it has more.
     */
    fun lines(state: State): List<String> {
        val out = ArrayList<String>()
        state.trafficFeed?.let { out += "Traffic from $it." }
        // What is missing outright first, then what is there in part.
        val byWhy = state.ownGaps.sortedByDescending { it.level }.groupBy { it.level to it.why }
        for ((key, entries) in byWhy) {
            val (level, why) = key
            val items = entries.map { it.item.label.lowercaseFirst() }
            out += when (level) {
                Level.NONE -> "No ${join(items, "or")}: $why."
                else -> "${join(items).replaceFirstChar { it.uppercase() }}: $why."
            }
        }
        if (state.ownGaps.isEmpty()) out += "Nothing missing beyond what most states lack."
        for (extra in state.extras) out += "${extra.item.label}: ${extra.why}."
        return out
    }

    /**
     * The gaps at a glance, for the collapsed list: which states have no traffic feed, which need a key, which can't
     * avoid DOT cameras, which have no message signs.
     */
    fun summary(): List<String> {
        fun names(pick: (State) -> Boolean) = join(STATES.filter(pick).map { it.name })
        val noFeed = names { it.trafficFeed == null }
        val keyed = names { it.trafficFeed != null && it.entry(Item.INCIDENTS).level == Level.PARTIAL }
        val noAvoid = names { it.entry(Item.AVOID_DOT_CAMERAS).level == Level.NONE }
        val noSigns = names { it.trafficFeed != null && it.entry(Item.SIGNS).level == Level.NONE }
        return listOfNotNull(
            noFeed.takeIf { it.isNotEmpty() }?.let { "No traffic feed: $it." },
            keyed.takeIf { it.isNotEmpty() }?.let { "Traffic only with your own key: $it." },
            noAvoid.takeIf { it.isNotEmpty() }?.let { "No avoiding DOT cameras: $it." },
            noSigns.takeIf { it.isNotEmpty() }?.let { "No message signs: $it." },
            "Traffic speeds and transit: New York City only.",
        )
    }

    /**
     * A line for the region picker under a heading or a one-region state ("Maryland, Delaware and DC" -> "No traffic
     * feed for DC"), or null when every state under it has its traffic.
     */
    /**
     * The states (and DC) a heading of the region picker ([Regions.byState]) covers: "Maryland, Delaware and DC"
     * is three.
     */
    fun statesIn(heading: String): List<State> = STATES.filter { it.name in namesIn(heading) }

    /** The state names a picker heading lists, whole: "Maryland, Delaware and DC" is three, "West Virginia" not Virginia's. */
    fun namesIn(heading: String): List<String> = heading.split(", ", " and ").map { it.trim() }

    /**
     * The states (and DC) the phone has map regions for, in [STATES] order: Settings > Live data lists their
     * traffic feeds first. Maryland's region brings Delaware and DC along.
     */
    fun statesWithRegions(regionIds: Set<String>): List<State> {
        val headings = Regions.byState.filter { (_, regions) -> regions.any { it.id in regionIds } }.map { it.first }
        return STATES.filter { state -> headings.any { state.name in namesIn(it) } }
    }

    fun pickerNote(heading: String): String? {
        val states = statesIn(heading)
        val noFeed = states.filter { it.trafficFeed == null }
        val keyed = states.filter { it.trafficFeed != null && it.entry(Item.INCIDENTS).level == Level.PARTIAL }
        return when {
            noFeed.isNotEmpty() && noFeed.size == states.size -> "No traffic feed (Settings > What each state has)"
            noFeed.isNotEmpty() -> "No traffic feed for ${join(noFeed.map { it.name })} (Settings > What each state has)"
            keyed.isNotEmpty() -> "Traffic needs your free ${keyed.joinToString { it.trafficFeed!! }} key (Settings > Live data)"
            else -> null
        }
    }

    private fun String.lowercaseFirst(): String = replaceFirstChar { it.lowercase() }

    private fun join(items: List<String>, last: String = "and"): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " $last " + items.last()
    }
}
