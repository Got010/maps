package app.southbound.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `stringify round-trips what parse reads`() {
        val text = """{"a":[1,2.5,-3,true,false,null],"b":{"c":"quote \" backslash \\ newline \n tab \t"},"d":"é"}"""
        val parsed = MiniJson.parse(text)
        assertEquals(text, MiniJson.stringify(parsed))
        assertEquals(parsed, MiniJson.parse(MiniJson.stringify(parsed)))
    }

    @Test
    fun `whole doubles are written without a decimal point`() {
        assertEquals("[8,0.5,1.0E20]", MiniJson.stringify(listOf(8.0, 0.5, 1e20)))
        assertEquals("""{"zoom":14}""", MiniJson.stringify(mapOf("zoom" to 14L)))
    }

    @Test
    fun `control characters are escaped`() {
        assertEquals("\"a\\u0001b\"", MiniJson.stringify("a\u0001b"))
    }

    @Test
    fun `duplicate keys are refused only when asked`() {
        val text = """{"a":1,"b":{"c":true,"c":false},"a":2}"""
        assertEquals(2L, (MiniJson.parse(text) as Map<*, *>)["a"])
        for (bad in listOf(text, """{"a":null,"a":null}""", """[{"x":1,"x":1}]""")) {
            try {
                MiniJson.parse(bad, rejectDuplicateKeys = true)
                org.junit.Assert.fail("accepted $bad")
            } catch (expected: MiniJson.JsonException) {
            }
        }
        assertEquals(mapOf("a" to 1L, "b" to mapOf("a" to 2L)), MiniJson.parse("""{"a":1,"b":{"a":2}}""", rejectDuplicateKeys = true))
    }
}
