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
}
