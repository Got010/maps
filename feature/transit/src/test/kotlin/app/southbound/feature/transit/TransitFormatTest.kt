package app.southbound.feature.transit

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TransitFormatTest {
    @Test
    fun `minutes until rounds up and never goes negative`() {
        assertEquals(1, TransitFormat.minutesUntil(1000, 990))
        assertEquals(0, TransitFormat.minutesUntil(1000, 1000))
        assertEquals(0, TransitFormat.minutesUntil(1000, 1200))
        assertEquals(2, TransitFormat.minutesUntil(1120, 1000))
    }

    @Test
    fun `a countdown says now for the departure minute and nothing once it has gone`() {
        assertEquals(2, TransitFormat.countdown(1120, 1000))
        assertEquals(1, TransitFormat.countdown(1001, 1000))
        assertEquals(0, TransitFormat.countdown(1000, 1000))
        assertEquals(0, TransitFormat.countdown(1000, 1059))
        assertNull(TransitFormat.countdown(1000, 1060))
        assertNull(TransitFormat.countdown(1000, 4600))
    }

    @Test
    fun `times follow the 12 or 24 hour setting in any language`() {
        val evening = 20 * 3600 + 31 * 60
        assertEquals("20:31", TransitFormat.time(evening, use24h = true, Locale.US))
        assertEquals("20:31", TransitFormat.time(evening, use24h = true, Locale.GERMANY))
        // Newer Java puts a narrow no-break space before "PM".
        for (locale in listOf(Locale.US, Locale.GERMANY)) {
            val twelve = TransitFormat.time(evening, use24h = false, locale)
            assertTrue(twelve, twelve.startsWith("8:31") && !twelve.contains("20"))
        }
        // Past midnight wraps.
        assertEquals("00:05", TransitFormat.time(86_400 + 300, use24h = true, Locale.US))
    }

    @Test
    fun `colours parse with or without a hash`() {
        assertEquals(Color(0xFFD82233), TransitFormat.color("D82233"))
        assertEquals(TransitFormat.color("#d82233"), TransitFormat.color("D82233"))
        assertNull(TransitFormat.color("12345"))
        assertNull(TransitFormat.color(null))
    }
}
