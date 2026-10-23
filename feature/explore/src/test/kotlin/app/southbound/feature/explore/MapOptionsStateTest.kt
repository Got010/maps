package app.southbound.feature.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOptionsStateTest {
    private val nothing = MapOptionsState(
        hasLocation = false, following = true,
        trafficAvailable = false, trafficVisible = false,
        weatherAvailable = false, weatherVisible = false,
        wifiAvailable = false, wifiVisible = false, wifiFiltered = false,
    )
    private val everything = nothing.copy(hasLocation = true, trafficAvailable = true, weatherAvailable = true, wifiAvailable = true)

    @Test
    fun noRowsWithoutFeaturesOrFix() {
        assertTrue(nothing.rows.isEmpty())
        assertEquals(MapOptionsDot.NONE, nothing.dot)
    }

    @Test
    fun rowsInListOrder() {
        assertEquals(listOf(MapOption.RECENTER, MapOption.TRAFFIC, MapOption.WEATHER, MapOption.WIFI), everything.rows)
    }

    @Test
    fun thisStateIsLastAndOnlyWhenTheStateIsKnown() {
        assertEquals(listOf(MapOption.THIS_STATE), nothing.copy(stateAvailable = true).rows)
        assertEquals(
            listOf(MapOption.RECENTER, MapOption.TRAFFIC, MapOption.WEATHER, MapOption.WIFI, MapOption.THIS_STATE),
            everything.copy(stateAvailable = true).rows,
        )
        assertEquals(MapOptionsDot.NONE, nothing.copy(stateAvailable = true).dot)
    }

    @Test
    fun recenterNeedsAFix() {
        assertEquals(listOf(MapOption.TRAFFIC, MapOption.WEATHER, MapOption.WIFI), everything.copy(hasLocation = false).rows)
    }

    @Test
    fun eachRowFollowsItsOwnCondition() {
        assertEquals(listOf(MapOption.TRAFFIC), nothing.copy(trafficAvailable = true).rows)
        assertEquals(listOf(MapOption.WEATHER), nothing.copy(weatherAvailable = true).rows)
        assertEquals(listOf(MapOption.WIFI), nothing.copy(wifiAvailable = true).rows)
        assertEquals(listOf(MapOption.RECENTER), nothing.copy(hasLocation = true).rows)
    }

    @Test
    fun recenterLabelFollowsThePan() {
        assertFalse(everything.panned)
        assertTrue(everything.copy(following = false).panned)
    }

    @Test
    fun dotWhenAnyLayerIsVisible() {
        assertEquals(MapOptionsDot.NONE, everything.dot)
        assertEquals(MapOptionsDot.LAYER, everything.copy(trafficVisible = true).dot)
        assertEquals(MapOptionsDot.LAYER, everything.copy(weatherVisible = true).dot)
        assertEquals(MapOptionsDot.LAYER, everything.copy(wifiVisible = true).dot)
        assertEquals(MapOptionsDot.LAYER, everything.copy(trafficVisible = true, weatherVisible = true, wifiVisible = true).dot)
    }

    @Test
    fun goldDotOnlyWhenTheWifiLayerShowsWithAFilter() {
        assertEquals(MapOptionsDot.WIFI_FILTERED, everything.copy(wifiVisible = true, wifiFiltered = true).dot)
        assertEquals(MapOptionsDot.WIFI_FILTERED, everything.copy(wifiVisible = true, trafficVisible = true, wifiFiltered = true).dot)
        // A filter on a hidden Wi-Fi layer gives no gold, even while another layer shows.
        assertEquals(MapOptionsDot.LAYER, everything.copy(trafficVisible = true, wifiFiltered = true).dot)
        assertEquals(MapOptionsDot.LAYER, everything.copy(weatherVisible = true, wifiFiltered = true).dot)
    }

    @Test
    fun noDotWhenOnlyAFilterIsSetAndNothingShows() {
        assertEquals(MapOptionsDot.NONE, everything.copy(wifiFiltered = true).dot)
    }

    @Test
    fun theRoadRowComesLastAndNeedsTheInstalledPack() {
        assertEquals(listOf(MapOption.ROAD), nothing.copy(roadAvailable = true).rows)
        assertEquals(
            listOf(MapOption.RECENTER, MapOption.TRAFFIC, MapOption.WEATHER, MapOption.WIFI, MapOption.ROAD),
            everything.copy(roadAvailable = true).rows,
        )
        // Without the pack there is no row, whatever was left set.
        assertEquals(everything.rows, everything.copy(roadVisible = true, roadFiltered = true).rows)
    }

    @Test
    fun theRoadLayerCountsAsALayerButNeverColoursTheDotGold() {
        val road = everything.copy(roadAvailable = true)
        assertEquals(MapOptionsDot.NONE, road.dot)
        assertEquals(MapOptionsDot.LAYER, road.copy(roadVisible = true).dot)
        assertEquals(MapOptionsDot.LAYER, road.copy(roadVisible = true, roadFiltered = true).dot)
        assertTrue(road.copy(roadFiltered = true).roadRowFiltered)
        assertFalse(everything.copy(roadFiltered = true).roadRowFiltered)
        // A visible layer whose row is missing (the pack was removed) does not count.
        assertEquals(MapOptionsDot.NONE, everything.copy(roadVisible = true).dot)
    }

    @Test
    fun aLayerWhoseRowIsMissingDoesNotCount() {
        // The Live-data toggle was turned off while the layer's flag was still set: the row and the layer are gone.
        val off = nothing.copy(hasLocation = true, trafficVisible = true, weatherVisible = true, wifiVisible = true, wifiFiltered = true)
        assertFalse(off.anyLayerVisible)
        assertEquals(MapOptionsDot.NONE, off.dot)
        assertFalse(off.wifiRowFiltered)
        // A filter left over from a removed pack does not colour the dot of a visible traffic layer.
        val traffic = off.copy(trafficAvailable = true)
        assertEquals(MapOptionsDot.LAYER, traffic.dot)
    }

    @Test
    fun tripLogIsTheLastRowAndNeverADot() {
        assertEquals(listOf(MapOption.TRIP_LOG), nothing.copy(tripLogAvailable = true).rows)
        assertEquals(
            listOf(MapOption.RECENTER, MapOption.TRAFFIC, MapOption.WEATHER, MapOption.WIFI, MapOption.ROAD, MapOption.THIS_STATE, MapOption.TRIP_LOG),
            everything.copy(roadAvailable = true, stateAvailable = true, tripLogAvailable = true).rows,
        )
        assertEquals(MapOption.TRIP_LOG, MapOption.entries.last())
        assertEquals(MapOptionsDot.NONE, nothing.copy(tripLogAvailable = true).dot)
        assertFalse(nothing.copy(tripLogAvailable = true).anyLayerVisible)
        // Not offered (guidance): no row.
        assertFalse(everything.rows.contains(MapOption.TRIP_LOG))
    }
}
