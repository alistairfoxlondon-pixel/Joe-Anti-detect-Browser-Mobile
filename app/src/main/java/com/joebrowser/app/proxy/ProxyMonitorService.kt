package com.joebrowser.app.proxy

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.joebrowser.app.data.Prefs

/**
 * Foreground service that drives [ProxyMonitorEngine] on a schedule.
 *
 * Lifecycle contract:
 *  - Started only when at least one proxy is watched, and it stops itself as soon as the
 *    watch list becomes empty, so it never lingers as an idle foreground service.
 *  - The heavy lifting happens on a dedicated [HandlerThread] + the shared background pool
 *    inside the engine; this class only owns the notification and the timer.
 *  - Every OS-level call that can throw on a restricted device (foreground promotion,
 *    notification channel) is guarded: if the OS refuses, the service shuts down cleanly
 *    instead of crashing the process.
 */
class ProxyMonitorService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("joe-monitor").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val watched = runCatching { ProxyMonitorEngine.watchedCount(this) }.getOrDefault(0)
        if (watched == 0 || !Prefs.monitorEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoteToForeground(watched)) {
            stopSelf()
            return START_NOT_STICKY
        }
        scheduleNext(initialDelayMs = 1500L)
        return START_STICKY
    }

    private fun promoteToForeground(watched: Int): Boolean = try {
        val notification = ProxyNotifier.monitorNotification(this, watched)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                ProxyNotifier.NOTIF_MONITOR, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ProxyNotifier.NOTIF_MONITOR, notification)
        }
        true
    } catch (t: Throwable) {
        false
    }

    private fun scheduleNext(initialDelayMs: Long = 0L) {
        handler.removeCallbacks(cycleRunnable)
        handler.postDelayed(cycleRunnable, initialDelayMs)
    }

    private val cycleRunnable = Runnable {
        try {
            val watched = ProxyMonitorEngine.watchedCount(this)
            if (watched == 0) {
                stopSelf()
                return@Runnable
            }
            ProxyMonitorEngine.runCycle(this, force = false)
        } catch (t: Throwable) {
            // A failed cycle must not kill the timer.
        }
        handler.postDelayed(this::scheduleNextNext, tickDelayMs())
    }

    /** Re-reads the user's chosen cadence each tick so a settings change applies immediately. */
    private fun tickDelayMs(): Long =
        (Prefs.monitorIntervalSec(this).coerceIn(30, 3600) * 1000L).coerceAtLeast(30_000L)

    private fun scheduleNextNext() {
        handler.removeCallbacks(cycleRunnable)
        handler.postDelayed(cycleRunnable, tickDelayMs())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { handler.removeCallbacksAndMessages(null) }
        runCatching { thread.quitSafely() }
        ProxyNotifier.cancelMonitor(this)
        super.onDestroy()
    }

    companion object {
        /**
         * Starts the monitor when at least one proxy is watched. Safe to call from any screen
         * and from [BootReceiver]; background-start restrictions degrade to a no-op because
         * the next app launch re-arms it.
         */
        fun startIfNeeded(context: Context) {
            val app = context.applicationContext
            if (!Prefs.monitorEnabled(app)) return
            val watched = runCatching { ProxyMonitorEngine.watchedCount(app) }.getOrDefault(0)
            if (watched <= 0) {
                stop(context)
                return
            }
            val intent = Intent(app, ProxyMonitorService::class.java)
            runCatching { ContextCompat.startForegroundService(app, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.applicationContext.stopService(Intent(context, ProxyMonitorService::class.java)) }
        }
    }
}

/** Re-arms the monitor after a reboot when at least one proxy is watched. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ProxyMonitorService.startIfNeeded(context)
        }
    }
}
