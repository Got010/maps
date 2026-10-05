package app.southbound.core.export

import app.southbound.core.export.fwx.FwxError
import app.southbound.core.export.fwx.FwxException
import app.southbound.core.network.MiniJson
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The one way contents Southbound can't use are refused: MALFORMED_PAYLOAD, with a note that holds no contents. */
internal fun invalid(detail: String): Nothing = throw FwxException(FwxError.MALFORMED_PAYLOAD, detail)

/** UTF-8 to text, refusing malformed bytes instead of replacing them. */
internal fun utf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    invalid("an entry is not valid UTF-8")
}

/** Parses one entry's JSON; any parser failure is a payload problem. */
internal fun parseJson(bytes: ByteArray, what: String): Any? = try {
    MiniJson.parse(utf8(bytes))
} catch (e: MiniJson.JsonException) {
    invalid("$what is not valid JSON")
}

/** No unpaired surrogate: such text does not survive being written as UTF-8. */
internal fun String.isWellFormed(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= length || !Character.isLowSurrogate(this[i + 1])) return false
            i += 2
        } else if (Character.isLowSurrogate(c)) {
            return false
        } else {
            i++
        }
    }
    return true
}

/**
 * A JSON object read strictly: exactly the [keys] expected (no more, no fewer), each of the type asked for.
 * Failures say which field, never what it held.
 */
internal class Obj private constructor(private val map: Map<*, *>, private val what: String) {

    fun bool(key: String): Boolean = map[key] as? Boolean ?: bad(key)

    fun string(key: String, maxLength: Int): String {
        val s = map[key] as? String ?: bad(key)
        if (s.length > maxLength || !s.isWellFormed()) bad(key)
        return s
    }

    fun stringOrNull(key: String, maxLength: Int): String? {
        if (!map.containsKey(key)) bad(key)
        return if (map[key] == null) null else string(key, maxLength)
    }

    fun long(key: String, min: Long, max: Long): Long {
        val n = map[key] as? Long ?: bad(key)
        if (n < min || n > max) bad(key)
        return n
    }

    fun double(key: String, min: Double, max: Double): Double {
        val n = (map[key] as? Number)?.toDouble() ?: bad(key)
        if (!n.isFinite() || n < min || n > max) bad(key)
        return n
    }

    fun obj(key: String, keys: Set<String>): Obj = of(map[key], "$what.$key", keys)

    fun list(key: String, maxSize: Int): List<*> {
        val l = map[key] as? List<*> ?: bad(key)
        if (l.size > maxSize) bad(key)
        return l
    }

    fun raw(key: String): Any? = map[key]

    inline fun <reified E : Enum<E>> enum(key: String): E {
        val name = string(key, 64)
        return enumValues<E>().firstOrNull { it.name == name } ?: bad(key)
    }

    inline fun <reified E : Enum<E>> enumOrNull(key: String): E? {
        if (raw(key) == null) {
            if (!has(key)) bad(key)
            return null
        }
        return enum<E>(key)
    }

    fun has(key: String): Boolean = map.containsKey(key)

    fun bad(key: String): Nothing = invalid("$what: field $key is missing or not allowed")

    companion object {
        fun of(value: Any?, what: String, keys: Set<String>): Obj {
            val m = value as? Map<*, *> ?: invalid("$what is not an object")
            if (m.keys != keys) invalid("$what does not have exactly the fields schema 1 lists")
            return Obj(m, what)
        }

        /** An object whose field names are not fixed (the API keys): the caller checks them. */
        fun open(value: Any?, what: String): Map<*, *> = value as? Map<*, *> ?: invalid("$what is not an object")
    }
}
