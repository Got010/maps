package app.southbound.feature.transit

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitFormatTest {
    @Test
    fun `minutes until rounds up and never goes negative`() {
        assertEquals(1, TransitFormat.minutesUntil(1000, 990))
        assertEquals(0, TransitFormat.minutesUntil(1000, 1000))
        assertEquals(0, TransitFormat.minutesUntil(1000, 1200))
        assertEquals(2, TransitFormat.minutesUntil(1120, 1000))
    }

    @Test
    fun `colours parse with or without a hash`() {
        assertEquals(Color(0xFFD82233), TransitFormat.color("D82233"))
        assertEquals(TransitFormat.color("#d82233"), TransitFormat.color("D82233"))
        assertNull(TransitFormat.color("12345"))
        assertNull(TransitFormat.color(null))
    }
}
