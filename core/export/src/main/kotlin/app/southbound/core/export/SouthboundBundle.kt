package app.southbound.core.export

import app.southbound.core.export.fwx.Fwx
import app.southbound.core.export.fwx.FwxError
import app.southbound.core.export.fwx.FwxException
import app.southbound.core.export.fwx.FwxHeader
import app.southbound.core.export.fwx.FwxReader
import app.southbound.core.export.fwx.FwxWriter
import app.southbound.core.network.MiniJson
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Southbound's export: an FWX v1 bundle (the codec in `fwx/`) with app ID `southbound`, schema 4 (schemas 1 to 3 are
 * still read). The entries, in
 * this order and all required: [MANIFEST], [PLACES], [API_KEYS], [SETTINGS]. docs/EXPORT.md lists what each holds.
 * The bundle is sealed with the passphrase alone, so it opens on a phone that has none of this phone's Keystore keys.
 *
 * Export and import share every rule here: [encode] builds the entries, [decode] checks them (export runs it before
 * writing and on the file it just wrote, import runs it on what it read), and sizes are checked against the same
 * [LIMITS], so Southbound never writes a bundle that it would refuse to read.
 *
 * Schema 1 is these four entries; a new entry, a new ApiKey or NetworkFeature, or a change to any entry's format
 * bumps it, and the importer then declares the range it supports (with a tested migration for the older ones).
 * Schema 2 (October 2026) added the route setting `avoid_dot_cameras`; a schema 1 bundle imports with it off.
 * Schema 3 added the `WEATHER` toggle, schema 4 `TRAFFIC_511PA` and `TRAFFIC_CTROADS`, schema 5 `TRAFFIC_CHART_MD` and
 * `TRAFFIC_DELDOT`, schema 6 `TRAFFIC_511WI` and `TRAFFIC_TRAVEL_MIDWEST`; an older bundle imports with them off.
 * Schema 7 added the driving setting `search_at_bottom`; an older bundle imports with the search bar at the bottom.
 */
object SouthboundBundle {
    const val APP_ID = "southbound"
    const val SCHEMA = 7L
    val SCHEMA_RANGE = 1L..SCHEMA

    /** The most plaintext an import reads (spec section 4.3); the limits below add up to less. */
    const val MAX_STREAM_BYTES = 9L shl 20

    const val MANIFEST = "manifest.json"
    const val PLACES = "places.json"
    const val API_KEYS = "api-keys.json"
    const val SETTINGS = "settings.json"

    /** Every entry in the order it is written, with the most it may hold. */
    internal val LIMITS: Map<String, Int> = linkedMapOf(
        MANIFEST to (4 shl 10),
        PLACES to (8 shl 20),
        API_KEYS to (16 shl 10),
        SETTINGS to (64 shl 10),
    )

    private val ENTRY_NAMES = LIMITS.keys.toList()

    private val MANIFEST_KEYS = listOf(
        "app", "schema", "app_version", "lists", "saved_places", "recents", "home", "work", "api_keys", "settings",
    )

    /** What the error messages call the other apps (the FWX app ID registry, spec section 7). */
    private val APP_NAMES = mapOf(
        "tunnels" to "Tunnels",
        "lumen" to "Lumen",
        "mardigras" to "Mardi Gras",
        "pusher" to "Pusher",
        "pusher-server" to "Pusher server",
        "prikey" to "Prikey",
        APP_ID to "Southbound",
    )

    fun appName(appId: String): String = APP_NAMES[appId] ?: appId

    /** The name an export is offered under, from its UTC time: southbound-20261004-120000Z.fwx. */
    fun fileName(createdMs: Long): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "$APP_ID-${format.format(Date(createdMs))}Z.fwx"
    }

    /** The entries of an export of [data], manifest first. Throws FwxException(MALFORMED_PAYLOAD) if [data] breaks a rule. */
    fun encode(data: BackupData, appVersion: String): LinkedHashMap<String, ByteArray> {
        val counts = data.counts()
        return linkedMapOf(
            MANIFEST to manifest(counts, appVersion),
            PLACES to PlacesEntry.encode(data.userData),
            API_KEYS to KeysEntry.encode(data.apiKeys),
            SETTINGS to SettingsEntry.encode(data.settings),
        )
    }

    /**
     * Checks a whole set of entries and turns them into data: each entry parses with the exact fields of its schema,
     * every value is inside its range, and the manifest's counts match what the entries hold. Anything wrong is
     * FwxException(MALFORMED_PAYLOAD). Nothing is applied here.
     */
    fun decode(entries: Map<String, ByteArray>): BackupData {
        if (entries.keys.toList() != ENTRY_NAMES) invalid("the entries are not the four Southbound entries")
        val manifest = Obj.of(parseJson(entries.getValue(MANIFEST), MANIFEST), "manifest", MANIFEST_KEYS.toSet())
        val schema = manifest.long("schema", 0, Long.MAX_VALUE)
        if (manifest.string("app", 32) != APP_ID || schema !in SCHEMA_RANGE) {
            invalid("the manifest doesn't describe a Southbound export")
        }
        val userData = PlacesEntry.decode(entries.getValue(PLACES))
        val keys = KeysEntry.decode(entries.getValue(API_KEYS), schema)
        val settings = SettingsEntry.decode(entries.getValue(SETTINGS), schema)
        val data = BackupData(userData, keys, settings)

        manifest.string("app_version", MAX_VERSION)
        val c = data.counts()
        // The settings count is the one the file's schema writes (schema 1 had one route field fewer).
        val listed = listOf(c.lists, c.savedPlaces, c.recents, c.home, c.work, c.apiKeys, SettingsEntry.count(settings, schema))
        val names = MANIFEST_KEYS.drop(3)
        for ((i, name) in names.withIndex()) {
            if (manifest.long(name, 0, Long.MAX_VALUE) != listed[i].toLong()) invalid("the contents don't match the manifest")
        }
        return data
    }

    /**
     * Writes [entries] (from [encode]) into [out] as a bundle sealed with [passphrase]. Any failure is an
     * [FwxException] or the stream's IOException, and leaves a partial file for the caller to delete.
     */
    fun write(out: OutputStream, passphrase: CharArray, entries: Map<String, ByteArray>, createdMs: Long) {
        checkSizes(entries)
        val writer = FwxWriter(out, APP_ID, SCHEMA, createdMs, passphrase)
        for ((name, bytes) in entries) writer.entry(name, bytes)
        writer.finish()
    }

    /** Never write an export that Southbound would refuse to import: the entries are the four, each within its limit. */
    fun checkSizes(entries: Map<String, ByteArray>) {
        if (entries.keys.toList() != ENTRY_NAMES) throw IllegalArgumentException("not the four Southbound entries")
        for ((name, bytes) in entries) {
            if (bytes.size > LIMITS.getValue(name)) throw FwxException(FwxError.TOO_LARGE, "$name is too large")
        }
    }

    /**
     * Reads a finished export back with the import path (full verify, then [decode]) and checks that it holds
     * exactly [entries]. Returns false for a file that opens but differs; opening failures are FwxExceptions.
     */
    fun verify(input: InputStream, passphrase: CharArray, entries: Map<String, ByteArray>): Boolean {
        val read = readEntries(input, passphrase)
        try {
            decode(read)
            return read.keys.toList() == entries.keys.toList() && entries.all { (name, bytes) -> read[name].contentEquals(bytes) }
        } finally {
            wipe(read)
        }
    }

    /**
     * The passphrase-free part of an import (spec section 5.1, steps 1 to 6): the header, then the app ID
     * (WRONG_APP) and the schema (SCHEMA_TOO_NEW, SCHEMA_TOO_OLD). Its values are not verified yet.
     */
    fun readHeader(input: InputStream): FwxHeader {
        val header = Fwx.readHeader(input)
        if (header.appId != APP_ID) {
            throw FwxException(FwxError.WRONG_APP, "bundle is for another app", otherAppId = header.appId)
        }
        if (header.schemaVersion > SCHEMA_RANGE.last) throw FwxException(FwxError.SCHEMA_TOO_NEW, "schema too new")
        if (header.schemaVersion < SCHEMA_RANGE.first) throw FwxException(FwxError.SCHEMA_TOO_OLD, "schema too old")
        return header
    }

    /**
     * Opens a bundle and reads every entry into memory (the limits keep that small), returning only after the
     * reader's verified end (spec section 4.4). The caller [wipe]s the arrays when done. The bytes are not
     * interpreted here; [decode] does that.
     */
    fun readEntries(input: InputStream, passphrase: CharArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            FwxReader(input, passphrase, APP_ID, SCHEMA_RANGE, MAX_STREAM_BYTES).use { reader ->
                while (true) {
                    val entry = reader.next() ?: break
                    val expected = ENTRY_NAMES.getOrNull(entries.size) ?: invalid("an entry Southbound doesn't know")
                    if (entry.name != expected) invalid("entries are out of order or unknown")
                    if (entry.length > LIMITS.getValue(expected)) throw FwxException(FwxError.TOO_LARGE, "$expected is too large")
                    val bytes = ByteArray(entry.length.toInt())
                    entries[expected] = bytes
                    var at = 0
                    while (at < bytes.size) {
                        val n = entry.stream.read(bytes, at, bytes.size - at)
                        if (n < 0) throw FwxException(FwxError.DAMAGED, "entry ended early")
                        at += n
                    }
                }
                if (entries.size != ENTRY_NAMES.size) invalid("entries are missing")
                // The entries are read with the manifest's schema, and the import dialog showed the header's: one file,
                // one schema (EXPORT.md).
                val manifest = Obj.of(parseJson(entries.getValue(MANIFEST), MANIFEST), "manifest", MANIFEST_KEYS.toSet())
                if (manifest.long("schema", 0, Long.MAX_VALUE) != reader.header.schemaVersion) {
                    invalid("the manifest's schema isn't the header's")
                }
            }
            return entries
        } catch (e: Throwable) {
            wipe(entries)
            throw e
        }
    }

    /** Overwrites plaintext buffers; best effort, since text parsed from them can't be zeroed. */
    fun wipe(entries: Map<String, ByteArray>) {
        for (bytes in entries.values) bytes.fill(0)
    }

    private const val MAX_VERSION = 64

    private fun manifest(counts: BackupCounts, appVersion: String): ByteArray {
        val version = appVersion.filter { it.code in 0x20..0x7e }.take(MAX_VERSION).ifBlank { "unknown" }
        val values = listOf(counts.lists, counts.savedPlaces, counts.recents, counts.home, counts.work, counts.apiKeys, counts.settings)
        val tree = LinkedHashMap<String, Any?>()
        tree["app"] = APP_ID
        tree["schema"] = SCHEMA
        tree["app_version"] = version
        for ((i, name) in MANIFEST_KEYS.drop(3).withIndex()) tree[name] = values[i]
        return MiniJson.stringify(tree).toByteArray(Charsets.UTF_8)
    }
}
