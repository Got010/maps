package app.southbound.core.map

/**
 * One colour per Wi-Fi tier, shared by the map's badges and clusters and the badges on the list and the sheet, so a
 * colour means the same thing everywhere. Tiers are the Wi-Fi pack's: 1 tagged free, 2 tagged with the fee not
 * stated, 3 chain policy, 4 likely. Apart from the plate readers' purple, the speed cameras' blue and the traffic
 * cameras' teal.
 */
object WifiColors {
    const val TAGGED_FREE = "#1E8E3E"
    const val TAGGED_UNSTATED = "#0097A7"
    const val CHAIN = "#1565C0"
    const val LIKELY = "#607D8B"

    /**
     * The gold of the ring round a tagged-free spot that is free for customers only, and of the sheet's "Free for
     * customers" badge. An amber gold that reads on both the light and the dark map and is far from tier 1's green.
     */
    const val CUSTOMERS_GOLD = "#F9AB00"

    /** "#RRGGBB" for [tier]; the weakest tier's for a number outside 1 to 4. */
    fun hex(tier: Int): String = when (tier) {
        1 -> TAGGED_FREE
        2 -> TAGGED_UNSTATED
        3 -> CHAIN
        else -> LIKELY
    }

    /** [CUSTOMERS_GOLD] as an opaque ARGB int, for Compose. */
    val customersArgb: Int get() = (0xFF000000L or CUSTOMERS_GOLD.removePrefix("#").toLong(16)).toInt()

    /** [hex] as an opaque ARGB int, for Canvas and Compose. */
    fun argb(tier: Int): Int = (0xFF000000L or hex(tier).removePrefix("#").toLong(16)).toInt()
}
