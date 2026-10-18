package app.southbound.core.wifi

/**
 * The kinds of place the map's Wi-Fi chooser offers ("Cafés and food", "Libraries", ...), from a spot's category.
 * The pack stores the OSM value without its key ("cafe", "supermarket"), so the shops are an explicit list; anything
 * that is in no list is [OTHER]. The declaration order is the chooser's order.
 */
enum class WifiKind {
    CAFES_FOOD,
    SHOPS,
    LIBRARIES,
    HOTELS,
    REST_AREAS,
    FUEL,
    OTHER,
    ;

    companion object {
        private val FOOD = setOf("cafe", "fast_food", "restaurant", "food_court", "coffee_shop")

        /** The shop values the chains and the commonest tagged Wi-Fi shops carry; a shop not listed here is [OTHER]. */
        private val SHOP = setOf(
            "supermarket", "department_store", "electronics", "doityourself", "mobile_phone", "computer", "convenience",
            "mall", "variety_store", "general", "hardware", "clothes", "shoes", "books", "stationery", "furniture",
            "gift", "chemist", "wholesale", "car", "bicycle", "sports", "toys", "copyshop",
        )
        private val HOTEL = setOf("hotel", "motel")
        private val REST = setOf("rest_area", "welcome_center", "visitor_centre", "information")

        /** The kind of a spot of this [category] (compared in lower case; `*_restaurant` is food too). */
        fun of(category: String): WifiKind {
            val c = category.trim().lowercase()
            return when {
                c in FOOD || c.endsWith("_restaurant") -> CAFES_FOOD
                c in SHOP -> SHOPS
                c == "library" -> LIBRARIES
                c in HOTEL -> HOTELS
                c in REST -> REST_AREAS
                c == "fuel" -> FUEL
                else -> OTHER
            }
        }
    }
}

/**
 * What the Wi-Fi button's chooser ("Show on the map") leaves on the map: the [tiers] shown, with [customersOnly] only
 * the tagged-free spots that are free for customers (fee "customers"; tier 1 must be among [tiers] too), and the
 * place [kinds] (null = every kind). The default is everything. It is view state: nothing here is stored or exported.
 */
data class WifiFilter(
    val tiers: Set<WifiTier> = WifiTier.entries.toSet(),
    val customersOnly: Boolean = false,
    val kinds: Set<WifiKind>? = null,
) {
    /** True when this shows everything the pack has, which the map draws from the pack's own grid cells. */
    val isDefault: Boolean
        get() = tiers.size >= WifiTier.entries.size && !customersOnly && (kinds == null || kinds.size >= WifiKind.entries.size)

    /** True when no spot can pass: no tier is ticked, or customers only without tagged free. */
    val showsNothing: Boolean
        get() = tiers.isEmpty() || (customersOnly && WifiTier.TAGGED_FREE !in tiers) || kinds?.isEmpty() == true

    /** The kind part alone, for spots the pack query has already cut by tier and fee. */
    fun keepsKind(spot: WifiSpot): Boolean = kinds == null || WifiKind.of(spot.category) in kinds

    /** Whether [spot] passes the whole filter. */
    fun matches(spot: WifiSpot): Boolean =
        spot.tier in tiers && (!customersOnly || spot.freeForCustomers) && keepsKind(spot)

    /** [spots] cut by the kind only; the pack's query has done the tiers and the fee. */
    fun applyKinds(spots: List<WifiSpot>): List<WifiSpot> = if (kinds == null) spots else spots.filter(::keepsKind)

    /** [tier] ticked or unticked. */
    fun withTier(tier: WifiTier, on: Boolean): WifiFilter = copy(tiers = if (on) tiers + tier else tiers - tier)

    /** A kind chip tapped: from "All" it picks that kind alone; the last one off, or every one on, is "All" again. */
    fun toggleKind(kind: WifiKind): WifiFilter {
        val now = kinds ?: return copy(kinds = setOf(kind))
        val next = if (kind in now) now - kind else now + kind
        return copy(kinds = next.takeUnless { it.isEmpty() || it.size >= WifiKind.entries.size })
    }

    /** One line for a saved state (rotation): tier codes, the customers switch and the kind names, or `*` for all. */
    fun encode(): String =
        tiers.map { it.code }.sorted().joinToString(",") + "|" + (if (customersOnly) "1" else "0") + "|" +
            (kinds?.map { it.name }?.sorted()?.joinToString(",") ?: "*")

    companion object {
        val DEFAULT = WifiFilter()

        /** What [encode] wrote; anything unreadable is the default, a name this app doesn't know is dropped. */
        fun decode(text: String?): WifiFilter {
            val parts = text?.split("|") ?: return DEFAULT
            if (parts.size != 3) return DEFAULT
            val tiers = parts[0].split(",").mapNotNull { it.toIntOrNull()?.let(WifiTier::of) }.toSet()
            val kinds = if (parts[2] == "*") {
                null
            } else {
                parts[2].split(",").mapNotNull { name -> WifiKind.entries.firstOrNull { it.name == name } }.toSet()
                    .takeUnless { it.isEmpty() || it.size >= WifiKind.entries.size }
            }
            return WifiFilter(tiers, parts[1] == "1", kinds)
        }
    }
}

/** Grid cells worked out on the phone, for the map's low zooms when a filter hides some of the pack's spots. */
object WifiClusters {
    /**
     * The cells of [zoom] that [spots] fall in, by the same tile maths as the pack's `cluster` table
     * ([WifiTiles.tileOf]): per cell the count, the mean position (six decimals) and the best tier. Ordered by column, then row, so
     * the same spots always give the same list. No spots, no cells.
     */
    fun fromSpots(spots: List<WifiSpot>, zoom: Int): List<WifiCluster> {
        if (spots.isEmpty()) return emptyList()
        val cells = HashMap<Long, Cell>()
        for (spot in spots) {
            val tile = WifiTiles.tileOf(spot.lat, spot.lon, zoom)
            val cell = cells.getOrPut(tile.x.toLong() shl 32 or tile.y.toLong()) { Cell(tile.x, tile.y) }
            cell.count++
            cell.latSum += spot.lat
            cell.lonSum += spot.lon
            if (spot.tier.code < cell.best.code) cell.best = spot.tier
        }
        return cells.values.sortedWith(compareBy({ it.x }, { it.y })).map {
            WifiCluster(zoom, it.x, it.y, it.count, round6(it.latSum / it.count), round6(it.lonSum / it.count), it.best)
        }
    }

    /** To six decimals, as the pack's cells are, so the same spots in any order give the same position. */
    private fun round6(degrees: Double): Double = Math.round(degrees * 1_000_000.0) / 1_000_000.0

    private class Cell(val x: Int, val y: Int) {
        var count = 0
        var latSum = 0.0
        var lonSum = 0.0
        var best = WifiTier.LIKELY
    }
}
