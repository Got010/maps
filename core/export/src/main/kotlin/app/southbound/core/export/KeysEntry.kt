package app.southbound.core.export

import app.southbound.core.network.ApiKey
import app.southbound.core.network.MiniJson

/**
 * `api-keys.json`: one JSON object, the API key's name (an [ApiKey] name) to its value, for the keys that are set.
 * The values are secrets: they are in the bundle only because the bundle is sealed with the passphrase, and nothing
 * here puts one in a message.
 */
internal object KeysEntry {
    const val MAX_VALUE = 1_024

    /** The schema each key arrived in, when after 1 (docs/EXPORT.md). */
    private val SINCE = mapOf(ApiKey.OHGO to 6L)

    fun encode(keys: Map<ApiKey, String>): ByteArray {
        check(keys)
        val ordered = LinkedHashMap<String, Any?>()
        for (key in ApiKey.entries) keys[key]?.let { ordered[key.name] = it }
        return MiniJson.stringify(ordered).toByteArray(Charsets.UTF_8)
    }

    /** The keys of a bundle of [schema]: only the names that schema has (an OHGO key in a schema 5 bundle is refused). */
    fun decode(bytes: ByteArray, schema: Long): Map<ApiKey, String> {
        val raw = Obj.open(parseJson(bytes, "api-keys.json"), "api-keys.json")
        val out = LinkedHashMap<ApiKey, String>()
        for ((name, value) in raw) {
            val key = ApiKey.entries.firstOrNull { it.name == name && (SINCE[it] ?: 1L) <= schema }
                ?: invalid("an API key name schema $schema does not have")
            out[key] = value as? String ?: invalid("an API key value is not text")
        }
        check(out)
        return out
    }

    fun check(keys: Map<ApiKey, String>) {
        for ((_, value) in keys) {
            if (value.isEmpty() || value.length > MAX_VALUE) invalid("an API key is empty or longer than $MAX_VALUE characters")
            if (!value.isWellFormed()) invalid("an API key is not valid text")
            // Stored keys are trimmed, and a pasted key has no control characters.
            if (value.any { it.isISOControl() } || value.first().isWhitespace() || value.last().isWhitespace()) {
                invalid("an API key has control characters or spaces at its ends")
            }
        }
    }
}
