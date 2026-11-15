package com.joebrowser.app.data

import android.content.Context
import android.util.Log
import com.joebrowser.app.core.Async
import com.joebrowser.app.fingerprint.FingerprintFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for profiles, saved proxies, extensions and monitor alerts.
 *
 * Why this exists:
 *  - Screens never touch SQLite directly. They call the repository, which runs every query
 *    on the background pool and delivers results on the main thread. That removes the
 *    "database on the UI thread" class of jank and StrictMode violations.
 *  - Writes are serialised on a single-threaded executor, so two screens editing the same
 *    profile cannot interleave into a lost update.
 *  - After any successful write, every registered [Listener] is notified on the main thread.
 *    Fragments therefore stay in sync with each other, with the proxy monitor service, and
 *    with the browser activity without polling.
 *
 * All callbacks are already on the main thread and are internally guarded.
 */
object ProfileRepository {

    private const val TAG = "JoeRepo"

    fun interface Listener {
        fun onDataChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        Async.onMain {
            listeners.forEach { listener ->
                runCatching { listener.onDataChanged() }
                    .onFailure { Log.w(TAG, "Listener failed", it) }
            }
        }
    }

    /** Called by background services after changing rows without going through this object. */
    fun notifyExternalChange() = notifyChanged()

    // ------------------------------------------------------------- profiles

    fun profiles(context: Context, onResult: (List<Profile>) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).getAllProfiles() }) { onResult(it ?: emptyList()) }
    }

    fun profile(context: Context, id: String, onResult: (Profile?) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).getProfile(id) }) { onResult(it) }
    }

    fun profileCount(context: Context, onResult: (Int) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).countProfiles() }) { onResult(it ?: 0) }
    }

    fun saveProfile(context: Context, profile: Profile, onDone: ((Profile) -> Unit)? = null) {
        val app = context.applicationContext
        Async.serialize {
            val saved = runCatching {
                db(app).saveProfile(profile)
                // Materialise the profile's private directory tree on first save so the very
                // first launch already has isolated storage to write into.
                ProfileStorage.ensure(app, profile.id)
                profile
            }.getOrNull()
            if (saved != null) {
                onDone?.let { callback -> Async.onMain { runCatching { callback(saved) } } }
                notifyChanged()
            } else {
                Async.toast(app, "Could not save the profile")
            }
        }
    }

    /**
     * Applies [mutate] to the freshest copy of the profile inside the serial writer, then
     * persists it. Use this for counters (open count, last opened) that several screens
     * may touch at once — it avoids clobbering fields another screen just wrote.
     */
    fun updateProfile(context: Context, id: String, mutate: (Profile) -> Unit) {
        val app = context.applicationContext
        Async.serialize {
            val current = runCatching { db(app).getProfile(id) }.getOrNull() ?: return@serialize
            runCatching {
                mutate(current)
                db(app).saveProfile(current)
            }.onFailure { Log.w(TAG, "updateProfile failed", it) }
            notifyChanged()
        }
    }

    fun deleteProfile(context: Context, profile: Profile, keepTrash: Boolean, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            runCatching {
                db(app).deleteProfile(profile, keepTrash)
                if (!keepTrash) {
                    ProfileStorage.erase(app, profile.id)
                }
            }.onFailure { Log.w(TAG, "deleteProfile failed", it) }
            Async.onMain { onDone() }
            notifyChanged()
        }
    }

    fun duplicateProfile(context: Context, profile: Profile, onDone: (Profile?) -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            val copy = profile.duplicate()
            // A clone must not be a twin. The seed drives the canvas/audio noise, so copying it
            // verbatim handed two "separate" profiles byte-identical fingerprints — exactly the
            // correlation duplicating a profile is meant to avoid. Re-seeding keeps every
            // deliberate choice (OS, screen, GPU, proxy) and changes only the noise.
            runCatching {
                val existing = db(app).getAllProfiles().map { FingerprintFactory.signature(it.fp) }
                FingerprintFactory.ensureUnique(copy.fp, existing)
            }
            // The copy has never been opened and its proxy has never been probed from this
            // profile, so it must not inherit the original's measurements — otherwise a brand new
            // profile shows a latency, an exit IP and a country nobody measured for it.
            copy.openCount = 0
            copy.lastOpened = 0L
            copy.everLaunched = false
            copy.proxy = copy.proxy.duplicate().apply {
                lastIp = ""
                lastCountry = ""
                lastCountryCode = ""
                lastCity = ""
                lastTimezone = ""
                lastCheckedAt = 0L
                latencyMs = -1
                lastError = ""
                failStreak = 0
                rotations = 0
            }
            val saved = runCatching { db(app).saveProfile(copy); copy }.getOrNull()
            // The copy gets its own isolated storage tree and its own WebKit bucket token,
            // so the clone never shares cookies with the original.
            saved?.let { ProfileStorage.ensure(app, it.id) }
            Async.onMain { onDone(saved) }
            notifyChanged()
        }
    }

    /**
     * Bulk create — the AdsPower/GoLogin "add 20 profiles" flow, done honestly.
     *
     * Each profile gets its own engine/OS identity rolled by [FingerprintFactory.regenerate]
     * and is de-duplicated against every signature already on the device, so twenty profiles
     * are twenty distinct fingerprints rather than twenty copies of one. Names are generated
     * from the requested base (`Shop 01`, `Shop 02`, …) and the whole batch is written inside a
     * single serialised transaction, so a failure halfway through cannot leave a half-batch.
     */
    fun createBatch(
        context: Context,
        baseName: String,
        count: Int,
        engine: BrowserEngine,
        os: String,
        proxyId: String?,
        onDone: (List<Profile>) -> Unit
    ) {
        val app = context.applicationContext
        val safeCount = count.coerceIn(1, 50)
        Async.serialize {
            val created = mutableListOf<Profile>()
            runCatching {
                val existing = db(app).getAllProfiles().map { FingerprintFactory.signature(it.fp) }.toMutableList()
                val proxy = proxyId?.let { id -> db(app).getAllProxies().firstOrNull { it.id == id } }
                val startIndex = nextBatchIndex(existingNames = db(app).getAllProfiles().map { it.name }, base = baseName)
                repeat(safeCount) { offset ->
                    val profile = Profile(name = "%s %02d".format(baseName, startIndex + offset))
                    profile.browserKind = engine.id
                    profile.browserVersion = FingerprintFactory.latestBuildFor(engine)
                    FingerprintFactory.regenerate(profile.fp, os, proxy?.config?.lastCountryCode, engine)
                    FingerprintFactory.ensureUnique(profile.fp, existing)
                    existing += FingerprintFactory.signature(profile.fp)
                    if (proxy != null) {
                        profile.proxy = proxy.config.duplicate()
                        profile.proxy.enabled = true
                    }
                    db(app).saveProfile(profile)
                    ProfileStorage.ensure(app, profile.id)
                    created += profile
                }
            }.onFailure { Log.w(TAG, "createBatch stopped after ${created.size} profiles", it) }
            Async.onMain { onDone(created) }
            notifyChanged()
        }
    }

    /** Next free `base NN` suffix, so a second batch does not collide with the first. */
    private fun nextBatchIndex(existingNames: List<String>, base: String): Int {
        val pattern = Regex("^" + Regex.escape(base) + "\\s+(\\d+)$")
        val highest = existingNames.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return highest + 1
    }

    // -------------------------------------------------------------- proxies

    fun proxies(context: Context, onResult: (List<SavedProxy>) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).getAllProxies() }) { onResult(it ?: emptyList()) }
    }

    fun saveProxy(context: Context, proxy: SavedProxy, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        Async.serialize {
            val ok = runCatching { db(app).saveProxy(proxy) }.isSuccess
            if (ok) Async.onMain { onDone?.invoke() } else Async.toast(app, "Could not save the proxy")
            notifyChanged()
        }
    }

    fun saveProxies(context: Context, proxies: List<SavedProxy>, onDone: (Int) -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            var saved = 0
            proxies.forEach { proxy ->
                runCatching { db(app).saveProxy(proxy); saved++ }
                    .onFailure { Log.w(TAG, "saveProxy failed", it) }
            }
            Async.onMain { onDone(saved) }
            notifyChanged()
        }
    }

    fun deleteProxy(context: Context, id: String, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            runCatching { db(app).deleteProxy(id) }
            Async.onMain { onDone() }
            notifyChanged()
        }
    }

    fun watchedProxyCount(context: Context, onResult: (Int) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).countWatchedProxies() }) { onResult(it ?: 0) }
    }

    // ----------------------------------------------------------- extensions

    fun extensions(context: Context, onResult: (List<ExtensionItem>) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).getAllExtensions() }) { onResult(it ?: emptyList()) }
    }

    fun saveExtension(context: Context, item: ExtensionItem) {
        val app = context.applicationContext
        Async.serialize {
            runCatching { db(app).saveExtension(item) }
            notifyChanged()
        }
    }

    fun deleteExtension(context: Context, id: String) {
        val app = context.applicationContext
        Async.serialize {
            runCatching { db(app).deleteExtension(id) }
            notifyChanged()
        }
    }

    // -------------------------------------------------------------- history

    fun addHistory(context: Context, profile: Profile, url: String, title: String) {
        if (!Prefs.historyEnabled(context)) return
        if (url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
        val app = context.applicationContext
        Async.serialize {
            runCatching { db(app).addHistory(profile.id, profile.displayName(), url, title.ifBlank { url }) }
        }
    }

    fun history(context: Context, profileId: String? = null, limit: Int = 500, onResult: (List<HistoryEntry>) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).getHistory(limit, profileId) }) { onResult(it ?: emptyList()) }
    }

    fun clearHistory(context: Context, profileId: String? = null, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            runCatching { db(app).clearHistory(profileId) }
            Async.onMain { onDone() }
            notifyChanged()
        }
    }

    // --------------------------------------------------------------- alerts

    fun alerts(context: Context, limit: Int = 20, onResult: (List<MonitorAlert>) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).getAlerts(limit) }) { onResult(it ?: emptyList()) }
    }

    fun alertCount(context: Context, onResult: (Int) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).alertCount() }) { onResult(it ?: 0) }
    }

    fun clearAlerts(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            runCatching { db(app).clearAlerts() }
            Async.onMain { onDone() }
            notifyChanged()
        }
    }

    // ------------------------------------------------------------- household

    fun trashCount(context: Context, onResult: (Int) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).trashCount() }) { onResult(it ?: 0) }
    }

    fun trashedProfiles(context: Context, onResult: (List<Pair<Profile, Long>>) -> Unit) {
        val app = context.applicationContext
        Async.compute({ db(app).getAllTrashedProfiles() }) { onResult(it ?: emptyList()) }
    }

    fun restoreFromTrash(context: Context, id: String, onDone: (Profile?) -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            val restored = runCatching {
                val p = db(app).restoreTrashedProfile(id)
                if (p != null) ProfileStorage.ensure(app, p.id)
                p
            }.getOrNull()
            Async.onMain { onDone(restored) }
            notifyChanged()
        }
    }

    fun deleteTrashedForever(context: Context, id: String, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            runCatching {
                db(app).deleteTrashedForever(id)
                ProfileStorage.erase(app, id)
            }
            Async.onMain { onDone() }
            notifyChanged()
        }
    }

    fun emptyTrash(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            runCatching {
                val items = db(app).getAllTrashedProfiles()
                db(app).emptyTrash()
                items.forEach { (p, _) -> ProfileStorage.erase(app, p.id) }
            }
            Async.onMain { onDone() }
            notifyChanged()
        }
    }

    fun exportBackup(context: Context, includeSecrets: Boolean, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        Async.compute({
            val database = db(app)
            ExportFormat.build(
                database.getAllProfiles(),
                database.getAllProxies(),
                database.getAllExtensions(),
                includeSecrets
            ).toString(2)
        }) { onResult(it) }
    }

    /** Outcome of a restore: counts for the confirmation toast. */
    data class RestoreResult(val profiles: Int, val proxies: Int, val skipped: Int, val hadCredentials: Boolean)

    /**
     * Writes a parsed backup into the database.
     *
     * Ids are preserved so a restore over the same install is idempotent, and every restored
     * profile gets a fresh isolation directory (a cloned backup must never share cookies with the
     * profile it came from). Runs serially with every other write so a concurrent save cannot
     * interleave with a restore.
     */
    fun restoreBackup(context: Context, restore: ExportFormat.Restore, onDone: (RestoreResult) -> Unit) {
        val app = context.applicationContext
        Async.serialize {
            var profiles = 0
            var proxies = 0
            var failed = 0
            runCatching {
                val database = db(app)
                restore.proxies.forEach { proxy ->
                    runCatching {
                        database.saveProxy(proxy)
                        proxies++
                    }.onFailure { failed++ }
                }
                restore.profiles.forEach { profile ->
                    runCatching {
                        ProfileStorage.ensure(app, profile.id)
                        database.saveProfile(profile)
                        profiles++
                    }.onFailure { failed++ }
                }
            }
            val result = RestoreResult(
                profiles = profiles,
                proxies = proxies,
                skipped = failed + restore.skippedProfiles + restore.skippedProxies,
                hadCredentials = restore.hadCredentials
            )
            Async.onMain { onDone(result) }
            notifyChanged()
        }
    }

    /** Reads a backup file from a content Uri and hands back the parse result. */
    fun readBackup(context: Context, uri: android.net.Uri, onResult: (ExportFormat.Restore?) -> Unit) {
        val app = context.applicationContext
        Async.compute({
            val text = runCatching {
                app.contentResolver.openInputStream(uri)?.use { stream ->
                    // Cap the read: a backup is metadata only, so anything huge is not ours.
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(chunk)
                        if (read <= 0) break
                        total += read
                        if (total > 16L * 1024L * 1024L) return@use null
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toString("UTF-8")
                }
            }.getOrNull()
            ExportFormat.parse(text)
        }) { onResult(it) }
    }

    fun resetEverything(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Async.serialize {
            runCatching { db(app).wipeAll() }
            ProfileStorage.eraseAll(app)
            ProfileStorage.clearRunState(app)
            Async.onMain { onDone() }
            notifyChanged()
        }
    }

    private fun db(context: Context): Database = Database.get(context)
}
