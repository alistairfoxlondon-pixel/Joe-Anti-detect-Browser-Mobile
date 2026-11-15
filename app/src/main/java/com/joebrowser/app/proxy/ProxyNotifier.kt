package com.joebrowser.app.proxy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.joebrowser.app.R
import com.joebrowser.app.data.MonitorAlert
import com.joebrowser.app.ui.MainActivity

/**
 * Notification plumbing for the monitor.
 *
 * Two channels, deliberately different:
 *  - `proxy_alerts` (high importance) for offline / recovered / rotation events. These are
 *    the "act now" notifications.
 *  - `proxy_monitor` (low importance, no badge) for the ongoing foreground service state, so
 *    it never competes visually with the alerts.
 */
object ProxyNotifier {

    const val CHANNEL_ALERTS = "proxy_alerts"
    const val CHANNEL_MONITOR = "proxy_monitor"
    const val NOTIF_MONITOR = 4201

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Proxy alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A watched proxy went offline, recovered, or rotated its exit IP"
                    enableVibration(true)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MONITOR, "Proxy monitor", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing proxy health monitoring"
                    setShowBadge(false)
                }
            )
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "proxies")
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** One-shot alert notification. Never throws when notifications are denied. */
    fun notifyAlert(context: Context, kind: String, target: String, detail: String) {
        if (!canNotify(context)) return
        val title = when (kind) {
            MonitorAlert.KIND_OFFLINE -> "Proxy offline — " + target
            MonitorAlert.KIND_RECOVERED -> "Proxy back online — " + target
            MonitorAlert.KIND_ROTATED -> "Exit IP rotated — " + target
            else -> "Proxy monitor — " + target
        }
        runCatching {
            ensureChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_shield_check)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify((kind + ":" + target).hashCode(), notification)
        }
    }

    fun monitorNotification(context: Context, watched: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle("Proxy monitor active")
            .setContentText(
                if (watched == 1) "Watching 1 proxy for outages and IP rotation"
                else "Watching " + watched + " proxies for outages and IP rotation"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
            .build()

    /** Refreshes the ongoing notification in place (watch count changes as proxies are edited). */
    fun updateMonitor(context: Context, watched: Int) {
        runCatching {
            if (!canNotify(context)) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_MONITOR, monitorNotification(context, watched))
        }
    }

    fun cancelMonitor(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIF_MONITOR)
        }
    }
}
