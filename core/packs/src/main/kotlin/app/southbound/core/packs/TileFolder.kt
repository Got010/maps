package app.southbound.core.packs

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * The routing graph on the phone, from southbound-v2.json's routing packs: Valhalla's tile files unpacked from
 * routing-main.tar and the packs naming the phone's regions into one folder, which the router reads
 * (ValhallaConfigFactory.usingTilesDir).
 *
 * A release's packs come from one graph, and packs from two builds don't mix: a tile names its neighbours'
 * edges by id. So every change is made whole in a new generation folder ([begin]): packs unchanged since the
 * current one are hard-linked from it ([Generation.keep]), the others unpacked from their downloads
 * ([Generation.unpack]), and only when all are in does [Generation.commit] switch the `current` pointer to it.
 * The router opens [current]; an older generation is deleted once no router reads it ([prune]).
 *
 * Under `<filesDir>/routing/`: `current` (the generation's folder name), and one folder per generation, `g<n>`,
 * holding the tiles (`2/000/756/425.gph`) and `packs.tsv`: one line per pack, its file name, SHA-256, size in
 * bytes and tiles.
 */
class TileFolder(filesDir: File) {

    val root: File = File(filesDir, DIR)
    private val pointer = File(root, POINTER)

    /** The generation the router should open, or null before any routing pack is in. */
    fun current(): File? {
        val name = runCatching { pointer.readText().trim() }.getOrNull() ?: return null
        if (!GENERATION.matches(name)) return null
        return File(root, name).takeIf { File(it, RECORDS).isFile }
    }

    /** The packs in the current generation: file name (`routing-main.tar`) to SHA-256. */
    fun installed(): Map<String, String> = records(current()).mapValues { it.value.sha256 }

    /** How many packs the current generation holds, their tiles' bytes and when it was made; null without one. */
    fun summary(): Summary? {
        val dir = current() ?: return null
        val records = records(dir)
        return Summary(records.size, records.values.sumOf { it.bytes }, File(dir, RECORDS).lastModified())
    }

    data class Summary(val packs: Int, val bytes: Long, val modified: Long)

    /**
     * Starts a new generation next to the current one; [Generation.commit] makes it current, [Generation.abandon]
     * drops it. Until then no [prune] deletes it, from this TileFolder or another on the same folder.
     */
    fun begin(): Generation {
        root.mkdirs()
        var n = System.currentTimeMillis()
        var dir: File
        do {
            dir = File(root, "g${n++}")
        } while (dir.exists())
        // Registered before the folder exists, so a prune listing the folder in between can't take it.
        synchronized(building) { building += dir.absolutePath }
        if (!dir.mkdirs()) {
            synchronized(building) { building -= dir.absolutePath }
            throw IOException("Couldn't make $dir")
        }
        val previous = current()
        return Generation(dir, previous, records(previous))
    }

    /**
     * Deletes every generation but the current one, [inUse], the one the open router reads ([reading]: older than
     * the current one while a trip keeps its router) and those a round in this process is still filling ([begin]);
     * an unfinished one a stopped process left behind goes too. The router opens on the map screen while the
     * updater runs in the same process with a TileFolder of its own, so the round's generation must survive the
     * router's prune, and the router's generation the round's, however many rounds a trip spans.
     */
    fun prune(inUse: File? = null) {
        val keep = setOfNotNull(current()?.name, inUse?.name)
        root.listFiles()?.forEach { f ->
            if (f.isDirectory && GENERATION.matches(f.name) && f.name !in keep && !isBuilding(f) && !isRead(f)) f.deleteRecursively()
        }
    }

    /**
     * Records [dir] as the generation the open router reads (null: none, or a one-graph pack), for every
     * TileFolder in the process: no [prune] deletes it until another takes its place.
     */
    fun reading(dir: File?) {
        synchronized(building) { readPath = dir?.absolutePath }
    }

    /** Deletes every generation and the pointer: no routing from packs. */
    fun clear() {
        pointer.delete()
        prune()
    }

    /** A generation being filled; see [TileFolder]. */
    inner class Generation internal constructor(
        val dir: File,
        private val previous: File?,
        private val previousRecords: Map<String, Record>,
    ) {
        private val records = LinkedHashMap<String, Record>()

        /**
         * Hard-links pack [name]'s tiles from the current generation when it's there with [sha256]; false
         * (nothing linked) otherwise, and the pack must be downloaded and [unpack]ed.
         */
        fun keep(name: String, sha256: String): Boolean {
            val record = previousRecords[name]?.takeIf { it.sha256 == sha256 } ?: return false
            val from = previous ?: return false
            for ((i, tile) in record.tiles.withIndex()) {
                val source = File(from, tile)
                if (!source.isFile) {
                    // Unlink what was linked: [unpack] is next, and must not find the current generation's files here.
                    for (linked in record.tiles.subList(0, i)) File(dir, linked).delete()
                    return false
                }
                val target = File(dir, tile)
                target.parentFile?.mkdirs()
                target.delete()
                try {
                    Files.createLink(target.toPath(), source.toPath())
                } catch (e: Exception) {
                    source.copyTo(target, overwrite = true)
                }
            }
            records[name] = record
            return true
        }

        /** Unpacks routing pack [name] (a tar of tile files, already checked to be [sha256]) into this generation. */
        fun unpack(name: String, sha256: String, tar: File) {
            val tiles = tar.inputStream().buffered().use { untar(it, dir) }
            records[name] = Record(sha256, tiles.sumOf { File(dir, it).length() }, tiles)
        }

        /**
         * Writes the pack list and makes this generation current, in one rename. Refused when a tile the list
         * names isn't here: a generation that lost tiles would route without them and, its list matching the
         * manifest, never be replaced.
         */
        fun commit() {
            for ((name, record) in records) {
                if (record.tiles.any { !File(dir, it).isFile }) throw IOException("Tiles of $name went missing before the routing switched over")
            }
            File(dir, RECORDS).writeText(
                records.entries.joinToString("") { (name, r) -> "$name\t${r.sha256}\t${r.bytes}\t${r.tiles.joinToString(",")}\n" },
            )
            val temp = File(root, "$POINTER.tmp")
            temp.writeText(dir.name)
            if (!temp.renameTo(pointer)) throw IOException("Couldn't switch the routing tiles")
            synchronized(building) { building -= dir.absolutePath }
        }

        /** Drops this generation unfinished. */
        fun abandon() {
            dir.deleteRecursively()
            synchronized(building) { building -= dir.absolutePath }
        }
    }

    data class Record(val sha256: String, val bytes: Long, val tiles: List<String>)

    private fun records(generation: File?): Map<String, Record> {
        val file = generation?.let { File(it, RECORDS) }?.takeIf { it.isFile } ?: return emptyMap()
        return file.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            parts[0] to Record(parts[1], parts[2].toLongOrNull() ?: 0L, parts[3].split(',').filter { it.isNotEmpty() })
        }.toMap()
    }

    private fun isBuilding(dir: File): Boolean = synchronized(building) { dir.absolutePath in building }

    private fun isRead(dir: File): Boolean = synchronized(building) { dir.absolutePath == readPath }

    companion object {
        const val DIR = "routing"

        /** Generations a round in this process is filling ([begin]), by path, for every TileFolder. */
        private val building = HashSet<String>()

        /** The generation the open router reads ([reading]), by path; guarded by [building]'s lock. */
        private var readPath: String? = null

        private const val POINTER = "current"
        private const val RECORDS = "packs.tsv"
        private val GENERATION = Regex("g[0-9]+")

        /** A Valhalla tile path: level, then the id in groups of three digits. */
        private val TILE = Regex("[0-2](/[0-9]{3})+\\.gph")
        private const val BLOCK = 512

        /**
         * Writes the regular files of a ustar archive under [dir] and returns their paths. Only Valhalla tile
         * paths are accepted ([TILE]), so a pack can't write outside its folder.
         */
        internal fun untar(input: InputStream, dir: File): List<String> {
            val header = ByteArray(BLOCK)
            val tiles = ArrayList<String>()
            while (true) {
                // A tar ends with zero blocks; the end of the file before one means the pack was cut short.
                if (!readFully(input, header)) throw UnrecognizedPackException("A routing pack ends before its end")
                if (header.all { it == 0.toByte() }) break
                val name = field(header, 0, 100)
                val prefix = field(header, 345, 155)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val size = field(header, 124, 12).trim().ifEmpty { "0" }.toLongOrNull(8)
                    ?: throw UnrecognizedPackException("A routing pack has a damaged file header")
                val type = header[156]
                val regular = type == '0'.code.toByte() || type == 0.toByte()
                if (regular) {
                    if (!TILE.matches(path)) throw UnrecognizedPackException("Unexpected file $path in a routing pack")
                    val target = File(dir, path)
                    target.parentFile?.mkdirs()
                    // A new file, never through a hard link into another generation's copy.
                    target.delete()
                    target.outputStream().use { out -> copyExactly(input, out, size) }
                    tiles += path
                } else {
                    skip(input, size)
                }
                skip(input, (BLOCK - size % BLOCK) % BLOCK)
            }
            return tiles
        }

        private fun field(header: ByteArray, offset: Int, length: Int): String {
            var end = offset
            while (end < offset + length && header[end] != 0.toByte()) end++
            return String(header, offset, end - offset, Charsets.US_ASCII)
        }

        private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
            var total = 0
            while (total < buffer.size) {
                val n = input.read(buffer, total, buffer.size - total)
                if (n < 0) {
                    if (total == 0) return false
                    throw UnrecognizedPackException("A routing pack ends mid-file")
                }
                total += n
            }
            return true
        }

        private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
            val buffer = ByteArray(1 shl 16)
            var left = size
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw UnrecognizedPackException("A routing pack ends mid-file")
                out.write(buffer, 0, n)
                left -= n
            }
        }

        /** Reads past [count] bytes (InputStream.skip may run past the end of a file without saying so). */
        private fun skip(input: InputStream, count: Long) {
            val buffer = ByteArray(BLOCK)
            var left = count
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw UnrecognizedPackException("A routing pack ends mid-file")
                left -= n
            }
        }
    }
}
