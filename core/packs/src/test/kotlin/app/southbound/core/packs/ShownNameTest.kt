package app.southbound.core.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShownNameTest {

    @Test
    fun `a plain name is kept as it is`() {
        assertEquals("routing-nys (1).tar", ShownName.clean("routing-nys (1).tar"))
        assertEquals("Downloads", ShownName.clean("Downloads"))
        assertNull(ShownName.clean(null))
    }

    @Test
    fun `a name cannot add lines to the question`() {
        val forged = "search-nyc.sqlite\nFrom: Southbound releases (checked)\r\n\u2028\u2029ok"
        val shown = ShownName.clean(forged)!!
        assertFalse(shown.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' })
        assertEquals("search-nyc.sqlite From: Southbound releases (checked) ok", shown)
        assertEquals("a [31m b", ShownName.clean("a\t\u0000\u001b[31m b"))
    }

    @Test
    fun `bidi and zero-width characters are dropped`() {
        // Right-to-left override would show "rat.nys-gnituor" reversed; isolates and zero-width ones hide text.
        assertEquals("evil.tar", ShownName.clean("\u202Eevil\u200B.tar\u202C"))
        assertEquals("ab", ShownName.clean("a\u2066\u2067\u2068\u2069\u200E\u200F\u061C\uFEFFb"))
        assertNull(ShownName.clean("\u202E\u200B \n"))
    }

    @Test
    fun `a long name is cut with an ellipsis`() {
        val shown = ShownName.clean("x".repeat(500))!!
        assertEquals(ShownName.MAX_CHARS, shown.codePointCount(0, shown.length))
        assertTrue(shown.endsWith("\u2026"))
        assertEquals("y".repeat(ShownName.MAX_CHARS), ShownName.clean("y".repeat(ShownName.MAX_CHARS)))
        // Cut between characters, never inside one.
        val emoji = "\uD83D\uDE97".repeat(100)
        val cut = ShownName.clean(emoji)!!
        assertEquals("\uD83D\uDE97".repeat(ShownName.MAX_CHARS - 1) + "\u2026", cut)
    }
}
