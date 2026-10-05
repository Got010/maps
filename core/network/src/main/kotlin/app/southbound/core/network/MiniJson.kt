package app.southbound.core.network

/**
 * A small, strict JSON reader for the two documents the updater reads (GitHub's release and the release
 * manifest), taken from the Tunnels updater: objects become
 * [Map]s, arrays [List]s, numbers [Long] when integral else [Double], plus [String], [Boolean] and null.
 * No dependency, so nothing new joins the build; nesting is capped so a hostile document cannot exhaust the stack.
 */
object MiniJson {
    const val MAX_DEPTH = 64

    class JsonException(message: String) : IllegalArgumentException(message)

    /**
     * The inverse of [parse]: writes maps, lists, strings, numbers, booleans
     * and null as compact JSON, so a parsed document can be edited and
     * written back (core/map does this to the bundled style).
     */
    fun stringify(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(value, out)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Double -> if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) {
                out.append(value.toLong())
            } else {
                out.append(value)
            }
            is Number -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> throw JsonException("can't write a ${value::class.java.simpleName}")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /**
     * @param rejectDuplicateKeys refuse an object that names a key twice (otherwise the last one wins, as in most
     * readers). Imported documents are read this way, since two readers could otherwise see different values;
     * the feeds the app reads keep the default.
     */
    fun parse(text: String, rejectDuplicateKeys: Boolean = false): Any? {
        val p = Parser(text, rejectDuplicateKeys)
        p.skipWhitespace()
        val value = p.value(0)
        p.skipWhitespace()
        if (p.pos != text.length) throw JsonException("trailing data at ${p.pos}")
        return value
    }

    private class Parser(val s: String, val rejectDuplicateKeys: Boolean) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos] in " \t\r\n") pos++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("nested deeper than $MAX_DEPTH")
            if (pos >= s.length) throw JsonException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("object key expected at $pos")
                val key = str()
                if (rejectDuplicateKeys && out.containsKey(key)) throw JsonException("duplicate key")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                out[key] = value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw JsonException("',' or '}' expected at $pos")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipWhitespace()
                out += value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw JsonException("',' or ']' expected at $pos")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("short \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw JsonException("bad \\u escape"))
                                pos += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw JsonException("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            digits()
            var integral = true
            if (peek() == '.') { integral = false; pos++; digits() }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                pos++
                if (peek() == '+' || peek() == '-') pos++
                digits()
            }
            val text = s.substring(start, pos)
            return if (integral) text.toLongOrNull() ?: text.toDouble() else text.toDoubleOrNull() ?: throw JsonException("bad number '$text'")
        }

        private fun digits() {
            val start = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == start) throw JsonException("digit expected at $pos")
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null

        private fun expect(c: Char) {
            if (peek() != c) throw JsonException("'$c' expected at $pos")
            pos++
        }
    }
}
