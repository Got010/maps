package app.southbound.feature.explore

import app.southbound.core.wifi.WifiFilter
import app.southbound.core.wifi.WifiKind
import app.southbound.core.wifi.WifiTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiChooserTest {
    @Test
    fun `the default choice can be applied, so a swipe away applies it`() {
        assertTrue(WifiFilter.DEFAULT.canApply)
    }

    @Test
    fun `a narrowed choice that still shows something can be applied`() {
        assertTrue(WifiFilter.DEFAULT.withTier(WifiTier.LIKELY, false).canApply)
        assertTrue(WifiFilter(kinds = setOf(WifiKind.LIBRARIES)).canApply)
    }

    @Test
    fun `a choice that shows nothing is discarded on dismissal`() {
        assertFalse(WifiFilter(tiers = emptySet()).canApply)
        assertFalse(WifiFilter(customersOnly = true, tiers = setOf(WifiTier.CHAIN)).canApply)
        assertFalse(WifiFilter(kinds = emptySet()).canApply)
    }
}
