package app.southbound.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsIndexTest {
    private val sources = File("src/main/kotlin/app/southbound/feature/settings").walk().filter { it.extension == "kt" }.toList()
    private val indexSource = sources.single { it.name == "SettingsIndex.kt" }.readText()

    /** The English strings by name, unescaped as Android reads them. */
    private val strings: Map<String, String> = Regex("""<string name="(\w+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(File("src/main/res/values/strings.xml").readText())
        .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"") }

    /** A string resource's text, through R's field of that name. */
    private val text: (Int) -> String = { id ->
        val name = R.string::class.java.fields.first { it.getInt(null) == id }.name
        strings.getValue(name)
    }

    private fun titles(vararg query: String) = query.associateWith { q ->
        SettingsIndex.search(q, text).map { entry -> R.string::class.java.fields.first { it.getInt(null) == entry.title }.name }
    }

    @Test
    fun `every titled control is in the index, so search finds it`() {
        val calls = listOf(
            Regex("""\bToggle\(\s*(?:title\s*=\s*)?R\.string\.(\w+)"""),
            Regex("""\bChoice\(\s*(?:title\s*=\s*)?R\.string\.(\w+)"""),
            Regex("""\bSettingsNumberField\(\s*R\.string\.(\w+)"""),
            Regex("""\bKeyField\(\s*secrets,\s*ApiKey\.\w+,\s*R\.string\.(\w+)"""),
            // Each state's traffic switch, and its key's field.
            Regex("""\bTrafficFeed\(\s*"\w+",\s*NetworkFeature\.\w+,\s*R\.string\.(\w+)"""),
            Regex("""\bApiKey\.\w+,\s*R\.string\.(\w+),\s*R\.string\.\w+,\s*\)"""),
        )
        // Shown only while an older backup has turned it on, to turn it off: nothing to look for.
        val unlisted = setOf("live_tomtom_old")
        val titles = sources.filter { it.name != "SettingsIndex.kt" }
            .flatMap { file -> calls.flatMap { call -> call.findAll(file.readText()).map { it.groupValues[1] } } }
            .toSet() - unlisted
        assertTrue("found too few controls: $titles", titles.size > 50)
        val missing = titles.filter { "R.string.$it," !in indexSource && "R.string.$it\n" !in indexSource }
        assertEquals("not in SettingsIndex: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `each setting is listed once`() {
        val titles = SettingsIndex.entries.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `search matches the start of each word typed, in any case`() {
        val found = titles("chime", "OHGO", "mpg", "plate read", "gas", "  ", "zzz")
        assertEquals(listOf("voice_instruction_chime", "voice_alert_chime"), found["chime"])
        assertEquals(listOf("live_ohgo", "live_ohgo_key"), found["OHGO"])
        assertEquals(listOf("driving_mpg"), found["mpg"])
        assertEquals(listOf("voice_alpr_cameras"), found["plate read"])
        assertTrue(found.getValue("gas").containsAll(listOf("driving_gas_price", "live_gas", "live_gas_state")))
        assertEquals(emptyList<String>(), found["  "])
        assertEquals(emptyList<String>(), found["zzz"])
    }

    @Test
    fun `a group's name finds what is in it`() {
        val alerts = titles("alerts").getValue("alerts")
        assertTrue(alerts.containsAll(listOf("voice_speed_cameras", "voice_school_zones", "voice_reroute")))
    }
}
