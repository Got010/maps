package app.southbound.core.packs

/**
 * A name another app chose (a shared file's name, an app's own label), made safe to show inside Southbound's own
 * question: one line, no control, format or bidi characters (so it can't add lines, reorder text or hide parts of
 * itself), at most [MAX_CHARS] characters with an ellipsis. Null when nothing printable is left.
 */
object ShownName {
    const val MAX_CHARS = 60
    private const val ELLIPSIS = "\u2026"

    fun clean(raw: String?, max: Int = MAX_CHARS): String? {
        if (raw == null) return null
        val points = ArrayList<Int>()
        var pendingSpace = false
        raw.codePoints().forEach { cp ->
            when (Character.getType(cp).toByte()) {
                // Bidi controls and isolates, zero-width characters, and anything with no glyph of its own: dropped.
                Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> Unit
                // Newlines, tabs and other controls, and every kind of space: one plain space between words.
                Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR ->
                    pendingSpace = points.isNotEmpty()
                else -> {
                    if (pendingSpace) points.add(' '.code)
                    pendingSpace = false
                    points.add(cp)
                }
            }
        }
        if (points.isEmpty()) return null
        val kept = if (points.size > max) points.subList(0, max - 1) else points
        val text = buildString { kept.forEach { appendCodePoint(it) } }.trimEnd()
        return if (points.size > max) text + ELLIPSIS else text
    }
}
