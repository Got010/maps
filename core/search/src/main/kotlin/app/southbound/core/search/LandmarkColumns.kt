package app.southbound.core.search

/**
 * The `landmark` table's optional columns, kept apart from [SearchIndex] so the rules are
 * tested on the JVM without a SQLite file.
 */
internal object LandmarkColumns {

    /** The select-list term for a landmark's direction: the column when [columns] has it, else NULL (an older pack). */
    fun directionSelect(columns: Set<String>): String = if ("direction" in columns) "direction" else "NULL"

    /** A stored direction as 0-359; the pipeline writes it that way already, this only guards a stray value. */
    fun normalDirection(raw: Long): Int = Math.floorMod(raw, 360L).toInt()
}
