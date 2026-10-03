package app.southbound.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGateTest {

    @Test
    fun onlyPackDownloadsAreOnByDefault() {
        // CLAUDE.md: each feature off by default except pack downloads.
        val toggles = InMemoryNetworkToggles()
        val enabled = NetworkFeature.entries.filter(toggles::isEnabled)
        assertEquals(listOf(NetworkFeature.PACK_DOWNLOADS), enabled)
    }

    @Test
    fun disabledFeatureNeverRunsTheRequest() {
        val gate = NetworkGate(InMemoryNetworkToggles())
        var ran = false
        val error = runCatching {
            gate.request(NetworkFeature.TRAFFIC_TOMTOM) { ran = true }
        }.exceptionOrNull()
        assertFalse(ran)
        assertTrue(error is NetworkFeatureDisabledException)
        assertEquals(NetworkFeature.TRAFFIC_TOMTOM, (error as NetworkFeatureDisabledException).feature)
    }

    @Test
    fun enabledFeatureRunsTheRequest() {
        val toggles = InMemoryNetworkToggles()
        toggles.set(NetworkFeature.TRAFFIC_511NY, true)
        val result = NetworkGate(toggles).request(NetworkFeature.TRAFFIC_511NY) { "ok" }
        assertEquals("ok", result)
    }

    @Test
    fun turningAFeatureOffBlocksLaterRequests() {
        val toggles = InMemoryNetworkToggles()
        val gate = NetworkGate(toggles)
        assertTrue(gate.isAllowed(NetworkFeature.PACK_DOWNLOADS))
        toggles.set(NetworkFeature.PACK_DOWNLOADS, false)
        assertFalse(gate.isAllowed(NetworkFeature.PACK_DOWNLOADS))
    }

    @Test
    fun disabledFeatureNeverConnects() {
        // Nothing listens on port 1; reaching it would fail with an IOException instead.
        val error = runCatching {
            NetworkGate(InMemoryNetworkToggles()).get(NetworkFeature.TRANSIT_REALTIME, "http://127.0.0.1:1/")
        }.exceptionOrNull()
        assertTrue(error is NetworkFeatureDisabledException)
    }
}
