package app.southbound.core.export

import app.southbound.core.userdata.Sealer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The commit point of an import. Once a bundle has fully verified and its contents have been checked, its entries
 * are written here, sealed under this phone's Keystore like the saved places file (never plaintext on disk), by a
 * temp file, fsync and atomic rename. Before that rename nothing has changed; after it the import is decided, and
 * applying it to the stores is repeatable, so a stop in the middle is finished at the next start ([BackupService.resume]).
 * The file is deleted when the import has been applied.
 */
class ImportJournal(private val file: File, private val sealer: Sealer) {

    fun exists(): Boolean = file.exists()

    /**
     * Writes [entries] (the verified bundle entries) as the pending import; the rename is the commit. [mark] (a
     * digest of the saved places before the import, BackupService) is kept beside it, sealed too, and written first,
     * so a pending import always has the mark it was written with.
     */
    fun write(entries: Map<String, ByteArray>, mark: String? = null) {
        val markFile = markFile()
        if (mark == null) {
            markFile.delete()
        } else {
            writeAtomically(markFile, sealer.seal(mark.toByteArray(Charsets.US_ASCII)))
        }
        val names = entries.keys.associateWith { it.toByteArray(Charsets.US_ASCII) }
        // Framed straight into one array, which is zeroed once sealed (a stream's internal copy could not be).
        val framed = ByteArray(8 + entries.entries.sumOf { (name, bytes) -> 2 + names.getValue(name).size + 4 + bytes.size })
        val buffer = ByteBuffer.wrap(framed)
        buffer.putInt(MAGIC).putInt(entries.size)
        for ((name, bytes) in entries) {
            val nameBytes = names.getValue(name)
            buffer.putShort(nameBytes.size.toShort()).put(nameBytes).putInt(bytes.size).put(bytes)
        }
        val sealed = try {
            sealer.seal(framed)
        } catch (e: Throwable) {
            framed.fill(0)
            throw e
        }
        // A sealer that returns its input (the tests' PlainSealer) must not have it zeroed.
        if (sealed !== framed) framed.fill(0)
        writeAtomically(file, sealed)
    }

    /** The [write] mark of the pending import, or null when there is none or it can't be opened. */
    fun mark(): String? = try {
        markFile().takeIf { it.exists() }?.let { String(sealer.open(it.readBytes()), Charsets.US_ASCII) }
    } catch (e: Exception) {
        null
    }

    private fun markFile() = File(file.parentFile, "${file.name}.mark")

    /** [bytes] into [target] by a temp file, fsync and atomic rename. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(target.parentFile)
        } catch (e: Exception) {
            temp.delete()
            throw if (e is IOException) e else IOException("Couldn't save the pending import", e)
        }
    }

    /** The pending entries, or null when there are none. Throws when the file is there but can't be opened. */
    fun read(): LinkedHashMap<String, ByteArray>? {
        if (!file.exists()) return null
        val plain = sealer.open(file.readBytes())
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            val buffer = ByteBuffer.wrap(plain)
            if (buffer.getInt() != MAGIC || buffer.getInt() != SouthboundBundle.LIMITS.size) throw IOException("not a pending import")
            repeat(SouthboundBundle.LIMITS.size) {
                val nameBytes = ByteArray(buffer.getShort().toInt() and 0xFFFF)
                buffer.get(nameBytes)
                val name = String(nameBytes, Charsets.US_ASCII)
                val limit = SouthboundBundle.LIMITS[name] ?: throw IOException("not a pending import")
                val length = buffer.getInt()
                if (length < 0 || length > limit || length > buffer.remaining() || entries.containsKey(name)) {
                    throw IOException("not a pending import")
                }
                entries[name] = ByteArray(length).also { buffer.get(it) }
            }
            if (buffer.hasRemaining()) throw IOException("not a pending import")
            return entries
        } catch (e: BufferUnderflowException) {
            SouthboundBundle.wipe(entries)
            throw IOException("not a pending import")
        } catch (e: Throwable) {
            SouthboundBundle.wipe(entries)
            throw e
        } finally {
            plain.fill(0)
        }
    }

    /** Deletes the journal. A failure to delete is an IOException (the import would be applied again at the next start). */
    fun clear() {
        if (file.exists() && !file.delete()) throw IOException("Couldn't remove the pending import")
        File(file.parentFile, "${file.name}.part").delete()
        markFile().delete()
    }

    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Some filesystems refuse to open a directory; the rename itself has already happened.
        }
    }

    companion object {
        const val FILE_NAME = "import.pending"
        private const val MAGIC = 0x53424A31 // "SBJ1"

        /** The journal in [dir] (the no-backup directory, like the saved places file). */
        fun at(dir: File, sealer: Sealer): ImportJournal = ImportJournal(File(dir, FILE_NAME), sealer)

        /** Whether [dir] holds a kept import, without opening anything. */
        fun pendingIn(dir: File): Boolean = File(dir, FILE_NAME).exists()
    }
}
