package app.southbound.core.states

import app.southbound.core.network.MiniJson

/**
 * One line of a state's card: what [label] says there. [sources] are the pages the value was compiled from, [asOf]
 * the date it was compiled (a day, `2026-10-23`). The text is informational: nothing in it is advice.
 */
data class Field(
    val key: String,
    val label: String,
    val value: String,
    val sources: List<String>,
    val asOf: String,
) {
    /** The compiler could not confirm this value against a primary source; the card shows a small "verify" tag. */
    val needsVerify: Boolean get() = VERIFY.containsMatchIn(value)

    private companion object {
        val VERIFY = Regex("\\bverify\\b", RegexOption.IGNORE_CASE)
    }
}

/** What a state says about the road: [fields] in the card's order, [StateCards.FIELDS]. */
data class StateCard(val code: String, val name: String, val fields: List<Field>) {
    fun field(key: String): Field? = fields.firstOrNull { it.key == key }
}

/**
 * The cards of the 50 states and DC, keyed by postal code, from `state-cards.json`. Static data, held in memory
 * once; looking one up leaves no trace.
 */
class StateCards private constructor(private val byCode: Map<String, StateCard>) {

    /** Every card, in the file's order. */
    val all: List<StateCard> get() = byCode.values.toList()

    operator fun get(code: String?): StateCard? = code?.let { byCode[it] }

    companion object {
        /** The twelve fields of a card, in order, with the label each is shown under. */
        val FIELDS: List<Pair<String, String>> = listOf(
            "time_zone" to "Time zone",
            "handheld_phone" to "Handheld phone",
            "speed_limits" to "Speed limits",
            "tolls" to "Tolls",
            "state_police" to "State police short code",
            "move_over" to "Move over",
            "radar_detector" to "Radar detectors",
            "open_container" to "Open container",
            "cannabis" to "Cannabis",
            "sales_tax" to "Sales tax",
            "rest_area_overnight" to "Rest-area overnight",
            "daylight_time" to "Daylight time",
        )

        /** Fields shown first on the "Welcome to" sheet, and the ones the spoken line reads when they changed. */
        private val SPOKEN_KEYS = listOf("handheld_phone", "speed_limits")
        private const val SPOKEN_CLAUSE_MAX = 90

        /**
         * Reads the JSON text: an object of postal code to record, each record with `name`, the twelve [FIELDS],
         * `sources` (a list of text) and `as_of`. Throws [IllegalArgumentException] if anything is missing,
         * empty or not text.
         */
        fun parse(json: String): StateCards {
            val root = try {
                MiniJson.parse(json)
            } catch (e: MiniJson.JsonException) {
                throw IllegalArgumentException("state cards are not JSON: ${e.message}")
            }
            val records = root as? Map<*, *> ?: bad("not an object")
            val cards = LinkedHashMap<String, StateCard>()
            for ((code, record) in records) {
                if (code !is String || code.isEmpty()) bad("a record has no postal code")
                val r = record as? Map<*, *> ?: bad("$code is not an object")
                fun text(key: String): String = (r[key] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: bad("$code has no $key")
                val sources = (r["sources"] as? List<*> ?: bad("$code has no sources")).map { it as? String ?: bad("$code has a source that is not text") }
                val asOf = text("as_of")
                cards[code] = StateCard(code, text("name"), FIELDS.map { (key, label) -> Field(key, label, text(key), sources, asOf) })
            }
            if (cards.isEmpty()) bad("no records")
            return StateCards(cards)
        }

        /** The fields of [to] whose text differs from [from]'s: what changes on crossing from one to the other. */
        fun diff(from: StateCard, to: StateCard): List<Field> = to.fields.filter { field -> from.field(field.key)?.value != field.value }

        /**
         * One line to speak on entering [card], from the fields in [changed] only: "Entering Ohio. Handheld phone use
         * banned for all drivers. 70 interstate rural, 65 urban." Each is the first clause of its field, and a clause
         * that still needs checking ("verify") is left out rather than read aloud.
         */
        fun spoken(card: StateCard, changed: List<Field>): String {
            val parts = SPOKEN_KEYS.mapNotNull { key ->
                changed.firstOrNull { it.key == key && !it.needsVerify }?.let { clause(it.value) }
            }
            return (listOf("Entering ${card.name}") + parts).joinToString(". ") + "."
        }

        private fun clause(value: String): String? {
            val first = value.substringBefore(';').substringBefore('(').trim().trimEnd('.', ',', ' ')
            if (first.isEmpty()) return null
            if (first.length <= SPOKEN_CLAUSE_MAX) return first
            return first.take(SPOKEN_CLAUSE_MAX).substringBeforeLast(' ')
        }

        private fun bad(why: String): Nothing = throw IllegalArgumentException("state cards: $why")
    }
}
