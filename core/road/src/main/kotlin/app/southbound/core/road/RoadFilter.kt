package app.southbound.core.road

/**
 * Which categories the Road layer and the Road list show: a set of [RoadCategory]. The default is the places a road
 * trip needs every day (camp, rv, rest, dump, water, fuel, truck); the rest are off until the chooser turns them on.
 * It is view state: nothing here is stored or exported.
 */
data class RoadFilter(val categories: Set<RoadCategory> = RoadCategory.DEFAULT_ON) {

    /** True when this is the set a fresh map starts with. */
    val isDefault: Boolean get() = categories == RoadCategory.DEFAULT_ON

    /** True when no category is ticked: nothing can pass. */
    val showsNothing: Boolean get() = categories.isEmpty()

    fun matches(category: RoadCategory): Boolean = category in categories

    /** [category] ticked or unticked. */
    fun with(category: RoadCategory, on: Boolean): RoadFilter =
        copy(categories = if (on) categories + category else categories - category)

    fun toggle(category: RoadCategory): RoadFilter = with(category, category !in categories)

    /** One line for a saved state (rotation): the codes in the table's order, comma separated; empty for none. */
    fun encode(): String = RoadCategory.entries.filter { it in categories }.joinToString(",") { it.code }

    companion object {
        val DEFAULT = RoadFilter()

        /** Every category on. */
        val ALL = RoadFilter(RoadCategory.entries.toSet())

        /**
         * What [encode] wrote. A code this app doesn't know is dropped; a line with no code in it at all (damage, or
         * only codes of a newer app) is the default. The empty line is the empty set (the chooser can hold it while
         * it is open), not the default.
         */
        fun decode(text: String?): RoadFilter {
            if (text == null) return DEFAULT
            if (text.isEmpty()) return RoadFilter(emptySet())
            val known = text.split(",").mapNotNull { RoadCategory.of(it) }.toSet()
            return if (known.isEmpty()) DEFAULT else RoadFilter(known)
        }
    }
}
