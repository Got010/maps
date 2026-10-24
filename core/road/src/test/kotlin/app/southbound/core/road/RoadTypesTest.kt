package app.southbound.core.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadTypesTest {

    @Test
    fun `the categories are the pipeline's sixteen, in its order`() {
        // CATEGORIES in pipeline/road/build_road.py.
        assertEquals(
            listOf(
                "camp", "rv", "rest", "dump", "water", "toilet", "shower", "fuel", "truck", "laundry", "library",
                "pharmacy", "hospital", "clinic", "grocery", "park",
            ),
            RoadCategory.entries.map { it.code },
        )
        assertEquals(16, RoadCategory.entries.size)
        assertEquals(16, RoadCategory.entries.map { it.iconKey }.toSet().size)
        assertEquals(16, RoadCategory.entries.map { it.displayName }.toSet().size)
        assertTrue(RoadCategory.entries.all { it.displayName.isNotBlank() && it.iconKey.matches(Regex("[a-z]+")) })
    }

    @Test
    fun `a category is found by its code, and an unknown code is none`() {
        assertEquals(RoadCategory.TRUCK, RoadCategory.of("truck"))
        assertNull(RoadCategory.of("ev_charging"))
        assertNull(RoadCategory.of("Truck"))
        assertNull(RoadCategory.of(null))
    }

    @Test
    fun `the default categories are the everyday ones`() {
        assertEquals(
            setOf(RoadCategory.CAMP, RoadCategory.RV, RoadCategory.REST, RoadCategory.DUMP, RoadCategory.WATER, RoadCategory.FUEL, RoadCategory.TRUCK),
            RoadCategory.DEFAULT_ON,
        )
        assertEquals(7, RoadCategory.entries.count { it.defaultOn })
    }

    @Test
    fun `flags read OpenStreetMap's words and unknown is never no`() {
        assertEquals(RoadFlag.YES, RoadFlag.of("yes"))
        assertEquals(RoadFlag.YES, RoadFlag.of(" Yes "))
        assertEquals(RoadFlag.NO, RoadFlag.of("no"))
        assertEquals(RoadFlag.UNKNOWN, RoadFlag.of(null))
        assertEquals(RoadFlag.UNKNOWN, RoadFlag.of(""))
        assertEquals(RoadFlag.UNKNOWN, RoadFlag.of("customers"))
        assertEquals(RoadFlag.UNKNOWN, RoadFlag.of("seasonal"))
    }

    @Test
    fun `extra decodes the typed fields`() {
        val e = RoadExtra.parse(
            """{"diesel":true,"drinking_water":"yes","fee":"yes","opening_hours":"Mo-Su 06:00-22:00","operator":"County Parks","shower":"no","toilets":"yes"}""",
        )
        assertTrue(e.diesel)
        assertEquals(RoadFlag.YES, e.fee)
        assertEquals(RoadFlag.YES, e.water)
        assertEquals(RoadFlag.YES, e.toilets)
        assertEquals(RoadFlag.NO, e.shower)
        assertEquals(RoadFlag.UNKNOWN, e.overnight)
        assertEquals("Mo-Su 06:00-22:00", e.hours)
        assertEquals("County Parks", e.operator)
    }

    @Test
    fun `absent means unknown, never false`() {
        val e = RoadExtra.parse("{}")
        assertEquals(RoadExtra.EMPTY, e)
        assertFalse(e.diesel)
        assertEquals(RoadFlag.UNKNOWN, e.fee)
        assertEquals(RoadFlag.UNKNOWN, e.toilets)
        assertNull(e.hours)
        assertTrue(e.isEmpty)
        assertTrue(e.chips().isEmpty())
        // Only a true diesel is diesel; the pack never writes false, and anything else is not it.
        assertFalse(RoadExtra.parse("""{"diesel":false}""").diesel)
        assertFalse(RoadExtra.parse("""{"diesel":"yes"}""").diesel)
    }

    @Test
    fun `a damaged column is empty, never an error`() {
        for (bad in listOf(null, "", "   ", "not json", "[]", "5", "null", """{"fee":""", """{"fee":5,"toilets":true,"operator":["x"]}""")) {
            assertEquals(bad, RoadExtra.EMPTY, RoadExtra.parse(bad))
        }
    }

    @Test
    fun `overnight comes from tents or caravans being allowed`() {
        assertEquals(RoadFlag.YES, RoadExtra.parse("""{"tents":"yes"}""").overnight)
        assertEquals(RoadFlag.YES, RoadExtra.parse("""{"caravans":"yes"}""").overnight)
        assertEquals(RoadFlag.YES, RoadExtra.parse("""{"tents":"no","caravans":"yes"}""").overnight)
        assertEquals(RoadFlag.NO, RoadExtra.parse("""{"tents":"no","caravans":"no"}""").overnight)
        // One no and nothing else says nothing about the other.
        assertEquals(RoadFlag.UNKNOWN, RoadExtra.parse("""{"tents":"no"}""").overnight)
        assertEquals(RoadFlag.UNKNOWN, RoadExtra.parse("""{"reservation":"required"}""").overnight)
    }

    @Test
    fun `chips are the yeses in the documented order`() {
        val all = RoadExtra(true, RoadFlag.YES, RoadFlag.YES, RoadFlag.YES, RoadFlag.YES, RoadFlag.YES)
        assertEquals(
            listOf(RoadChip.DIESEL, RoadChip.FEE, RoadChip.TOILETS, RoadChip.WATER, RoadChip.SHOWERS, RoadChip.OVERNIGHT),
            all.chips(),
        )
        // A no is not a chip: "no fee" would be a claim the row does not make.
        assertEquals(listOf(RoadChip.TOILETS), RoadExtra(fee = RoadFlag.NO, toilets = RoadFlag.YES, shower = RoadFlag.NO).chips())
    }

    @Test
    fun `long text is cut`() {
        val long = "x".repeat(1000)
        assertEquals(RoadExtra.MAX_TEXT, RoadExtra.parse("""{"operator":"$long","opening_hours":"$long"}""").operator!!.length)
        assertNull(RoadExtra.parse("""{"operator":"   "}""").operator)
    }

    @Test
    fun `the facts one row lacks come from the other`() {
        val pumps = RoadExtra(diesel = true, hours = "24/7")
        val truck = RoadExtra(shower = RoadFlag.YES, hours = "Mo-Su 00:00-24:00")
        val merged = truck.orElse(pumps)
        assertTrue(merged.diesel)
        assertEquals(RoadFlag.YES, merged.shower)
        // The row that shows keeps its own hours.
        assertEquals("Mo-Su 00:00-24:00", merged.hours)
        assertEquals(RoadFlag.UNKNOWN, merged.fee)
    }

    @Test
    fun `a spot is titled by name, then brand, then its kind`() {
        fun spot(name: String?, brand: String?) = RoadSpot(1, 39.5, -95.5, RoadCategory.REST, name, brand)
        assertEquals("Mile 40 Rest Area", spot("Mile 40 Rest Area", "x").title)
        assertEquals("Acme", spot(null, "Acme").title)
        assertEquals("Acme", spot("  ", "Acme").title)
        assertEquals("Rest area", spot(null, null).title)
    }

    @Test
    fun `a spot becomes a place with its hours and its kind`() {
        val spot = RoadSpot(77, 39.51, -95.52, RoadCategory.RV, "Prairie RV", null, RoadExtra(hours = "Mo-Su 08:00-20:00"))
        val place = spot.toPlace()
        assertEquals(77L, place.id)
        assertEquals("Prairie RV", place.name)
        assertEquals("RV site", place.categoryLabel)
        assertEquals("Mo-Su 08:00-20:00", place.openingHours)
        assertEquals(39.51, place.lat, 0.0)
        assertEquals(-95.52, place.lon, 0.0)
        assertNull(place.address)
        assertNull(place.osmRef)
    }
}
