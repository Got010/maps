package app.southbound.core.export

import app.southbound.core.export.fwx.FwxHeader
import app.southbound.core.network.NetworkFeature
import app.southbound.core.userdata.UserData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** The stores an export reads and an import replaces. The Android implementation is [AndroidBackupStores]. */
interface BackupStores {
    /**
     * Why export and import must stop now, or null. Today: the saved places file exists but can't be read
     * (HANDOFF rule: an unreadable user-data file is never silently exported as empty or replaced).
     */
    fun blocker(): String?

    /**
     * Throws [BackupRefusedException] if anything an import would replace can't be read (for example a saved API key the
     * Keystore refuses to decrypt): an import never overwrites what it could not read. Checks that cost more than
     * [blocker] allows go here, off the main thread.
     */
    suspend fun ensureReadable() {}

    /** Which network features are on now, so an import can say which ones it turns on. */
    fun networkToggles(): Map<NetworkFeature, Boolean>

    /** Everything an export carries. Throws if a part can't be read; never returns a part as empty to hide that. */
    suspend fun read(): BackupData

    /**
     * Makes the stores exactly [data]. Repeatable: applying the same data twice leaves the same state. Every write
     * must be durable when this returns (a synchronous commit, not a queued one), and a write that fails throws:
     * the caller deletes the kept import only after this returns.
     */
    suspend fun apply(data: BackupData)
}

/** Export or import was stopped before it began; [reason] is for the person, and nothing was changed. */
class BackupRefusedException(val reason: String) : IOException(reason)

/** The finished export file did not read back as what was written. */
class ReadBackFailedException : IOException("The file written did not read back as written")

/** What an import came to. */
sealed interface ImportOutcome {
    /** Applied: the stores now hold the bundle. */
    data class Done(val counts: BackupCounts, val turnedOn: List<NetworkFeature>) : ImportOutcome

    /**
     * The bundle verified and was kept to apply, but applying stopped ([reason]). It is finished at the next
     * start, or with [BackupService.resume]; nothing is lost.
     */
    data class Pending(val counts: BackupCounts, val turnedOn: List<NetworkFeature>, val reason: String) : ImportOutcome
}

/** What [BackupService.resume] found. */
sealed interface ResumeOutcome {
    data object NothingPending : ResumeOutcome
    data class Applied(val counts: BackupCounts, val turnedOn: List<NetworkFeature>) : ResumeOutcome

    /** [unreadable]: the kept import itself can't be opened (so it can only be discarded). */
    data class Failed(val reason: String, val unreadable: Boolean) : ResumeOutcome
}

/**
 * Export and import, without any Android type. The caller supplies streams and runs this off the main thread
 * (key derivation alone takes about a second).
 *
 * Export: refuse if the stores can't be read whole, build the entries, run the import's own checks on them, seal
 * the file in memory, write it to the destination, read it back through the import path and compare. A failure
 * before the destination is opened leaves it untouched; after that the caller deletes the file.
 *
 * Import: the whole file is verified (header, app, schema, passphrase, every chunk, the end record) and its contents
 * checked in memory first; wrong app, wrong passphrase and damage all stop there with nothing changed. Then the
 * entries are kept in [ImportJournal] (atomic: the commit point) and applied to the stores. If applying stops, the
 * import is finished later; it is never half-forgotten.
 */
class BackupService(
    private val stores: BackupStores,
    private val journal: ImportJournal,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** Steps 1 to 6 of the import: the header, the app and the schema, no passphrase. Values are not verified. */
    fun readHeader(open: () -> InputStream): FwxHeader = open().use(SouthboundBundle::readHeader)

    /** Whether an import was kept and not yet applied. */
    fun hasPending(): Boolean = journal.exists()

    /** Why a new export or import can't start, or null. */
    fun blocker(): String? = when {
        journal.exists() -> PENDING_REASON
        else -> stores.blocker()
    }

    suspend fun export(
        openOutput: () -> OutputStream,
        openInput: () -> InputStream,
        passphrase: CharArray,
    ): BackupCounts = lock.withLock {
        blocker()?.let { throw BackupRefusedException(it) }
        // The header's created time is the device clock at the start of the export.
        val createdMs = clock()
        val data = stores.read()
        val entries = SouthboundBundle.encode(data, appVersion)
        try {
            // Everything import will check, before the destination is touched.
            SouthboundBundle.decode(entries)
            SouthboundBundle.checkSizes(entries)
            // The whole file is sealed in memory first (ciphertext only, a few MB at most), so a problem with the
            // passphrase or the data never reaches the destination; only a finished file is written to it.
            val sealed = ByteArrayOutputStream()
            SouthboundBundle.write(sealed, passphrase, entries, createdMs)
            openOutput().use { sealed.writeTo(it) }
            // Read the whole file back through the import path before calling it done (spec section 5.3).
            val same = openInput().use { SouthboundBundle.verify(it, passphrase, entries) }
            if (!same) throw ReadBackFailedException()
            data.counts()
        } finally {
            SouthboundBundle.wipe(entries)
        }
    }

    suspend fun import(openInput: () -> InputStream, passphrase: CharArray): ImportOutcome = lock.withLock {
        blocker()?.let { throw BackupRefusedException(it) }
        stores.ensureReadable()
        val entries = openInput().use { SouthboundBundle.readEntries(it, passphrase) }
        try {
            val data = SouthboundBundle.decode(entries)
            val counts = data.counts()
            val turnedOn = turnedOn(data)
            // The places as they are before the import, kept with it: a resume at start goes ahead only while the
            // places are these or the import's own ([resume]).
            val before = placesPrint { stores.read().userData }
            // The commit point. If this throws, nothing has changed.
            journal.write(entries, before)
            try {
                stores.apply(data)
                journal.clear()
                ImportOutcome.Done(counts, turnedOn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportOutcome.Pending(counts, turnedOn, reasonOf(e))
            }
        } finally {
            SouthboundBundle.wipe(entries)
        }
    }

    /**
     * Finishes a kept import. Call it at start with [automatic], and from the "try again" button without.
     *
     * At start nobody asked, so it goes ahead only while the saved places are the ones the import found or the ones
     * it brings (it stopped before or after replacing them). Places saved or changed since would be deleted by
     * applying it again: then it waits, [CHANGED_REASON], for the person to finish or discard it in Settings.
     */
    suspend fun resume(automatic: Boolean = false): ResumeOutcome = lock.withLock {
        if (!journal.exists()) return@withLock ResumeOutcome.NothingPending
        val entries = try {
            journal.read() ?: return@withLock ResumeOutcome.NothingPending
        } catch (e: Exception) {
            return@withLock ResumeOutcome.Failed(reasonOf(e), unreadable = true)
        }
        try {
            val data = SouthboundBundle.decode(entries)
            // The same refusal as a fresh import: never overwrite what can't be read.
            stores.ensureReadable()
            if (automatic) {
                val now = placesPrint { stores.read().userData }
                if (now == null || now !in setOfNotNull(journal.mark(), placesPrint { data.userData })) {
                    return@withLock ResumeOutcome.Failed(CHANGED_REASON, unreadable = false)
                }
            }
            val turnedOn = turnedOn(data)
            stores.apply(data)
            journal.clear()
            ResumeOutcome.Applied(data.counts(), turnedOn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ResumeOutcome.Failed(reasonOf(e), unreadable = false)
        } finally {
            SouthboundBundle.wipe(entries)
        }
    }

    /** Gives up on a kept import (the person's choice; the bundle file they picked is untouched). */
    suspend fun discardPending() = lock.withLock { journal.clear() }

    /** The network features the bundle switches from off to on, in declaration order. */
    private fun turnedOn(data: BackupData): List<NetworkFeature> {
        val now = stores.networkToggles()
        return NetworkFeature.entries.filter { data.settings.network[it] == true && now[it] != true }
    }

    /** A digest of [places] as an export would write them, or null when they can't be read. */
    private suspend fun placesPrint(places: suspend () -> UserData): String? = try {
        val bytes = PlacesEntry.encode(places())
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.also { bytes.fill(0) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** A short reason that holds no contents: the exception's own message, or its kind. */
    private fun reasonOf(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        const val PENDING_REASON = "An earlier import has not finished applying. Finish or discard it first."
        const val CHANGED_REASON =
            "Saved places changed after this import stopped. Finish it to replace them with the backup's, or discard it."
    }
}
