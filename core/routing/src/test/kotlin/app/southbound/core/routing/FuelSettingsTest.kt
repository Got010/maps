package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelSettingsTest {
    @Test
    fun `defaults are 25 mpg and 3_50 a gallon, typed price, New York`() {
        assertEquals(25.0, FuelSettings.DEFAULT.mpg, 0.0)
        assertEquals(3.50, FuelSettings.DEFAULT.pricePerGallon, 0.0)
        assertEquals(PriceSource.MANUAL, FuelSettings.DEFAULT.priceSource)
        assertEquals(GasRegion.NY, FuelSettings.DEFAULT.region)
        assertEquals(FuelSettings.DEFAULT, FuelSettings.DEFAULT.sanitized())
    }

    @Test
    fun `sanitized clamps to range`() {
        val low = FuelSettings(mpg = 0.5, pricePerGallon = 0.01).sanitized()
        assertEquals(5.0, low.mpg, 0.0)
        assertEquals(0.5, low.pricePerGallon, 0.0)
        val high = FuelSettings(mpg = 999.0, pricePerGallon = 99.0).sanitized()
        assertEquals(150.0, high.mpg, 0.0)
        assertEquals(15.0, high.pricePerGallon, 0.0)
    }

    @Test
    fun `values in range are kept`() {
        val s = FuelSettings(mpg = 34.5, pricePerGallon = 4.25).sanitized()
        assertEquals(34.5, s.mpg, 0.0)
        assertEquals(4.25, s.pricePerGallon, 0.0)
    }

    @Test
    fun `not a number goes back to the default`() {
        val s = FuelSettings(mpg = Double.NaN, pricePerGallon = Double.POSITIVE_INFINITY).sanitized()
        assertEquals(25.0, s.mpg, 0.0)
        assertEquals(3.50, s.pricePerGallon, 0.0)
    }

    @Test
    fun `an out of range fetched price is dropped`() {
        assertNull(FuelSettings(fetchedPrice = 0.1, fetchedRegion = GasRegion.NY).sanitized().fetchedPrice)
        assertNull(FuelSettings(fetchedPrice = 40.0, fetchedRegion = GasRegion.NY).sanitized().fetchedPrice)
        assertEquals(3.5, FuelSettings(fetchedPrice = 3.5, fetchedRegion = GasRegion.NY).sanitized().fetchedPrice!!, 0.0)
    }

    private val fetched = FuelSettings(
        pricePerGallon = 3.50,
        region = GasRegion.NY,
        fetchedPrice = 3.912,
        fetchedPeriod = "2026-09-29",
        fetchedRegion = GasRegion.NY,
    )

    @Test
    fun `effective price is the typed one unless EIA is the source`() {
        assertEquals(3.50, fetched.effectivePrice, 0.0)
        assertEquals(3.912, fetched.copy(priceSource = PriceSource.EIA).effectivePrice, 0.0)
    }

    @Test
    fun `EIA with nothing fetched falls back to the typed price`() {
        val s = FuelSettings(priceSource = PriceSource.EIA, pricePerGallon = 4.0)
        assertNull(s.eiaPrice)
        assertEquals(4.0, s.effectivePrice, 0.0)
    }

    @Test
    fun `a fetched price for another region does not count`() {
        val s = fetched.copy(priceSource = PriceSource.EIA, region = GasRegion.NJ)
        assertNull(s.eiaPrice)
        assertEquals(3.50, s.effectivePrice, 0.0)
    }
}
