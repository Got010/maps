package app.southbound.core.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys the user types into Settings (MTA Bus Time, 511NY; later TomTom). */
enum class ApiKey {
    MTA_BUS_TIME,

    /**
     * A 511NY developer key. Unused since 511NY's October 2026 move, whose feeds need none
     * (core/traffic FiveElevenNyCars); kept so a saved key and older backups still read.
     */
    NY_511,

    /** An optional read-only GitHub token: the repository is public, so it only lifts GitHub's rate limit. */
    GITHUB_RELEASES,

    /** A free EIA key (eia.gov/opendata/register.php), for the weekly gas price. */
    EIA,

    /** A free OHGO key (publicapi.ohgo.com/docs/registration), for Ohio's traffic (core/traffic Ohgo). */
    OHGO,
}

/**
 * Stores API keys encrypted with AES-GCM under a key held in the Android
 * Keystore, so the key material never leaves secure hardware and a copy of
 * the app's files doesn't reveal them. Keys are never logged or committed.
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    init {
        synchronized(Companion) {
            if (!loaded) {
                savedNow.value = ApiKey.entries.filter { prefs.contains(it.name) }.toSet()
                loaded = true
            }
        }
    }

    /**
     * The keys saved now, for a screen that says whether one is set, updated as keys are saved, removed or imported;
     * the same for every SecretStore in the process (they share one preferences file).
     */
    val saved: StateFlow<Set<ApiKey>> = savedNow.asStateFlow()

    @Synchronized
    fun get(key: ApiKey): String? {
        val stored = prefs.getString(key.name, null) ?: return null
        return runCatching { unseal(stored) }.getOrNull()
    }

    @Synchronized
    fun set(key: ApiKey, value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(key.name).apply()
            savedNow.value = savedNow.value - key
            return
        }
        prefs.edit().putString(key.name, seal(trimmed)).apply()
        savedNow.value = savedNow.value + key
    }

    /**
     * Every saved key, decrypted, for an export. Unlike [get] it does not treat a key that is saved but cannot be
     * read (a Keystore error) as absent: it throws [SecretUnreadableException], so an export never leaves one out.
     */
    @Synchronized
    fun readAll(): Map<ApiKey, String> {
        val out = LinkedHashMap<ApiKey, String>()
        for (key in ApiKey.entries) {
            val stored = prefs.getString(key.name, null) ?: continue
            out[key] = try {
                unseal(stored)
            } catch (e: Exception) {
                throw SecretUnreadableException(key)
            }
        }
        return out
    }

    /**
     * Makes the saved keys exactly [values] (an import): every value is sealed first, so a Keystore failure changes
     * nothing, then all keys are written in one commit. Throws [java.io.IOException] if the commit fails.
     */
    @Synchronized
    fun replaceAll(values: Map<ApiKey, String>) {
        val sealed = values.mapValues { (_, value) -> seal(value) }
        val edit = prefs.edit()
        for (key in ApiKey.entries) {
            val text = sealed[key]
            if (text == null) edit.remove(key.name) else edit.putString(key.name, text)
        }
        if (!edit.commit()) throw java.io.IOException("Couldn't save the API keys")
        savedNow.value = sealed.keys
    }

    /**
     * Whether [key] is saved and can be read. A key the Keystore can't decrypt counts as missing, as [get] treats it
     * (the feeds run without it): the screen then asks for it again rather than saying it is saved.
     */
    fun has(key: ApiKey): Boolean = get(key) != null

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val encrypted = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun unseal(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        val savedNow = MutableStateFlow<Set<ApiKey>>(emptySet())

        @Volatile
        var loaded = false

        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "southbound-api-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** A saved API key could not be decrypted (for example the Keystore refused). The message never holds the key. */
class SecretUnreadableException(val key: ApiKey) : java.io.IOException("The saved ${key.name} key can't be read")
