package app.southbound.core.routing

import app.southbound.core.network.InMemoryNetworkToggles
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GasPricesTest {

    private val fixture = """
        {"response":{"total":"1","dateFormat":"YYYY-MM-DD","frequency":"weekly",
         "data":[{"period":"2026-09-29","duoarea":"SNY","area-name":"NEW YORK","product":"EMM_EPMR",
                  "series":"EMM_EPMR_PTE_SNY_DPG","value":"3.512","units":"${'$'}/GAL"}]},
         "request":{"command":"/v2/petroleum/pri/gnd/data/"}}
    """.trimIndent()

    // --- parse ---

    @Test
    fun `parses the newest row with a string value`() {
        val p = GasPrices.parse(fixture)
        assertEquals(3.512, p.pricePerGallon, 0.0)
        assertEquals("2026-09-29", p.periodIso)
        assertEquals("EMM_EPMR_PTE_SNY_DPG", p.seriesId)
    }

    @Test
    fun `a numeric value works too`() {
        val p = GasPrices.parse("""{"response":{"data":[{"period":"2026-09-22","value":3.4,"series":"X"}]}}""")
        assertEquals(3.4, p.pricePerGallon, 0.0)
    }

    @Test
    fun `invalid rows are skipped and the next good one is used`() {
        val json = """{"response":{"data":[
            {"period":"2026-09-29","value":"99.0","series":"X"},
            {"period":"2026-09-22","value":"abc","series":"X"},
            {"period":"2026-09-15","value":null,"series":"X"},
            {"value":"3.3","series":"X"},
            {"period":"2026-09-08","value":"3.25","series":"X"}]}}"""
        val p = GasPrices.parse(json)
        assertEquals(3.25, p.pricePerGallon, 0.0)
        assertEquals("2026-09-08", p.periodIso)
    }

    @Test
    fun `prices outside 0_5 to 15 are invalid`() {
        for (v in listOf("0.49", "15.01", "-3", "0")) {
            assertFails { GasPrices.parse("""{"response":{"data":[{"period":"2026-09-29","value":"$v"}]}}""") }
        }
        assertEquals(0.5, GasPrices.parse("""{"response":{"data":[{"period":"2026-09-29","value":"0.5"}]}}""").pricePerGallon, 0.0)
        assertEquals(15.0, GasPrices.parse("""{"response":{"data":[{"period":"2026-09-29","value":"15"}]}}""").pricePerGallon, 0.0)
    }

    @Test
    fun `no rows, no response and not json are all refused`() {
        assertFails { GasPrices.parse("""{"response":{"data":[]}}""") }
        assertFails { GasPrices.parse("""{"error":"API_KEY_INVALID"}""") }
        assertFails { GasPrices.parse("[]") }
        assertFails { GasPrices.parse("<html>nope</html>") }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- url ---

    @Test
    fun `url asks for the newest weekly row of one series and nothing else`() {
        assertEquals(
            "https://api.eia.gov/v2/petroleum/pri/gnd/data/?api_key=KEY123&frequency=weekly&data%5B0%5D=value" +
                "&facets%5Bseries%5D%5B%5D=EMM_EPMR_PTE_SNY_DPG&sort%5B0%5D%5Bcolumn%5D=period" +
                "&sort%5B0%5D%5Bdirection%5D=desc&length=1",
            GasPrices.url("KEY123", EiaSeries.NEW_YORK),
        )
    }

    @Test
    fun `the key is url encoded`() {
        assertTrue(GasPrices.url("a b&c", EiaSeries.US).contains("api_key=a+b%26c&"))
    }

    @Test
    fun `regions map to their series`() {
        assertEquals("EMM_EPMR_PTE_SNY_DPG", GasRegion.NY.seriesId)
        assertEquals("EMM_EPMR_PTE_R1Y_DPG", GasRegion.NJ.seriesId)
        assertEquals("EMM_EPMR_PTE_NUS_DPG", GasRegion.US.seriesId)
    }

    @Test
    fun `period shows as month and day`() {
        assertEquals("Sep 29", GasPrices.formatPeriod("2026-09-29"))
        assertEquals("garbled", GasPrices.formatPeriod("garbled"))
    }

    // --- the 24 hour gate ---

    private class FakeStore(override var region: GasRegion = GasRegion.NY) : GasPriceStore {
        override var fetchedRegion: GasRegion? = null
        override var fetchedAtMillis: Long = 0L
        var saved: GasPrice? = null

        override fun saveFetched(price: GasPrice, region: GasRegion, atMillis: Long) {
            saved = price
            fetchedRegion = region
            fetchedAtMillis = atMillis
        }
    }

    private val requested = ArrayList<Pair<NetworkFeature, String>>()
    private var now = 1_790_000_000_000L
    private var failure: Exception? = null
    private var body = fixture

    private fun prices(store: FakeStore, enabled: Boolean = true, key: String? = "KEY"): GasPrices {
        val gate = NetworkGate(InMemoryNetworkToggles(mapOf(NetworkFeature.GAS_PRICES to enabled)))
        return GasPrices(gate, { key }, store, { now }) { feature, url ->
            gate.request(feature) {
                requested.add(feature to url)
                failure?.let { throw it }
                body.toByteArray()
            }
        }
    }

    @Test
    fun `off by default fetches nothing`() = runBlocking {
        val store = FakeStore()
        assertNull(prices(store, enabled = false).refresh())
        val defaults = GasPrices(NetworkGate(InMemoryNetworkToggles()), { "KEY" }, store, { now }) { _, _ -> error("no request expected") }
        assertNull(defaults.refresh())
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `no key fetches nothing`() = runBlocking {
        val store = FakeStore()
        assertNull(prices(store, key = null).refresh())
        assertNull(prices(store, key = "  ").refresh())
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `fetches once and stores the price`() = runBlocking {
        val store = FakeStore()
        val p = prices(store).refresh()
        assertNotNull(p)
        assertEquals(1, requested.size)
        assertEquals(NetworkFeature.GAS_PRICES, requested.single().first)
        assertTrue(requested.single().second.contains("facets%5Bseries%5D%5B%5D=EMM_EPMR_PTE_SNY_DPG"))
        assertEquals(3.512, store.saved!!.pricePerGallon, 0.0)
        assertEquals(GasRegion.NY, store.fetchedRegion)
        assertEquals(now, store.fetchedAtMillis)
    }

    @Test
    fun `at most one request a day`() = runBlocking {
        val store = FakeStore()
        val feed = prices(store)
        feed.refresh()
        now += GasPrices.DAY_MILLIS - 1
        assertNull(feed.refresh())
        assertEquals(1, requested.size)
        now += 1
        assertNotNull(feed.refresh())
        assertEquals(2, requested.size)
        assertNotNull(feed.refresh(force = true))
        assertEquals(3, requested.size)
    }

    @Test
    fun `a stored price survives a restart for the day`() = runBlocking {
        val store = FakeStore().apply {
            fetchedRegion = GasRegion.NY
            fetchedAtMillis = now - 3_600_000
        }
        assertNull(prices(store).refresh())
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `changing the region fetches right away`() = runBlocking {
        val store = FakeStore()
        val feed = prices(store)
        feed.refresh()
        store.region = GasRegion.NJ
        now += 60_000
        assertNotNull(feed.refresh())
        assertEquals(2, requested.size)
        assertTrue(requested.last().second.contains("EMM_EPMR_PTE_R1Y_DPG"))
        assertEquals(GasRegion.NJ, store.fetchedRegion)
    }

    @Test
    fun `isDue covers missing, other region, old and future stamps`() {
        val t = 1_000_000_000_000L
        assertTrue(GasPrices.isDue(null, 0, GasRegion.NY, t))
        assertTrue(GasPrices.isDue(GasRegion.NJ, t, GasRegion.NY, t))
        assertTrue(GasPrices.isDue(GasRegion.NY, t - GasPrices.DAY_MILLIS, GasRegion.NY, t))
        assertTrue(GasPrices.isDue(GasRegion.NY, t + 1, GasRegion.NY, t))
        assertEquals(false, GasPrices.isDue(GasRegion.NY, t - 1000, GasRegion.NY, t))
    }

    @Test
    fun `a failure stores nothing, records why, and waits before retrying`() = runBlocking {
        val store = FakeStore()
        val feed = prices(store)
        failure = IOException("HTTP 503 from api.eia.gov")
        assertNull(feed.refresh())
        assertNull(store.saved)
        assertEquals("HTTP 503 from api.eia.gov", feed.lastError)
        now += 60_000
        assertNull(feed.refresh())
        assertEquals(1, requested.size)
        failure = null
        now += GasPrices.RETRY_MILLIS
        assertNotNull(feed.refresh())
        assertEquals(2, requested.size)
        assertNull(feed.lastError)
    }

    @Test
    fun `an error document or garbage stores nothing`() = runBlocking {
        val store = FakeStore()
        body = """{"error":"API_KEY_INVALID"}"""
        val feed = prices(store)
        assertNull(feed.refresh())
        assertNull(store.saved)
        assertEquals("API_KEY_INVALID", feed.lastError)
        body = "not json"
        assertNull(feed.refresh(force = true))
        assertNull(store.saved)
    }
}
