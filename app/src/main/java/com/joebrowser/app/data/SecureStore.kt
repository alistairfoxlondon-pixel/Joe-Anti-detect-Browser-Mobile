package com.joebrowser.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption for proxy credentials at rest, backed by the Android Keystore.
 *
 * Properties that matter:
 *  - The key material never leaves the device's secure hardware/keystore.
 *  - Encryption and decryption are total: if the keystore is unavailable (work profile
 *    migration, key invalidation after a lock-screen change, emulator without a keystore
 *    provider) the password degrades to "absent" instead of throwing.
 *  - Ciphertext carries a version prefix so a future key rotation can be detected.
 */
object SecureStore {

    private const val TAG = "JoeSecureStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "joe_browser_proxy_v2"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PREFIX = "v2:"

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var keyFailureLogged = false

    private fun key(): SecretKey? {
        cachedKey?.let { return it }
        return try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
            val resolved = existing ?: generateKey()
            cachedKey = resolved
            resolved
        } catch (t: Throwable) {
            if (!keyFailureLogged) {
                keyFailureLogged = true
                Log.w(TAG, "Keystore unavailable — proxy passwords will not be persisted", t)
            }
            null
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Returns "v2:<base64(iv + ciphertext)>", or "" when encryption is unavailable. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val secret = key() ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val iv = cipher.iv
            val payload = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + payload, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "Encryption failed", t)
            ""
        }
    }

    /** Returns "" for anything unreadable rather than throwing. */
    fun decrypt(stored: String): String {
        if (stored.isBlank() || !stored.startsWith(PREFIX)) return ""
        val secret = key() ?: return ""
        return try {
            val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val iv = raw.copyOfRange(0, IV_BYTES)
            val ciphertext = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "Decryption failed (key rotated or ciphertext damaged)", t)
            ""
        }
    }

    /** True when credentials can actually be stored on this device. */
    fun available(): Boolean = key() != null
}
