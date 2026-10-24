package app.southbound.core.map

/**
 * One colour per Road pack category, shared by the map's badges and the badges on the list and the sheet, so a colour
 * means the same thing everywhere. The keys are the pack's `category` codes (core:road's `RoadCategory.code`; a test in
 * the explore module keeps the two lists in step). Kept apart from the Wi-Fi tiers' colours and from the plate
 * readers', speed cameras' and traffic cameras'.
 */
object RoadColors {
    /** The pack's category codes, in the pipeline's order. */
    val KEYS: List<String> = listOf(
        "camp", "rv", "rest", "dump", "water", "toilet", "shower", "fuel", "truck", "laundry", "library", "pharmacy",
        "hospital", "clinic", "grocery", "park",
    )

    private val HEX = mapOf(
        "camp" to "#2E7D32",
        "rv" to "#00796B",
        "rest" to "#546E7A",
        "dump" to "#6D4C41",
        "water" to "#1976D2",
        "toilet" to "#5E35B1",
        "shower" to "#0288D1",
        "fuel" to "#E65100",
        "truck" to "#BF360C",
        "laundry" to "#8E24AA",
        "library" to "#3949AB",
        "pharmacy" to "#C62828",
        "hospital" to "#B71C1C",
        "clinic" to "#D81B60",
        "grocery" to "#9E9D24",
        "park" to "#33691E",
    )

    /** The circles for the grid cells below the zoom of single spots: one slate for all, with the count over them. */
    const val CLUSTER = "#37474F"

    /** "#RRGGBB" for [category]; the slate for a code this app doesn't know. */
    fun hex(category: String): String = HEX[category] ?: CLUSTER

    /** [hex] as an opaque ARGB int, for Canvas and Compose. */
    fun argb(category: String): Int = argbOf(hex(category))

    /** [CLUSTER] as an opaque ARGB int. */
    val clusterArgb: Int get() = argbOf(CLUSTER)

    private fun argbOf(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
}
