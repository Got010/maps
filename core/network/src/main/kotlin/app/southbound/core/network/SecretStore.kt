package app.southbound.core.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API keys the user types into Settings (MTA Bus Time, 511NY; later TomTom). */
enum class ApiKey {
    MTA_BUS_TIME,

    /** A free 511NY developer key, for incidents and closures. */
    NY_511,

    /** A read-only GitHub token, needed for updates while the repository is private. */
    GITHUB_RELEASES,
}

/**
 * Stores API keys encrypted with AES-GCM under a key held in the Android
 * Keystore, so the key material never leaves secure hardware and a copy of
 * the app's files doesn't reveal them. Keys are never logged or committed.
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    @Synchronized
    fun get(key: ApiKey): String? {
        val stored = prefs.getString(key.name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun set(key: ApiKey, value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit().remove(key.name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val encrypted = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key.name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun has(key: ApiKey): Boolean = prefs.contains(key.name)

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
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "southbound-api-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
