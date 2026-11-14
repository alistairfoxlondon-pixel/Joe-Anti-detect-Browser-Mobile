package com.joebrowser.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/** Application-wide preferences (theme, defaults, switches). Profile data never lives here. */
object Prefs {
    private const val FILE = "joe_browser_prefs"
    private const val KEY_THEME = "theme_mode"          // 0 system, 1 dark, 2 light
    private const val KEY_DEFAULT_START = "default_start_url"
    private const val KEY_HISTORY = "history_enabled"
    private const val KEY_TRASH = "trash_enabled"
    private const val KEY_SORT = "profile_sort"         // 0 recent, 1 name, 2 created, 3 os, 4 group
    private const val KEY_DEFAULT_GROUP = "default_group"
    private const val KEY_NOTIF_ASKED = "notification_asked"
    private const val KEY_LAST_PROFILE_ID = "last_profile_id"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun themeMode(context: Context): Int = sp(context).getInt(KEY_THEME, 0)

    fun setThemeMode(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_THEME, mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: Int) {
        val night = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(night)
    }

    fun defaultStartUrl(context: Context): String =
        sp(context).getString(KEY_DEFAULT_START, DEFAULT_START_URL) ?: DEFAULT_START_URL

    fun setDefaultStartUrl(context: Context, url: String) =
        sp(context).edit().putString(KEY_DEFAULT_START, url).apply()

    fun historyEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_HISTORY, true)
    fun setHistoryEnabled(context: Context, v: Boolean) = sp(context).edit().putBoolean(KEY_HISTORY, v).apply()

    fun trashEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_TRASH, true)
    fun setTrashEnabled(context: Context, v: Boolean) = sp(context).edit().putBoolean(KEY_TRASH, v).apply()

    fun profileSort(context: Context): Int = sp(context).getInt(KEY_SORT, 0)
    fun setProfileSort(context: Context, v: Int) = sp(context).edit().putInt(KEY_SORT, v).apply()

    fun defaultGroup(context: Context): String = sp(context).getString(KEY_DEFAULT_GROUP, "") ?: ""
    fun setDefaultGroup(context: Context, v: String) = sp(context).edit().putString(KEY_DEFAULT_GROUP, v).apply()

    fun notificationAsked(context: Context): Boolean = sp(context).getBoolean(KEY_NOTIF_ASKED, false)
    fun setNotificationAsked(context: Context, v: Boolean) = sp(context).edit().putBoolean(KEY_NOTIF_ASKED, v).apply()

    fun lastProfileId(context: Context): String = sp(context).getString(KEY_LAST_PROFILE_ID, "") ?: ""
    fun setLastProfileId(context: Context, v: String) = sp(context).edit().putString(KEY_LAST_PROFILE_ID, v).apply()
}
