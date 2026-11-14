package com.joebrowser.app

import android.content.Context
import android.content.Intent

/** Tiny in-process event bus implemented with broadcasts (works across activities). */
object AppEvents {
    const val ACTION_PROXY_STATE = "com.joebrowser.app.PROXY_STATE"
    const val ACTION_PROFILE_CHANGED = "com.joebrowser.app.PROFILE_CHANGED"
    const val EXTRA_DETAIL = "detail"

    fun emitProxyState(context: Context, detail: String) {
        context.sendBroadcast(Intent(ACTION_PROXY_STATE).setPackage(context.packageName).putExtra(EXTRA_DETAIL, detail))
    }

    fun emitProfilesChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_PROFILE_CHANGED).setPackage(context.packageName))
    }
}
