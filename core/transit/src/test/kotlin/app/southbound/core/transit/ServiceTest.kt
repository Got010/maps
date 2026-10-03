package app.southbound.core.transit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ServiceTest {
    private val weekdays = Service(1, days = 0b0011111, startDate = 20260101, endDate = 20261231,
        exceptions = mapOf(20260907 to false, 20260912 to true))

    @Test
    fun `runs on its weekdays within its dates`() {
        assertTrue(weekdays.runsOn(LocalDate.of(2026, 9, 8))) // Tuesday
        assertFalse(weekdays.runsOn(LocalDate.of(2026, 9, 6))) // Sunday
        assertFalse(weekdays.runsOn(LocalDate.of(2027, 1, 4))) // after end
    }

    @Test
    fun `exceptions win`() {
        assertFalse(weekdays.runsOn(LocalDate.of(2026, 9, 7))) // Labor Day, removed
        assertTrue(weekdays.runsOn(LocalDate.of(2026, 9, 12))) // a Saturday, added
    }

    @Test
    fun `dated-only service runs only on its dates`() {
        val special = Service(2, 0, 0, 0, mapOf(20261231 to true))
        assertTrue(special.runsOn(LocalDate.of(2026, 12, 31)))
        assertFalse(special.runsOn(LocalDate.of(2026, 12, 30)))
    }
}
