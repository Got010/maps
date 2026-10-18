package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WifiColorsTest {
    @Test
    fun theCustomersRingIsGoldAndNoTierColour() {
        assertEquals("#F9AB00", WifiColors.CUSTOMERS_GOLD)
        assertEquals(0xFFF9AB00.toInt(), WifiColors.customersArgb)
        for (tier in 1..4) {
            assertNotEquals(WifiColors.CUSTOMERS_GOLD, WifiColors.hex(tier))
            assertNotEquals(WifiColors.customersArgb, WifiColors.argb(tier))
        }
        // Tier 1's green is unchanged: the badge body stays that green.
        assertEquals(0xFF1E8E3E.toInt(), WifiColors.argb(1))
    }
}
