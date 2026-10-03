package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class FuelModelTest {
    private val mile = 1609.344

    /** [miles] driven at [mph], as one leg. */
    private fun leg(miles: Double, mph: Double) = FuelModel.Leg(miles * mile, miles / mph * 3600.0)

    @Test
    fun `steady 30 mph burns the rated mpg`() {
        // 10 mi * 1.0 / 25 mpg = 0.4 gal
        assertEquals(0.4, FuelModel.gallons(listOf(leg(10.0, 30.0)), 25.0), 1e-9)
    }

    @Test
    fun `stop and go below 25 mph costs a quarter more`() {
        // 10 * 1.25 / 25 = 0.5
        assertEquals(0.5, FuelModel.gallons(listOf(leg(10.0, 10.0)), 25.0), 1e-9)
    }

    @Test
    fun `45 to 60 mph is a little better than rated`() {
        // 10 * 0.95 / 25 = 0.38
        assertEquals(0.38, FuelModel.gallons(listOf(leg(10.0, 50.0)), 25.0), 1e-9)
    }

    @Test
    fun `60 to 65 mph is the rated figure and above 65 pays for the wind`() {
        assertEquals(0.4, FuelModel.gallons(listOf(leg(10.0, 62.0)), 25.0), 1e-9)
        // 10 * 1.10 / 25 = 0.44
        assertEquals(0.44, FuelModel.gallons(listOf(leg(10.0, 70.0)), 25.0), 1e-9)
    }

    @Test
    fun `legs add up`() {
        // 5 mi at 10 mph: 5*1.25/25 = 0.25;  20 mi at 50 mph: 20*0.95/25 = 0.76
        assertEquals(1.01, FuelModel.gallons(listOf(leg(5.0, 10.0), leg(20.0, 50.0)), 25.0), 1e-9)
    }

    @Test
    fun `better mpg burns proportionally less`() {
        assertEquals(0.2, FuelModel.gallons(listOf(leg(10.0, 30.0)), 50.0), 1e-9)
    }

    @Test
    fun `traffic delay idles at 0_4 gallons an hour`() {
        // 30 min of delay = 0.2 gal on top of 0.4
        assertEquals(0.6, FuelModel.gallons(listOf(leg(10.0, 30.0)), 25.0, delaySeconds = 1800.0), 1e-9)
        // Negative delay never gives fuel back.
        assertEquals(0.4, FuelModel.gallons(listOf(leg(10.0, 30.0)), 25.0, delaySeconds = -600.0), 1e-9)
    }

    @Test
    fun `a leg with no time or distance counts as rated`() {
        assertEquals(0.4, FuelModel.gallons(listOf(FuelModel.Leg(10 * mile, 0.0)), 25.0), 1e-9)
        assertEquals(0.0, FuelModel.gallons(listOf(FuelModel.Leg(0.0, 60.0)), 25.0), 1e-9)
        assertEquals(0.0, FuelModel.gallons(emptyList(), 25.0), 1e-9)
    }

    @Test
    fun `cost is gallons times price`() {
        assertEquals(1.4, FuelModel.cost(0.4, 3.50), 1e-9)
    }

    @Test
    fun `most efficient is the fewest gallons and the first on a tie`() {
        assertEquals(1, FuelModel.mostEfficient(listOf(0.5, 0.4, 0.45)))
        assertEquals(0, FuelModel.mostEfficient(listOf(0.4, 0.4)))
        assertEquals(null, FuelModel.mostEfficient(emptyList()))
        assertEquals(1, FuelModel.mostEfficient(listOf(Double.NaN, 0.3)))
    }

    @Test
    fun `cost format`() {
        assertEquals("$4.10", FuelModel.formatCost(4.1))
        assertEquals("$0.35", FuelModel.formatCost(0.348))
        assertEquals("$14", FuelModel.formatCost(13.6))
        assertEquals("$10", FuelModel.formatCost(9.999))
    }
}
