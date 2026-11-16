package com.joebrowser.app.auth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local-only account: an email + password gate for this device.
 *
 * Joe Browser has no server, no sync and no telemetry, so an "account" cannot mean a cloud
 * login. What it is here:
 *  - a PBKDF2-HmacSHA256 password hash (never the password itself) stored in private,
 *    unbacked-up app storage,
 *  - an in-memory unlock flag for the process lifetime — a cold start always asks again,
 *  - a gate for the UI, not a cryptographic boundary around the database (profiles and
 *    history were already protected by app-private storage and the device-bound Keystore
 *    key; this adds a human gate on top).
 *
 * There is deliberately no email verification and no network call anywhere in this flow.
 */
object LocalAccount {

    private const val PREFS = "joe_account"
    private const val KEY_RECORD = "record"
    private const val ITERATIONS = 120_000
    private const val HASH_BITS = 256
    private const val SALT_BYTES = 16

    @Volatile
    private var unlocked = false

    /** True while the current process has unlocked the app. False after a cold start or lock(). */
    fun isUnlocked(): Boolean = unlocked

    fun lock() { unlocked = false }

    fun exists(context: Context): Boolean = record(context) != null

    fun email(context: Context): String? = record(context)?.optString("email")?.takeIf { it.isNotBlank() }

    /** Creates (or replaces, after a reset) the local account. */
    fun register(context: Context, email: String, password: String): Result<Unit> {
        val clean = email.trim().lowercase()
        if (!EMAIL_RE.matches(clean)) return Result.failure(IllegalArgumentException("bad email"))
        if (password.length < MIN_PASSWORD) return Result.failure(IllegalArgumentException("short password"))
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, HASH_BITS)
        val json = JSONObject()
            .put("v", 1)
            .put("email", clean)
            .put("salt", b64(salt))
            .put("iterations", ITERATIONS)
            .put("hash", b64(hash))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RECORD, json.toString()).apply()
        unlocked = true
        return Result.success(Unit)
    }

    fun signIn(context: Context, email: String, password: String): Result<Unit> {
        val rec = record(context) ?: return Result.failure(IllegalStateException("no account"))
        val storedEmail = rec.optString("email")
        val salt = b64decode(rec.optString("salt"))
        val iterations = rec.optInt("iterations", ITERATIONS)
        val expected = b64decode(rec.optString("hash"))
        val actual = pbkdf2(password.toCharArray(), salt, iterations, expected.size * 8)
        val emailOk = MessageDigest.isEqual(
            storedEmail.toByteArray(Charsets.UTF_8),
            email.trim().lowercase().toByteArray(Charsets.UTF_8)
        )
        val passOk = MessageDigest.isEqual(expected, actual)
        return if (emailOk && passOk) {
            unlocked = true
            Result.success(Unit)
        } else {
            Result.failure(IllegalArgumentException("bad credentials"))
        }
    }

    /**
     * Changes the password of the current local account, keeping the same email and all data.
     *
     * PBKDF2 is one-way by design, so "forgot password" cannot recover the old secret — but a
     * user who is already unlocked and remembers the current password must not have to nuke the
     * account and re-register. A fresh salt is generated, so a re-used password still hashes
     * differently and old hashes cannot be replayed against the new record.
     */
    fun changePassword(
        context: Context,
        currentPassword: String,
        newPassword: String
    ): Result<Unit> {
        val rec = record(context) ?: return Result.failure(IllegalStateException("no account"))
        if (!unlocked) return Result.failure(IllegalStateException("locked"))
        if (newPassword.length < MIN_PASSWORD) {
            return Result.failure(IllegalArgumentException("short password"))
        }
        val salt = b64decode(rec.optString("salt"))
        val iterations = rec.optInt("iterations", ITERATIONS)
        val expected = b64decode(rec.optString("hash"))
        val actual = pbkdf2(currentPassword.toCharArray(), salt, iterations, expected.size * 8)
        if (!MessageDigest.isEqual(expected, actual)) {
            return Result.failure(IllegalArgumentException("bad credentials"))
        }
        val newSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val newHash = pbkdf2(newPassword.toCharArray(), newSalt, ITERATIONS, HASH_BITS)
        val json = JSONObject(rec.toString())
            .put("v", 1)
            .put("salt", b64(newSalt))
            .put("iterations", ITERATIONS)
            .put("hash", b64(newHash))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECORD, json.toString()).apply()
        unlocked = true
        return Result.success(Unit)
    }

    /** Erases ONLY the local account. Profiles, proxies and history stay on the device. */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORD).apply()
        unlocked = false
    }

    private fun record(context: Context): JSONObject? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORD, null)
            ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, bits: Int): ByteArray =
        PBEKeySpec(password, salt, iterations, bits).let { spec ->
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun b64decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    /** Six characters, not eight: this is an on-device gate, and the length rule was the
     *  single biggest reason first-run registration was abandoned. */
    private const val MIN_PASSWORD = 6

    private val EMAIL_RE = Regex("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$")
}
