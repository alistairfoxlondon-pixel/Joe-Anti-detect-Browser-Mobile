package com.joebrowser.app.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Filesystem isolation layer — one directory per browser profile.
 *
 * ```text
 * files/profiles/<profileId>/
 *     meta.json          identity bookkeeping + opaque kernel token
 *     session.json       per-profile application variables (last URL, scroll, counters)
 *     cookies/           jar snapshots owned by this profile
 *     cache/             HTTP cache owned by this profile
 *     localstorage/      local/session storage snapshots
 *     indexdb/           IndexedDB snapshots
 *     downloads/         files saved from this profile
 * ```
 *
 * Two things matter here:
 *  1. Nothing a profile writes is ever reachable from another profile's path, so a wipe is a
 *     single recursive delete that cannot touch a sibling identity.
 *  2. [kernelToken] is a random opaque string used as the AndroidX WebKit `ProfileStore`
 *     profile name. The storage directory name therefore does not encode the profile id, so
 *     two profiles cannot collide in the WebView's own on-disk index.
 *
 * Every method is defensive: a missing directory is recreated, a corrupt JSON file is
 * replaced, and no method ever throws to the caller.
 */
object ProfileStorage {

    private const val TAG = "JoeStorage"
    private const val META = "meta.json"
    private const val SESSION = "session.json"
    private const val ROOT = "profiles"
    private val SUBDIRS = listOf("cookies", "cache", "localstorage", "indexdb", "downloads")
    private val random = SecureRandom()

    fun root(context: Context): File = File(context.applicationContext.filesDir, ROOT)

    fun dirFor(context: Context, profileId: String): File {
        val safe = profileId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "unknown" }
        return File(root(context), safe)
    }

    /** Creates the isolated directory tree if needed. Safe to call on every launch. */
    fun ensure(context: Context, profileId: String): File {
        val dir = dirFor(context, profileId)
        return try {
            if (!dir.exists()) dir.mkdirs()
            SUBDIRS.forEach { name -> File(dir, name).let { if (!it.exists()) it.mkdirs() } }
            sessionDir(context, profileId, "cookies")
            dir
        } catch (t: Throwable) {
            Log.w(TAG, "Could not create profile storage for $profileId", t)
            dir
        }
    }

    fun sessionDir(context: Context, profileId: String, kind: String): File {
        val dir = File(dirFor(context, profileId), kind)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return dir
    }

    /**
     * Opaque token handed to AndroidX WebKit's ProfileStore. Stable for the lifetime of the
     * profile; regenerated only when the profile's browsing data is erased.
     */
    fun kernelToken(context: Context, profileId: String): String {
        val dir = ensure(context, profileId)
        val meta = readMeta(dir)
        val existing = meta.optString("token", "")
        if (existing.length >= 16) return existing
        val token = newToken()
        meta.put("token", token)
        meta.put("created", meta.optLong("created", System.currentTimeMillis()))
        writeMeta(dir, meta)
        return token
    }

    /** Rotates the opaque kernel token (used when profile data is wiped). */
    fun rotateKernelToken(context: Context, profileId: String): String {
        val dir = ensure(context, profileId)
        val meta = readMeta(dir)
        val token = newToken()
        meta.put("token", token)
        meta.put("lastWipe", System.currentTimeMillis())
        writeMeta(dir, meta)
        return token
    }

    fun lastWipe(context: Context, profileId: String): Long =
        readMeta(dirFor(context, profileId)).optLong("lastWipe", 0L)

    fun markWiped(context: Context, profileId: String) {
        val dir = ensure(context, profileId)
        val meta = readMeta(dir)
        meta.put("lastWipe", System.currentTimeMillis())
        meta.put("wipeCount", meta.optInt("wipeCount", 0) + 1)
        writeMeta(dir, meta)
    }

    // ------------------------------------------------------- session variables

    /** Reads a per-profile application variable (isolated from every other profile). */
    fun getVar(context: Context, profileId: String, key: String): String? = try {
        val file = File(ensure(context, profileId), SESSION)
        if (!file.exists()) null
        else JSONObject(file.readText()).optString(key, "").takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }

    fun setVar(context: Context, profileId: String, key: String, value: String) {
        try {
            val file = File(ensure(context, profileId), SESSION)
            val json = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
            else JSONObject()
            json.put(key, value)
            file.writeText(json.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Could not persist session variable $key", t)
        }
    }

    // -------------------------------------------------------------- lifecycle

    /** Recursively removes one profile's entire isolated tree. */
    fun erase(context: Context, profileId: String) {
        try {
            val dir = dirFor(context, profileId)
            if (dir.exists()) deleteRecursively(dir)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not erase storage for $profileId", t)
        }
    }

    /**
     * Removes every per-run scratch file (downloads staged for the viewer, exports) without
     * touching profile identities. Called by "Reset application data".
     */
    fun clearRunState(context: Context) {
        try {
            val app = context.applicationContext
            listOf(File(app.cacheDir, "exports")).forEach { dir ->
                dir.listFiles()?.forEach { runCatching { it.delete() } }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not clear run state", t)
        }
    }

    /** Every materialised directory for one profile (cookies, cache, localstorage, …). */
    fun sessionDirs(context: Context, profileId: String): List<File> {
        val dir = dirFor(context, profileId)
        return SUBDIRS.map { File(dir, it) }
    }

    fun eraseAll(context: Context) {
        try {
            val root = root(context)
            if (root.exists()) root.listFiles()?.forEach { deleteRecursively(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not erase all profile storage", t)
        }
    }

    /** Bytes on disk for one profile — shown in the editor so the isolation is visible. */
    fun sizeBytes(context: Context, profileId: String): Long = try {
        sizeOf(dirFor(context, profileId))
    } catch (t: Throwable) {
        0L
    }

    fun totalSizeBytes(context: Context): Long = try {
        sizeOf(root(context))
    } catch (t: Throwable) {
        0L
    }

    fun humanSize(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    // ---------------------------------------------------------------- private

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { b -> String.format(java.util.Locale.US, "%02x", b) }
    }

    private fun readMeta(dir: File): JSONObject = try {
        val file = File(dir, META)
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun writeMeta(dir: File, json: JSONObject) {
        runCatching { File(dir, META).writeText(json.toString()) }
            .onFailure { Log.w(TAG, "Could not write profile meta", it) }
    }

    private fun deleteRecursively(file: File) {
        runCatching {
            if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
            file.delete()
        }
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }
}
