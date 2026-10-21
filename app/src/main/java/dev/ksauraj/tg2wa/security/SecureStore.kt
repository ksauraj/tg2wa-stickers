package dev.ksauraj.tg2wa.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest, encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where available) and never leaves it:
 *  - the user's api_id / api_hash,
 *  - the random key TDLib uses to encrypt its own session database.
 * Backups are disabled in the manifest, so none of this leaves the device.
 */
class SecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_ALIAS = "tg2wa_master_v1"
        const val API_ID = "api_id"
        const val API_HASH = "api_hash"
        const val DB_KEY = "db_key"
    }

    fun hasApiCredentials(): Boolean = prefs.contains(API_ID) && prefs.contains(API_HASH)

    fun saveApiCredentials(apiId: Int, apiHash: String) {
        prefs.edit()
            .putString(API_ID, encrypt(apiId.toString().toByteArray()))
            .putString(API_HASH, encrypt(apiHash.toByteArray()))
            .apply()
    }

    fun apiId(): Int? = prefs.getString(API_ID, null)?.let { String(decrypt(it)).toIntOrNull() }
    fun apiHash(): String? = prefs.getString(API_HASH, null)?.let { String(decrypt(it)) }

    fun clearApiCredentials() {
        prefs.edit().remove(API_ID).remove(API_HASH).apply()
    }

    /** 32 random bytes for TDLib's database_encryption_key, created on first use. */
    @Synchronized
    fun databaseKey(): ByteArray {
        prefs.getString(DB_KEY, null)?.let { return decrypt(it) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(DB_KEY, encrypt(key)).commit()
        return key
    }

    /** Forget the DB key too: whatever TDLib stored becomes unreadable. */
    fun wipeAll() {
        prefs.edit().clear().commit()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
        }
    }

    fun wipeDatabaseKey() {
        prefs.edit().remove(DB_KEY).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val ivLen = all[0].toInt()
        val iv = all.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(all, 1 + ivLen, all.size - 1 - ivLen)
    }
}
