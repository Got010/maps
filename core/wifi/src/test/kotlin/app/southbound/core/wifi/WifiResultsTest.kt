package app.southbound.core.wifi

import app.southbound.core.search.LatLon
import app.southbound.core.search.PlaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WifiResultsTest {

    // A made-up point in open country; 0.001 degrees of latitude is about 111 m.
    private val here = LatLon(39.5, -95.5)

    // A Wednesday.
    private val noon = LocalDateTime.of(2026, 10, 14, 12, 0)
    private val night = LocalDateTime.of(2026, 10, 14, 23, 30)

    private fun spot(
        id: Long,
        dLat: Double,
        tier: WifiTier = WifiTier.TAGGED_FREE,
        hours: String? = null,
        category: String = "cafe",
        name: String? = "Spot $id",
    ) = WifiSpot(
        id = id, osmType = "n", osmId = 1000 + id, name = name, brandWikidata = null,
        lat = here.lat + dLat, lon = here.lon, category = category, tier = tier, fee = WifiFee.UNKNOWN,
        openingHours = hours, website = null,
    )

    private fun ids(r: List<WifiResult>) = r.map { it.spot.id }

    private fun select(
        spots: List<WifiSpot>,
        radius: Double = 5000.0,
        tiers: Set<WifiTier> = WifiTier.entries.toSet(),
        openNow: Boolean = false,
        now: LocalDateTime = noon,
        unknown: Boolean = true,
        limit: Int = 40,
    ) = WifiResults.select(spots, here, radius, tiers, openNow, now, unknown, limit)

    @Test
    fun `sorts nearest first and keeps the circle`() {
        val spots = listOf(spot(1, 0.002), spot(2, 0.0005), spot(3, 0.004), spot(4, -0.001), spot(5, 0.05))
        val found = select(spots, radius = 600.0)
        assertEquals(listOf(2L, 4L, 1L, 3L), ids(found))
        assertEquals(55.6, found[0].distanceMeters, 1.0)
        assertEquals(listOf(2L, 4L), ids(select(spots, radius = 300.0, limit = 5)).take(2))
        assertEquals(listOf(2L), ids(select(spots, radius = 600.0, limit = 1)))
    }

    @Test
    fun `a tier filter keeps only those tiers`() {
        val spots = listOf(
            spot(1, 0.001, WifiTier.TAGGED_FREE), spot(2, 0.002, WifiTier.TAGGED_UNSTATED),
            spot(3, 0.003, WifiTier.CHAIN), spot(4, 0.004, WifiTier.LIKELY),
        )
        assertEquals(listOf(1L, 2L), ids(select(spots, tiers = WifiTierFilter.TAGGED.tiers)))
        assertEquals(listOf(3L), ids(select(spots, tiers = WifiTierFilter.CHAIN.tiers)))
        assertEquals(listOf(4L), ids(select(spots, tiers = WifiTierFilter.LIKELY.tiers)))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(select(spots, tiers = WifiTierFilter.ALL.tiers)))
        assertEquals(emptyList<Long>(), ids(select(spots, tiers = emptySet())))
    }

    @Test
    fun `open now keeps the open and, by default, the unknown`() {
        val spots = listOf(
            spot(1, 0.001, hours = "Mo-Su 08:00-20:00"), // open at noon
            spot(2, 0.002, hours = "Mo-Su 14:00-20:00"), // closed at noon
            spot(3, 0.003, hours = null), // unknown
            spot(4, 0.004, hours = "sunrise-sunset"), // can't be read: unknown
            spot(5, 0.005, hours = "24/7"),
        )
        assertEquals(listOf(1L, 3L, 4L, 5L), ids(select(spots, openNow = true)))
        assertEquals(listOf(1L, 5L), ids(select(spots, openNow = true, unknown = false)))
        // Off: every spot, closed ones too.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(select(spots, openNow = false)))
        // The unknown flag does nothing without the filter.
        assertEquals(5, select(spots, openNow = false, unknown = false).size)
    }

    @Test
    fun `open now follows the clock`() {
        val spots = listOf(spot(1, 0.001, hours = "Mo-Su 08:00-20:00"), spot(2, 0.002, hours = "Mo-Su 18:00-02:00"))
        assertEquals(listOf(1L), ids(select(spots, openNow = true, unknown = false, now = noon)))
        assertEquals(listOf(2L), ids(select(spots, openNow = true, unknown = false, now = night)))
    }

    @Test
    fun `ties go to the better tier, then the lower id`() {
        val spots = listOf(
            spot(9, 0.001, WifiTier.LIKELY), spot(7, 0.001, WifiTier.CHAIN), spot(8, 0.001, WifiTier.CHAIN),
            spot(6, 0.001, WifiTier.TAGGED_FREE),
        )
        assertEquals(listOf(6L, 7L, 8L, 9L), ids(select(spots)))
    }

    @Test
    fun `a limit of zero or less gives nothing`() {
        assertEquals(emptyList<WifiResult>(), select(listOf(spot(1, 0.001)), limit = 0))
        assertEquals(emptyList<WifiResult>(), select(listOf(spot(1, 0.001)), limit = -3))
    }

    @Test
    fun `more rows are read when open now will drop some`() {
        assertEquals(80, WifiResults.candidateRows(40, openNow = false))
        assertEquals(1_000, WifiResults.candidateRows(40, openNow = true))
        assertEquals(2_500, WifiResults.candidateRows(100, openNow = true))
    }

    @Test
    fun `only tagged free is confirmed`() {
        assertEquals(listOf(WifiTier.TAGGED_FREE), WifiTier.entries.filter { it.confirmed })
        assertEquals(listOf(1, 2, 3, 4), WifiTier.entries.map { it.code })
        assertEquals(WifiTier.CHAIN, WifiTier.of(3))
        assertNull(WifiTier.of(0))
        assertNull(WifiTier.of(5))
    }

    @Test
    fun `fees read the pack's words and anything else is unknown`() {
        assertEquals(WifiFee.NO, WifiFee.of("no"))
        assertEquals(WifiFee.CUSTOMERS, WifiFee.of("customers"))
        assertEquals(WifiFee.UNKNOWN, WifiFee.of("unknown"))
        assertEquals(WifiFee.UNKNOWN, WifiFee.of("yes"))
        assertEquals(WifiFee.UNKNOWN, WifiFee.of(null))
    }

    @Test
    fun `tier filters name the tiers they cover`() {
        assertEquals(setOf(WifiTier.TAGGED_FREE, WifiTier.TAGGED_UNSTATED), WifiTierFilter.TAGGED.tiers)
        assertEquals(WifiTier.entries.toSet(), WifiTierFilter.ALL.tiers)
        // Every tier is in exactly one of the three narrower filters.
        val narrower = listOf(WifiTierFilter.TAGGED, WifiTierFilter.CHAIN, WifiTierFilter.LIKELY).flatMap { it.tiers }
        assertEquals(WifiTier.entries.toSet(), narrower.toSet())
        assertEquals(narrower.size, narrower.toSet().size)
    }

    @Test
    fun `a spot becomes a place for the card with its OSM reference`() {
        val s = spot(12, 0.0, hours = "Mo-Fr 09:00-17:00", category = "library").copy(osmType = "w", osmId = 456, website = "https://example.org")
        assertEquals("w456", s.osmRef)
        val p = s.toPlace()
        assertEquals(12L, p.id)
        assertEquals(PlaceKind.PLACE, p.kind)
        assertEquals("Spot 12", p.name)
        assertEquals("library", p.category)
        assertEquals("Mo-Fr 09:00-17:00", p.openingHours)
        assertEquals("https://example.org", p.website)
        assertEquals("w456", p.osmRef)
        assertEquals(here.lat, p.lat, 0.0)
        assertNull(p.address)
    }

    @Test
    fun `free for customers is an OSM-tagged fee only, never a chain's policy`() {
        val customers = spot(1, 0.0).copy(fee = WifiFee.CUSTOMERS)
        assertTrue(customers.freeForCustomers)
        assertFalse(customers.copy(tier = WifiTier.CHAIN).freeForCustomers)
        assertFalse(customers.copy(tier = WifiTier.TAGGED_UNSTATED).freeForCustomers)
        assertFalse(customers.copy(tier = WifiTier.LIKELY).freeForCustomers)
        assertFalse(customers.copy(fee = WifiFee.NO).freeForCustomers)
    }

    @Test
    fun `a spot without a name takes its category for one`() {
        assertEquals("Fast food", spot(1, 0.0, category = "fast_food", name = null).toPlace().name)
        assertEquals("Library", spot(1, 0.0, category = "library", name = "  ").toPlace().name)
        assertNotNull(spot(1, 0.0, name = null).toPlace().name)
        assertFalse(spot(1, 0.0, name = null).toPlace().name.isBlank())
        assertTrue(spot(2, 0.0).osmRef.startsWith("n"))
    }
}

class WifiLikelyGroupTest {
    @Test
    fun `tier 4 categories map to the sheet's wording`() {
        assertEquals(WifiLikelyGroup.LIBRARIES, WifiLikelyGroup.of("library"))
        assertEquals(WifiLikelyGroup.HOTEL_LOBBIES, WifiLikelyGroup.of("hotel"))
        assertEquals(WifiLikelyGroup.HOTEL_LOBBIES, WifiLikelyGroup.of("motel"))
        assertEquals(WifiLikelyGroup.REST_AREAS, WifiLikelyGroup.of("rest_area"))
        assertEquals(WifiLikelyGroup.VISITOR_CENTERS, WifiLikelyGroup.of("welcome_center"))
        assertEquals(WifiLikelyGroup.VISITOR_CENTERS, WifiLikelyGroup.of("visitor_centre"))
        assertEquals(WifiLikelyGroup.OTHER, WifiLikelyGroup.of("cafe"))
    }
}
