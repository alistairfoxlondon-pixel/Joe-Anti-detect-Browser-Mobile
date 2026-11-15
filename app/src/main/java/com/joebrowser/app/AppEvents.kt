package com.joebrowser.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * In-process event bus.
 *
 * Activities and fragments live in one process, but they do not share a ViewModel, and the
 * foreground monitor service has to be able to tell the UI "a proxy just dropped". A local
 * broadcast with a JSON payload is the smallest mechanism that satisfies all three without
 * pulling in a service-binding layer.
 *
 * Every payload is a JSON object, so listeners can add fields without a breaking change, and
 * a receiver that fails to parse simply ignores the event.
 */
object AppEvents {

    const val ACTION_PROXY_STATE = "com.joebrowser.app.PROXY_STATE"
    const val ACTION_DATA_CHANGED = "com.joebrowser.app.DATA_CHANGED"
    const val ACTION_MONITOR_ALERT = "com.joebrowser.app.MONITOR_ALERT"
    const val EXTRA_PAYLOAD = "payload"

    /** Emitted after a proxy check batch: {checked, online, offline, at}. */
    fun emitProxyState(context: Context, checked: Int, online: Int, offline: Int) {
        emit(
            context, ACTION_PROXY_STATE,
            JSONObject().apply {
                put("checked", checked)
                put("online", online)
                put("offline", offline)
                put("at", System.currentTimeMillis())
            }
        )
    }

    /** Emitted when profiles/proxies/extensions changed so every screen can refresh. */
    fun emitDataChanged(context: Context, reason: String = "") {
        emit(context, ACTION_DATA_CHANGED, JSONObject().apply { put("reason", reason) })
    }

    /** Emitted when the monitor raises offline/recovered/rotation alerts. */
    fun emitMonitorAlert(context: Context, kind: String, target: String, detail: String) {
        emit(
            context, ACTION_MONITOR_ALERT,
            JSONObject().apply {
                put("kind", kind)
                put("target", target)
                put("detail", detail)
                put("at", System.currentTimeMillis())
            }
        )
    }

    private fun emit(context: Context, action: String, payload: JSONObject) {
        try {
            context.applicationContext.sendBroadcast(
                Intent(action).setPackage(context.applicationContext.packageName)
                    .putExtra(EXTRA_PAYLOAD, payload.toString())
            )
        } catch (t: Throwable) {
            // Broadcasting is best-effort: a failed delivery must never break the caller.
        }
    }

    /** Registers a receiver safely with the not-exported flag required on API 34+. */
    fun register(context: Context, receiver: BroadcastReceiver, actions: List<String>) {
        val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
        try {
            ContextCompat.registerReceiver(
                context.applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (t: Throwable) {
            // Ignore: a receiver that cannot register simply means the screen refreshes onResume.
        }
    }

    fun unregister(context: Context, receiver: BroadcastReceiver) {
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (t: Throwable) {
            // Not registered, or the process is going away.
        }
    }

    fun payload(intent: Intent?): JSONObject? = try {
        intent?.getStringExtra(EXTRA_PAYLOAD)?.let { JSONObject(it) }
    } catch (t: Throwable) {
        null
    }
}
