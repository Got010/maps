package app.southbound.feature.explore

import app.southbound.core.road.RoadCategory
import app.southbound.core.road.RoadFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadChooserTest {
    @Test
    fun `the default choice can be applied, so a swipe away applies it`() {
        assertTrue(RoadFilter.DEFAULT.canApply)
        assertTrue(RoadFilter.ALL.canApply)
    }

    @Test
    fun `a narrowed or widened choice that still shows something can be applied`() {
        assertTrue(RoadFilter.DEFAULT.toggle(RoadCategory.FUEL).canApply)
        assertTrue(RoadFilter.DEFAULT.toggle(RoadCategory.PARK).canApply)
        assertTrue(RoadFilter(setOf(RoadCategory.CAMP)).canApply)
    }

    @Test
    fun `a choice that shows nothing is discarded on dismissal`() {
        assertFalse(RoadFilter(emptySet()).canApply)
        assertFalse(RoadCategory.DEFAULT_ON.fold(RoadFilter.DEFAULT) { f, c -> f.with(c, false) }.canApply)
    }

    @Test
    fun `the saver's line brings the choice back through a rotation`() {
        for (f in listOf(RoadFilter.DEFAULT, RoadFilter.ALL, RoadFilter(setOf(RoadCategory.TOILET, RoadCategory.SHOWER)))) {
            assertEquals(f, RoadFilter.decode(f.encode()))
        }
    }
}
