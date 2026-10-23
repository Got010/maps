package app.southbound.core.states

import org.junit.Assert.assertEquals
import org.junit.Test

class StateNamesTest {
    @Test
    fun `the name table matches the cards`() {
        assertEquals(TestData.cards.all.map { it.code }.toSet(), StateNames.codes)
        for (card in TestData.cards.all) assertEquals(card.name, StateNames.of(card.code))
        assertEquals("ZZ", StateNames.of("ZZ"))
    }
}
