package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchTextTest {

    /**
     * The pipeline's normalize.py runs the same cases, so a query typed on
     * the phone normalises exactly like the names in the search pack.
     */
    @Test
    fun matchesPipelineNormalisation() {
        val cases = parsePairs(File("../../pipeline/search/normalize_cases.json").readText())
        assertTrue("expected the shared cases", cases.size >= 10)
        for ((raw, expected) in cases) {
            assertEquals(raw, expected, SearchText.normalize(raw))
        }
    }

    // The cases file is a JSON array of [input, expected] string pairs.
    private fun parsePairs(json: String): List<Pair<String, String>> {
        val string = "\"((?:[^\"\\\\]|\\\\.)*)\""
        return Regex("\\[\\s*$string\\s*,\\s*$string\\s*]").findAll(json).map {
            unescape(it.groupValues[1]) to unescape(it.groupValues[2])
        }.toList()
    }

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
}
