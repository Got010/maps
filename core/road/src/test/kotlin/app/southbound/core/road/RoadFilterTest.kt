package app.southbound.core.road

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadFilterTest {

    @Test
    fun `the default shows the everyday seven`() {
        val f = RoadFilter()
        assertTrue(f.isDefault)
        assertFalse(f.showsNothing)
        assertEquals(RoadFilter.DEFAULT, f)
        for (c in RoadCategory.entries) assertEquals(c.defaultOn, f.matches(c))
    }

    @Test
    fun `toggling a category changes the set and leaves the default`() {
        val f = RoadFilter.DEFAULT.toggle(RoadCategory.TOILET)
        assertTrue(f.matches(RoadCategory.TOILET))
        assertFalse(f.isDefault)
        assertTrue(f.toggle(RoadCategory.TOILET).isDefault)
        assertFalse(RoadFilter.DEFAULT.toggle(RoadCategory.CAMP).matches(RoadCategory.CAMP))
        assertEquals(RoadFilter.DEFAULT, RoadFilter.DEFAULT.with(RoadCategory.CAMP, true))
    }

    @Test
    fun `no category shows nothing`() {
        val none = RoadCategory.DEFAULT_ON.fold(RoadFilter.DEFAULT) { f, c -> f.with(c, false) }
        assertTrue(none.showsNothing)
        assertFalse(none.isDefault)
        assertFalse(RoadFilter.ALL.showsNothing)
        assertEquals(16, RoadFilter.ALL.categories.size)
    }

    @Test
    fun `a filter survives the line a rotation keeps`() {
        for (f in listOf(RoadFilter.DEFAULT, RoadFilter.ALL, RoadFilter(emptySet()), RoadFilter(setOf(RoadCategory.PARK, RoadCategory.CAMP)))) {
            assertEquals(f, RoadFilter.decode(f.encode()))
        }
        // In the table's order, whatever order they were ticked in.
        assertEquals("camp,park", RoadFilter(setOf(RoadCategory.PARK, RoadCategory.CAMP)).encode())
        assertEquals("", RoadFilter(emptySet()).encode())
    }

    @Test
    fun `an unreadable line is the default and an unknown code is dropped`() {
        assertEquals(RoadFilter.DEFAULT, RoadFilter.decode(null))
        assertEquals(RoadFilter(setOf(RoadCategory.FUEL)), RoadFilter.decode("fuel,ev_charging,,Fuel"))
        assertEquals(RoadFilter.DEFAULT, RoadFilter.decode("nonsense"))
        assertEquals(RoadFilter.DEFAULT, RoadFilter.decode("ev_charging"))
    }
}
