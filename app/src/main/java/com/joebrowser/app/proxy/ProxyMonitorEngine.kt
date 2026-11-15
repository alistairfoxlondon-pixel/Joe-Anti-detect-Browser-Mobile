package com.joebrowser.app.proxy

import android.content.Context
import com.joebrowser.app.AppEvents
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.MonitorAlert
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.data.ProxyConfig
import com.joebrowser.app.data.SavedProxy
import com.joebrowser.app.fingerprint.FingerprintFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The proxy monitoring engine.
 *
 * Responsibilities
 * ----------------
 *  - Periodically health-checks every proxy the user asked to watch (library proxies with
 *    `watch = true` and profile proxies with `tools.proxyWatch = true`), honouring each
 *    target's own interval.
 *  - Detects three transitions and raises a real-time alert for each:
 *      * **offline**   — two consecutive failures (one failure is usually a blip),
 *      * **recovered** — a watched proxy answers again,
 *      * **rotated**   — the exit IP changed since the last successful check.
 *  - Persists every alert to the database (so the UI can show a history) *and* broadcasts
 *    it, so an open screen updates within the same frame.
 *  - Keeps rotating proxy locale glued to the new exit country when the profile has
 *    auto-match enabled.
 *
 * This object holds no Android components. [ProxyMonitorService] owns the foreground
 * notification and simply drives [runCycle] on its own schedule, which keeps the engine
 * unit-testable exactly as written plus easy to reason about.
 */
object ProxyMonitorEngine {

    private const val TAG = "JoeMonitor"
    private const val FAIL_THRESHOLD = 2
    private const val MIN_INTERVAL_SEC = 30

    private val running = AtomicBoolean(false)

    data class Target(
        val key: String,
        val label: String,
        val config: ProxyConfig,
        val intervalSec: Int,
        val savedProxyId: String? = null,
        val profileId: String? = null
    )

    data class CycleResult(
        val checked: Int,
        val online: Int,
        val offline: Int,
        val rotated: Int,
        val recovered: Int
    )

    val isRunning: Boolean get() = running.get()

    /** Collects every watched target from the current data set. Safe from any thread. */
    fun collectTargets(context: Context): List<Target> {
        val database = Database.get(context)
        val globalInterval = Prefs.monitorIntervalSec(context)
        val targets = mutableListOf<Target>()

        runCatching {
            database.getAllProxies().forEach { saved ->
                val cfg = saved.config
                if (cfg.watch && cfg.enabled) {
                    targets.add(
                        Target(
                            key = "saved:" + saved.id,
                            label = saved.displayName(),
                            config = cfg,
                            intervalSec = cfg.watchIntervalSec.coerceAtLeast(MIN_INTERVAL_SEC),
                            savedProxyId = saved.id
                        )
                    )
                }
            }
        }.onFailure { android.util.Log.w(TAG, "Could not read saved proxies", it) }

        runCatching {
            database.getAllProfiles().forEach { profile ->
                if (profile.tools.proxyWatch && profile.proxy.enabled) {
                    targets.add(
                        Target(
                            key = "profile:" + profile.id,
                            label = profile.displayName(),
                            config = profile.proxy,
                            intervalSec = profile.tools.proxyWatchIntervalSec
                                .coerceAtLeast(MIN_INTERVAL_SEC)
                                .let { if (it <= 0) globalInterval else it },
                            profileId = profile.id
                        )
                    )
                }
            }
        }.onFailure { android.util.Log.w(TAG, "Could not read profile proxies", it) }

        return targets
    }

    fun watchedCount(context: Context): Int = collectTargets(context).size

    /**
     * Runs one monitoring pass. Skips targets whose interval has not elapsed, so a 30-second
     * service tick does not hammer a proxy the user set to five minutes.
     *
     * Call from a background thread; [onComplete] is delivered on the main thread.
     */
    fun runCycle(context: Context, force: Boolean = false, onComplete: (CycleResult) -> Unit = {}) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            onComplete(CycleResult(0, 0, 0, 0, 0))
            return
        }
        Async.bg {
            val result = try {
                executeCycle(app, force)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Monitor cycle failed", t)
                CycleResult(0, 0, 0, 0, 0)
            } finally {
                running.set(false)
            }
            Async.onMain { onComplete(result) }
        }
    }

    private fun executeCycle(context: Context, force: Boolean): CycleResult {
        val database = Database.get(context)
        val now = System.currentTimeMillis()
        val targets = collectTargets(context)

        if (targets.isEmpty()) {
            database.putStatus(STATUS_KEY, STATUS_IDLE)
            return CycleResult(0, 0, 0, 0, 0)
        }

        var checked = 0
        var online = 0
        var offline = 0
        var rotated = 0
        var recovered = 0

        targets.forEach { target ->
            try {
                val state = database.getWatchState(target.key)
                val due = force || state == null || now - state.lastCheck >= target.intervalSec * 1000L
                if (!due) {
                    if (state != null) {
                        if (state.fails == 0) online++ else offline++
                    }
                    return@forEach
                }

                checked++
                val result = ProxyTester.test(target.config)
                val previousIp = state?.ip.orEmpty()
                val wasNotifiedOffline = state?.notifiedOff ?: false

                if (result.ok) {
                    online++
                    val newIp = result.ip
                    val changed = previousIp.isNotBlank() && newIp.isNotBlank() && previousIp != newIp
                    database.setWatchState(target.key, newIp, 0, false, result.latencyMs)

                    if (changed) {
                        rotated++
                        raise(
                            context, MonitorAlert.KIND_ROTATED,
                            target.label,
                            "Exit IP " + previousIp + " -> " + newIp
                        )
                        applyRotation(context, target, result)
                    } else if (wasNotifiedOffline) {
                        recovered++
                        raise(
                            context, MonitorAlert.KIND_RECOVERED,
                            target.label,
                            "Reachable again at " + newIp + " (" + result.latencyMs + " ms)"
                        )
                    }
                } else {
                    offline++
                    val fails = (state?.fails ?: 0) + 1
                    if (fails >= FAIL_THRESHOLD && !wasNotifiedOffline) {
                        raise(
                            context, MonitorAlert.KIND_OFFLINE,
                            target.label,
                            "Failing: " + result.error.ifBlank { "no response" }
                        )
                        database.setWatchState(target.key, previousIp, fails, true, -1)
                    } else {
                        database.setWatchState(target.key, previousIp, fails, wasNotifiedOffline, -1)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "Check failed for " + target.key, t)
            }
        }

        database.putStatus(STATUS_KEY, STATUS_IDLE)
        database.putStatus(KEY_LAST_SWEEP, now.toString())

        if (checked > 0) {
            AppEvents.emitProxyState(context, checked, online, offline)
            ProfileRepository.notifyExternalChange()
        }
        return CycleResult(checked, online, offline, rotated, recovered)
    }

    /**
     * Re-aligns a profile's fingerprint locale to the new exit country after a rotation.
     * This is what stops "IP says Germany, browser says Asia/Dhaka" from happening.
     */
    private fun applyRotation(context: Context, target: Target, result: ProxyTestResult) {
        if (target.profileId == null) return
        if (!result.countryCode.isNotBlank()) return
        val database = Database.get(context)
        val profile: Profile = database.getProfile(target.profileId) ?: return
        if (!profile.fp.autoMatchProxy || !profile.tools.autoSyncLocale) return

        val before = profile.fp.timezone + "/" + profile.fp.language
        FingerprintFactory.applyProxyLocale(profile.fp, result.countryCode)
        profile.proxy = profile.proxy.duplicate().apply {
            lastCountry = result.country
            lastCountryCode = result.countryCode
            lastCity = result.city
            lastTimezone = result.timezone
            rotations += 1
            lastCheckedAt = System.currentTimeMillis()
            latencyMs = result.latencyMs
        }
        val after = profile.fp.timezone + "/" + profile.fp.language
        database.saveProfile(profile)

        if (before != after) {
            raise(
                context, MonitorAlert.KIND_ROTATED,
                profile.displayName(),
                "Locale re-aligned to " + profile.fp.language + " · " + profile.fp.timezone
            )
        }
    }

    private fun raise(context: Context, kind: String, target: String, detail: String) {
        runCatching {
            Database.get(context).addAlert(MonitorAlert(kind = kind, target = target, detail = detail))
        }
        AppEvents.emitMonitorAlert(context, kind, target, detail)
        ProxyNotifier.notifyAlert(context, kind, target, detail)
    }

    // ------------------------------------------------------------- status text

    const val STATUS_KEY = "monitor_state"
    const val STATUS_IDLE = "idle"
    const val KEY_LAST_SWEEP = "monitor_last_sweep"

    /** Human status for the Proxies screen; mirrors what the service is doing right now. */
    fun statusLine(context: Context): String {
        val database = Database.get(context)
        val targets = collectTargets(context).size
        if (targets == 0) return "Monitor idle — no proxies watched"
        val last = database.getStatus(KEY_LAST_SWEEP)?.toLongOrNull() ?: 0L
        if (last <= 0L) return "Monitoring " + targets + " proxy" + (if (targets == 1) "" else "ies") + " · first sweep pending"
        val ageSec = ((System.currentTimeMillis() - last) / 1000L).coerceAtLeast(0L)
        val age = if (ageSec < 90) ageSec.toString() + "s ago" else (ageSec / 60) .toString() + "m ago"
        return "Monitoring " + targets + " proxy" + (if (targets == 1) "" else "ies") + " · last sweep " + age
    }

    /** Convenience for the browser activity: check one profile's proxy right before launch. */
    fun verifyBeforeLaunch(context: Context, profile: Profile, onResult: (ProxyTestResult) -> Unit) {
        if (!profile.proxy.enabled) {
            onResult(ProxyTestResult(ok = true, error = ""))
            return
        }
        val app = context.applicationContext
        Async.bg {
            val result = runCatching { ProxyTester.test(profile.proxy) }
                .getOrElse { ProxyTestResult(ok = false, error = it.message ?: "Unreachable") }
            if (result.ok) {
                runCatching {
                    val database = Database.get(app)
                    val source = database.getProfile(profile.id) ?: profile
                    source.proxy = source.proxy.duplicate().apply {
                        lastCheckedAt = System.currentTimeMillis()
                        latencyMs = result.latencyMs
                        lastIp = result.ip
                        lastCountry = result.country
                        lastCountryCode = result.countryCode
                        lastCity = result.city
                        lastTimezone = result.timezone
                        lastError = ""
                        failStreak = 0
                    }
                    if (source.fp.autoMatchProxy && source.tools.autoSyncLocale && result.countryCode.isNotBlank()) {
                        FingerprintFactory.applyProxyLocale(source.fp, result.countryCode)
                    }
                    database.saveProfile(source)
                }
            }
            Async.onMain { onResult(result) }
        }
    }

    // ------------------------------------------------------------ direct device

    /** Cached "this device" public IP, shown on the Proxies screen. */
    @Volatile
    private var deviceCache: ProxyTestResult? = null

    fun deviceResult(): ProxyTestResult? = deviceCache

    fun refreshDevice(onResult: (ProxyTestResult) -> Unit) {
        Async.bg {
            val result = ProxyTester.checkDirectDevice()
            deviceCache = result
            Async.onMain { onResult(result) }
        }
    }
}
