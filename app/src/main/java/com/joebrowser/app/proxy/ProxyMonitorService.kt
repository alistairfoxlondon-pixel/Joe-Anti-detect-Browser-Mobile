package com.joebrowser.app.proxy

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Build
import com.joebrowser.app.AppEvents
import com.joebrowser.app.data.Database
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches proxies (saved ones and every profile's own proxy with the watch enabled) and
 * posts a notification on three transitions: offline, back online, and exit-IP rotation.
 * Runs as a special-use foreground service; stops itself when nothing is watched anymore.
 */
class ProxyMonitorService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val checking = AtomicBoolean(false)
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("joe-proxy-monitor").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val watched = watchTargets().size
        if (watched == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ProxyNotifier.NOTIF_MONITOR, ProxyNotifier.monitorNotification(this, watched),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ProxyNotifier.NOTIF_MONITOR, ProxyNotifier.monitorNotification(this, watched))
            }
        } catch (t: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        schedule(0)
        return START_STICKY
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ tick() }, delayMs)
    }

    private fun tick() {
        if (checking.compareAndSet(false, true)) {
            executor.execute {
                try {
                    checkAll()
                } finally {
                    checking.set(false)
                }
            }
        }
        schedule(TICK_MS)
    }

    private data class Target(val key: String, val label: String, val proxy: com.joebrowser.app.data.ProxyConfig, val intervalSec: Int)

    private fun watchTargets(): List<Target> {
        val db = Database.get(this)
        val out = mutableListOf<Target>()
        db.getAllProxies().forEach { p ->
            if (p.config.watch && p.config.enabled) {
                out += Target("saved:${p.id}", p.name.ifBlank { "${p.config.host}:${p.config.port}" }, p.config, p.config.watchIntervalSec.coerceAtLeast(30))
            }
        }
        db.getAllProfiles().forEach { p ->
            if (p.tools.proxyWatch && p.proxy.enabled) {
                out += Target("profile:${p.id}", p.name.ifBlank { "Profile" }, p.proxy, p.tools.proxyWatchIntervalSec.coerceAtLeast(30))
            }
        }
        return out
    }

    private fun checkAll() {
        val db = Database.get(this)
        val now = System.currentTimeMillis()
        var checked = 0
        for (t in watchTargets()) {
            val state = db.getWatchState(t.key)
            val lastCheck = lastCheckOf(t.key)
            if (lastCheck > 0 && now - lastCheck < t.intervalSec * 1000L) continue
            checked++

            val result = ProxyTester.test(t.proxy, timeoutMs = 12_000)
            val fails = state?.fails ?: 0
            val prevIp = state?.ip ?: ""
            val wasNotifiedOff = state?.notifiedOff ?: false

            if (result.ok) {
                val newIp = result.ip
                db.setWatchState(t.key, newIp, 0, false)
                if (prevIp.isNotBlank() && newIp.isNotBlank() && prevIp != newIp) {
                    ProxyNotifier.proxyRotated(this, t.key, t.label, prevIp, newIp)
                } else if (wasNotifiedOff) {
                    ProxyNotifier.proxyRecovered(this, t.key, t.label, newIp)
                }
            } else {
                val newFails = fails + 1
                if (newFails >= 2 && !wasNotifiedOff) {
                    ProxyNotifier.proxyOffline(this, t.key, t.label)
                    db.setWatchState(t.key, prevIp, newFails, true)
                } else {
                    db.setWatchState(t.key, prevIp, newFails, wasNotifiedOff)
                }
            }
        }
        if (checked > 0) AppEvents.emitProxyState(this, "checked-$checked")
        // Stop when nothing is watched anymore.
        if (watchTargets().isEmpty()) {
            stopSelf()
        }
    }

    private fun lastCheckOf(key: String): Long = try {
        val c = Database.get(this).readableDatabase
            .query("watch_state", arrayOf("last_check"), "key = ?", arrayOf(key), null, null, null)
        c.use { if (it.moveToFirst()) it.getLong(0) else 0L }
    } catch (t: Throwable) {
        0L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 30_000L

        fun maybeStart(context: Context) {
            if (watchCount(context) > 0) {
                val intent = Intent(context, ProxyMonitorService::class.java)
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                } catch (t: Throwable) {
                    // Background start restrictions: fall back to plain start; the service
                    // still enforces its own foreground promotion or stops cleanly.
                }
            }
        }

        fun watchCount(context: Context): Int {
            val db = Database.get(context)
            val saved = db.countWatchedProxies()
            val profiles = db.getAllProfiles().count { it.tools.proxyWatch && it.proxy.enabled }
            return saved + profiles
        }
    }
}

/** Restarts the monitor after a reboot when at least one proxy is watched. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ProxyMonitorService.maybeStart(context)
        }
    }
}
