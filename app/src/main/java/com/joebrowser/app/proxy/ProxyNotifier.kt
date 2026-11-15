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
import com.joebrowser.app.ui.MainActivity

object ProxyNotifier {
    const val CHANNEL_ALERTS = "proxy_alerts"
    const val CHANNEL_MONITOR = "proxy_monitor"
    const val NOTIF_MONITOR = 4201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Proxy alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Proxy offline and IP rotation alerts"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MONITOR, "Proxy monitor", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing proxy connectivity monitoring"
                    setShowBadge(false)
                }
            )
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun tapIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "proxies")
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(context: Context, id: Int, title: String, body: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
    }

    fun monitorNotification(context: Context, watched: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_shield_check)
            .setContentTitle("Joe Browser proxy monitor")
            .setContentText(if (watched == 1) "Watching 1 proxy for dropouts and rotation" else "Watching $watched proxies for dropouts and rotation")
            .setOngoing(true)
            .setContentIntent(tapIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun proxyOffline(context: Context, key: String, label: String) =
        post(context, key.hashCode(), "Proxy offline — $label",
            "$label stopped responding. The profile using it may show your real IP or fail to load pages.")

    fun proxyRecovered(context: Context, key: String, label: String, ip: String) =
        post(context, key.hashCode() xor 0x1f3, "Proxy back online — $label",
            "$label is reachable again (IP ${ip.ifBlank { "unknown" }}).")

    fun proxyRotated(context: Context, key: String, label: String, oldIp: String, newIp: String) =
        post(context, key.hashCode() xor 0x5e5, "Proxy rotated — $label",
            "Exit IP changed from ${oldIp.ifBlank { "?" }} to ${newIp.ifBlank { "?" }}. Accounts bound to an IP may flag this.")

    const val ACTION_PROXY_STATE = com.joebrowser.app.AppEvents.ACTION_PROXY_STATE
}
