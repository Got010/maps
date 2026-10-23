package app.southbound.feature.explore

import app.southbound.core.map.RoadColors
import app.southbound.core.map.RoadPin
import app.southbound.core.road.RoadCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map module draws by category code and the road module names the categories: the two lists must be one list. */
class RoadKeysTest {
    @Test
    fun `the map's keys are the road module's categories, in the same order`() {
        assertEquals(RoadCategory.entries.map { it.code }, RoadColors.KEYS)
    }

    @Test
    fun `every category has its own badge image and colour on the map`() {
        for (category in RoadCategory.entries) {
            assertEquals("southbound-road-${category.code}", RoadPin.imageName(category.code))
            assertTrue(category.code, RoadColors.hex(category.code) != RoadColors.CLUSTER)
        }
    }

    @Test
    fun `a tap on a cell zooms in two levels from where the map is`() {
        assertEquals(8.0, RoadLayer.zoomAfterClusterTap(6.0), 0.0)
        assertEquals(10.0, RoadLayer.zoomAfterClusterTap(null), 0.0)
        assertEquals(RoadPin.MIN_ZOOM, RoadLayer.zoomAfterClusterTap(RoadPin.MIN_ZOOM - 2), 0.0)
    }
}
