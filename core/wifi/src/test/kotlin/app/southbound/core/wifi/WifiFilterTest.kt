package app.southbound.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiFilterTest {

    private fun spot(
        id: Long,
        category: String = "cafe",
        tier: WifiTier = WifiTier.TAGGED_FREE,
        fee: WifiFee = WifiFee.NO,
        lat: Double = 40.0,
        lon: Double = -95.0,
    ) = WifiSpot(id, "n", id, "Place $id", null, lat, lon, category, tier, fee, null, null)

    @Test
    fun `categories map to their kinds`() {
        for (c in listOf("cafe", "fast_food", "restaurant", "food_court", "coffee_shop", "Cafe", "pizza_restaurant", "thai_restaurant")) {
            assertEquals(c, WifiKind.CAFES_FOOD, WifiKind.of(c))
        }
        for (c in listOf("supermarket", "department_store", "electronics", "doityourself", "mobile_phone", "computer", "convenience", "mall")) {
            assertEquals(c, WifiKind.SHOPS, WifiKind.of(c))
        }
        assertEquals(WifiKind.LIBRARIES, WifiKind.of("library"))
        assertEquals(WifiKind.HOTELS, WifiKind.of("hotel"))
        assertEquals(WifiKind.HOTELS, WifiKind.of("motel"))
        for (c in listOf("rest_area", "welcome_center", "visitor_centre", "information")) {
            assertEquals(c, WifiKind.REST_AREAS, WifiKind.of(c))
        }
        assertEquals(WifiKind.FUEL, WifiKind.of("fuel"))
    }

    @Test
    fun `everything else is other`() {
        for (c in listOf("station", "aerodrome", "park", "pub", "museum", "building", "", "restaurants", "restaurant_row")) {
            assertEquals(c, WifiKind.OTHER, WifiKind.of(c))
        }
    }

    @Test
    fun `the tier 4 categories the pipeline gives land in a kind of their own`() {
        // WifiLikelyGroup is the sheet's wording; the chooser's kinds agree with it for the same categories.
        assertEquals(WifiKind.LIBRARIES, WifiKind.of("library"))
        assertEquals(WifiLikelyGroup.LIBRARIES, WifiLikelyGroup.of("library"))
        assertEquals(WifiKind.REST_AREAS, WifiKind.of("welcome_center"))
        assertEquals(WifiKind.HOTELS, WifiKind.of("motel"))
    }

    @Test
    fun `the default shows everything`() {
        val f = WifiFilter()
        assertTrue(f.isDefault)
        assertFalse(f.showsNothing)
        assertEquals(WifiFilter.DEFAULT, f)
        val spots = listOf(
            spot(1, "cafe"), spot(2, "library", WifiTier.LIKELY), spot(3, "weird", WifiTier.CHAIN, WifiFee.CUSTOMERS),
            spot(4, "fuel", WifiTier.TAGGED_UNSTATED, WifiFee.UNKNOWN),
        )
        assertEquals(spots, spots.filter(f::matches))
        assertEquals(spots, f.applyKinds(spots))
    }

    @Test
    fun `any change from everything is not the default`() {
        assertFalse(WifiFilter().withTier(WifiTier.LIKELY, false).isDefault)
        assertFalse(WifiFilter(customersOnly = true).isDefault)
        assertFalse(WifiFilter(kinds = setOf(WifiKind.LIBRARIES)).isDefault)
        // Ticking a tier back, or choosing every kind, is everything again.
        assertTrue(WifiFilter().withTier(WifiTier.LIKELY, false).withTier(WifiTier.LIKELY, true).isDefault)
        assertTrue(WifiFilter(kinds = WifiKind.entries.toSet()).isDefault)
    }

    @Test
    fun `tiers cut by confidence`() {
        val f = WifiFilter().withTier(WifiTier.LIKELY, false)
        assertTrue(f.matches(spot(1, tier = WifiTier.CHAIN)))
        assertFalse(f.matches(spot(2, "hotel", WifiTier.LIKELY)))
        assertTrue(WifiFilter(tiers = emptySet()).showsNothing)
        assertFalse(WifiFilter(tiers = emptySet()).matches(spot(3)))
    }

    @Test
    fun `customers only keeps tagged free spots with the customers fee and no chain`() {
        val f = WifiFilter(customersOnly = true)
        assertTrue(f.matches(spot(1, tier = WifiTier.TAGGED_FREE, fee = WifiFee.CUSTOMERS)))
        assertFalse(f.matches(spot(2, tier = WifiTier.TAGGED_FREE, fee = WifiFee.NO)))
        // A chain's customers-only policy is not an OSM tag.
        assertFalse(f.matches(spot(3, tier = WifiTier.CHAIN, fee = WifiFee.CUSTOMERS)))
        assertFalse(f.matches(spot(4, tier = WifiTier.TAGGED_UNSTATED, fee = WifiFee.CUSTOMERS)))
        // Without tagged free ticked there is nothing to show.
        val noFree = f.withTier(WifiTier.TAGGED_FREE, false)
        assertTrue(noFree.showsNothing)
        assertFalse(noFree.matches(spot(1, tier = WifiTier.TAGGED_FREE, fee = WifiFee.CUSTOMERS)))
    }

    @Test
    fun `kinds cut by the category and the other bucket catches the rest`() {
        val spots = listOf(spot(1, "cafe"), spot(2, "supermarket"), spot(3, "library"), spot(4, "station"), spot(5, "hotel"))
        assertEquals(listOf(3L), WifiFilter(kinds = setOf(WifiKind.LIBRARIES)).applyKinds(spots).map { it.id })
        assertEquals(listOf(1L, 2L), WifiFilter(kinds = setOf(WifiKind.CAFES_FOOD, WifiKind.SHOPS)).applyKinds(spots).map { it.id })
        assertEquals(listOf(4L), WifiFilter(kinds = setOf(WifiKind.OTHER)).applyKinds(spots).map { it.id })
        assertTrue(WifiFilter(kinds = emptySet()).applyKinds(spots).isEmpty())
        assertTrue(WifiFilter(kinds = emptySet()).showsNothing)
    }

    @Test
    fun `the whole filter is the three parts together`() {
        val f = WifiFilter(setOf(WifiTier.TAGGED_FREE, WifiTier.CHAIN), customersOnly = false, kinds = setOf(WifiKind.CAFES_FOOD))
        assertTrue(f.matches(spot(1, "cafe", WifiTier.CHAIN)))
        assertFalse(f.matches(spot(2, "cafe", WifiTier.TAGGED_UNSTATED)))
        assertFalse(f.matches(spot(3, "library", WifiTier.TAGGED_FREE)))
    }

    @Test
    fun `a kind chip from all picks that kind, and the last off or every one on is all again`() {
        val one = WifiFilter().toggleKind(WifiKind.SHOPS)
        assertEquals(setOf(WifiKind.SHOPS), one.kinds)
        val two = one.toggleKind(WifiKind.LIBRARIES)
        assertEquals(setOf(WifiKind.SHOPS, WifiKind.LIBRARIES), two.kinds)
        assertEquals(setOf(WifiKind.LIBRARIES), two.toggleKind(WifiKind.SHOPS).kinds)
        assertNull(one.toggleKind(WifiKind.SHOPS).kinds)
        var all = WifiFilter()
        for (kind in WifiKind.entries) all = all.toggleKind(kind)
        assertNull(all.kinds)
        assertTrue(all.isDefault)
    }

    @Test
    fun `the saved text round trips every filter`() {
        val filters = listOf(
            WifiFilter(),
            WifiFilter(tiers = emptySet()),
            WifiFilter(setOf(WifiTier.TAGGED_FREE, WifiTier.LIKELY), true, setOf(WifiKind.FUEL, WifiKind.CAFES_FOOD)),
            WifiFilter(setOf(WifiTier.CHAIN), false, null),
        )
        for (f in filters) assertEquals(f, WifiFilter.decode(f.encode()))
        assertEquals("1,2,3,4|0|*", WifiFilter().encode())
    }

    @Test
    fun `unreadable saved text is the default and unknown names are dropped`() {
        assertEquals(WifiFilter.DEFAULT, WifiFilter.decode(null))
        assertEquals(WifiFilter.DEFAULT, WifiFilter.decode(""))
        assertEquals(WifiFilter.DEFAULT, WifiFilter.decode("garbage"))
        assertEquals(WifiFilter.DEFAULT, WifiFilter.decode("1,2|0"))
        val f = WifiFilter.decode("1,9,x|1|FUEL,GONE")
        assertEquals(setOf(WifiTier.TAGGED_FREE), f.tiers)
        assertTrue(f.customersOnly)
        assertEquals(setOf(WifiKind.FUEL), f.kinds)
        // Only unknown kinds left: all kinds, not none.
        assertNull(WifiFilter.decode("1|0|GONE").kinds)
    }
}
