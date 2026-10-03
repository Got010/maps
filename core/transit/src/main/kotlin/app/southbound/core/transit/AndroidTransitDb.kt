package app.southbound.core.transit

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File

/** [TransitDb] over the bundled SQLite driver, read-only. */
class AndroidTransitDb private constructor(private val connection: SQLiteConnection) : TransitDb {

    @Synchronized
    override fun query(sql: String, args: List<Any>, each: (TransitRow) -> Unit) {
        connection.prepare(sql).use { statement ->
            args.forEachIndexed { i, arg ->
                when (arg) {
                    is Int -> statement.bindInt(i + 1, arg)
                    is Long -> statement.bindLong(i + 1, arg)
                    is Double -> statement.bindDouble(i + 1, arg)
                    else -> statement.bindText(i + 1, arg.toString())
                }
            }
            val row = StatementRow(statement)
            while (statement.step()) each(row)
        }
    }

    override fun close() = connection.close()

    private class StatementRow(private val s: SQLiteStatement) : TransitRow {
        override fun int(index: Int) = s.getInt(index)
        override fun double(index: Int) = s.getDouble(index)
        override fun text(index: Int): String? = if (s.isNull(index)) null else s.getText(index)
        override fun blob(index: Int): ByteArray = s.getBlob(index)
        override fun isNull(index: Int) = s.isNull(index)
    }

    companion object {
        /** Opens a transit pack. Throws if [file] isn't one this app can read. */
        fun open(file: File): TransitPack {
            val connection = BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY)
            try {
                val appId = connection.prepare("PRAGMA application_id").use { if (it.step()) it.getLong(0) else 0L }
                require(appId == TransitPack.APPLICATION_ID) { "${file.name} is not a transit pack" }
                val version = connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0L }
                require(version == TransitPack.SCHEMA_VERSION.toLong()) {
                    "${file.name} is transit pack version $version; this app reads ${TransitPack.SCHEMA_VERSION}"
                }
                return TransitPack(AndroidTransitDb(connection))
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }
    }
}
