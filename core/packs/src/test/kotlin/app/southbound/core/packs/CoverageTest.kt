package app.southbound.core.packs

import app.southbound.core.packs.Coverage.Item
import app.southbound.core.packs.Coverage.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverageTest {
    @Test
    fun `every covered state is listed once, in the picker's order`() {
        assertEquals(
            listOf("NY", "NJ", "CT", "PA", "MD", "DE", "DC", "OH", "IN", "IL", "MI", "WI", "MN", "IA", "MO", "ND", "SD", "NE", "KS"),
            Coverage.STATES.map { it.code },
        )
        // Each picker heading names its states ("Maryland, Delaware and DC"), and each state is under one.
        val headings = Regions.byState.map { it.first }
        for (state in Coverage.STATES) {
            assertEquals(state.code, 1, headings.count { state.name in it })
        }
        for (heading in headings) assertTrue(heading, Coverage.STATES.any { it.name in heading })
    }

    @Test
    fun `the states without a traffic feed are New Jersey, DC and Missouri`() {
        assertEquals(listOf("NJ", "DC", "MO"), Coverage.STATES.filter { it.trafficFeed == null }.map { it.code })
        for (code in listOf("NJ", "DC", "MO")) {
            val state = Coverage.of(code)!!
            for (item in listOf(Item.INCIDENTS, Item.CAMERAS, Item.SIGNS)) assertEquals(code, Level.NONE, state.entry(item).level)
        }
        assertEquals(Level.PARTIAL, Coverage.of("OH")!!.entry(Item.INCIDENTS).level)
        assertEquals(listOf("MI", "ND", "SD"), Coverage.STATES.filter { it.trafficFeed != null && it.entry(Item.SIGNS).level == Level.NONE }.map { it.code })
    }

    /** "Avoiding DOT cameras" is what the packs carry: pipeline/search/dot_cameras.py's FETCHERS and NO_LIST. */
    @Test
    fun `avoiding DOT cameras matches the build's camera lists`() {
        val py = pipelineDir().resolve("search/dot_cameras.py").readText()
        fun keys(block: String): Set<String> {
            val body = py.substringAfter("$block = {").substringBefore("\n}")
            return Regex("""^\s{4}"([A-Z]{2})":""", RegexOption.MULTILINE).findAll(body).map { it.groupValues[1] }.toSet()
        }
        val fetched = keys("FETCHERS")
        val none = keys("NO_LIST")
        assertEquals(setOf("NJ", "OH", "MO", "DC"), none)
        assertEquals(15, fetched.size)
        assertEquals(none, Coverage.STATES.filter { it.entry(Item.AVOID_DOT_CAMERAS).level == Level.NONE }.map { it.code }.toSet())
        assertEquals(fetched, Coverage.STATES.filter { it.entry(Item.AVOID_DOT_CAMERAS).level == Level.FULL }.map { it.code }.toSet())
    }

    @Test
    fun `the lines Settings shows`() {
        assertEquals(
            listOf(
                "No incidents and closures, traffic cameras, message signs or avoiding DOT cameras: 511NJ refuses GitHub's servers, " +
                    "where the app's feeds are studied and its maps built.",
                "Businesses and house numbers in search: Northern New Jersey has Overture's places; house numbers from OpenStreetMap alone.",
                "Gas price: the Central Atlantic average (choose NJ under Fuel).",
            ),
            Coverage.lines(Coverage.of("NJ")!!),
        )
        assertEquals(listOf("Traffic from CTroads.", "Nothing missing beyond what most states lack."), Coverage.lines(Coverage.of("CT")!!))
        assertEquals(
            listOf(
                "Traffic from OHGO.",
                "No avoiding DOT cameras: OHGO's camera list needs a key, and the maps are built without one.",
                "Incidents and closures, traffic cameras and message signs: only with your free OHGO key (Settings > Live data).",
            ),
            Coverage.lines(Coverage.of("OH")!!),
        )
        val ny = Coverage.lines(Coverage.of("NY")!!)
        assertEquals("Traffic from 511NY.", ny[0])
        assertTrue(ny.toString(), "Traffic speeds: New York City's (NYC DOT)." in ny && "Transit: New York City's subway and buses." in ny)
        assertTrue("No message signs: NDDOT publishes no message signs." in Coverage.lines(Coverage.of("ND")!!))
        val common = Coverage.common()
        assertEquals("Every state: map, directions and search; weather.", common[0])
        assertTrue(common.toString(), common.any { it.startsWith("Traffic speeds: New York City only") })
    }

    @Test
    fun `the summary and the picker's notes`() {
        assertEquals(
            listOf(
                "No traffic feed: New Jersey, DC and Missouri.",
                "Traffic only with your own key: Ohio.",
                "No avoiding DOT cameras: New Jersey, DC, Ohio and Missouri.",
                "No message signs: Michigan, North Dakota and South Dakota.",
                "Traffic speeds and transit: New York City only.",
            ),
            Coverage.summary(),
        )
        assertEquals("No traffic feed for DC (Settings > What each state has)", Coverage.pickerNote("Maryland, Delaware and DC"))
        assertEquals("No traffic feed (Settings > What each state has)", Coverage.pickerNote("New Jersey"))
        assertEquals("No traffic feed (Settings > What each state has)", Coverage.pickerNote("Missouri"))
        assertEquals("Traffic needs your free OHGO key (Settings > Live data)", Coverage.pickerNote("Ohio"))
        assertEquals(null, Coverage.pickerNote("Pennsylvania"))
        assertEquals(null, Coverage.pickerNote("Michigan"))
    }

    private fun pipelineDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = dir.resolve("pipeline")
            if (candidate.resolve("regions.json").isFile) return candidate
            dir = dir.parentFile
        }
        error("pipeline/ not found above ${System.getProperty("user.dir")}")
    }
}
