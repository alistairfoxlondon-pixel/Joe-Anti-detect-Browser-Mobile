package com.joebrowser.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application-wide preferences.
 *
 * Profile data never lives here — this file only holds device-level settings (theme,
 * sorting, defaults, last-used profile). Every getter has a typed default so a preference
 * file written by another build cannot crash the reader.
 */
object Prefs {

    private const val FILE = "joe_browser_prefs"

    private const val KEY_THEME = "theme_mode"                 // 0 system, 1 dark, 2 light
    private const val KEY_DEFAULT_START = "default_start_url"
    private const val KEY_HISTORY = "history_enabled"
    private const val KEY_TRASH = "trash_enabled"
    private const val KEY_SORT = "profile_sort"                // 0 recent, 1 name, 2 created, 3 os, 4 group
    private const val KEY_DEFAULT_GROUP = "default_group"
    private const val KEY_NOTIF_ASKED = "notification_asked"
    private const val KEY_LAST_PROFILE_ID = "last_profile_id"
    private const val KEY_MONITOR_INTERVAL = "monitor_interval_sec"
    private const val KEY_AUTO_ROTATE = "auto_rotate_on_failure"
    private const val KEY_MONITOR_ENABLED = "monitor_enabled"
    private const val KEY_HUMAN_PASTE = "global_human_paste"
    private const val KEY_HUMAN_SPEED = "global_human_speed"
    private const val KEY_TRACKER = "global_tracker"
    private const val KEY_HUMAN_JITTER = "human_jitter"
    private const val KEY_UNLOCK_SELECTION = "global_unlock_selection"
    private const val KEY_LAYOUT_MIGRATED = "layout_v2"

    fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- appearance

    /** 0 = follow system, 1 = dark, 2 = light. */
    fun themeMode(context: Context): Int = sp(context).getInt(KEY_THEME, 0).coerceIn(0, 2)

    fun setThemeMode(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_THEME, mode.coerceIn(0, 2)).apply()
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

    fun themeLabel(mode: Int): String = when (mode) {
        1 -> "Dark"
        2 -> "Light"
        else -> "System"
    }

    // ---------------------------------------------------------------- browsing

    fun defaultStartUrl(context: Context): String =
        sp(context).getString(KEY_DEFAULT_START, DEFAULT_START_URL)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_START_URL

    fun setDefaultStartUrl(context: Context, url: String) {
        val clean = url.trim().ifBlank { DEFAULT_START_URL }
        sp(context).edit().putString(KEY_DEFAULT_START, clean).apply()
    }

    fun historyEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_HISTORY, true)
    fun setHistoryEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_HISTORY, value).apply()

    fun trashEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_TRASH, true)
    fun setTrashEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_TRASH, value).apply()

    fun profileSort(context: Context): Int = sp(context).getInt(KEY_SORT, 0).coerceIn(0, 4)
    fun setProfileSort(context: Context, value: Int) =
        sp(context).edit().putInt(KEY_SORT, value.coerceIn(0, 4)).apply()

    fun defaultGroup(context: Context): String = sp(context).getString(KEY_DEFAULT_GROUP, "") ?: ""

    fun notificationAsked(context: Context): Boolean = sp(context).getBoolean(KEY_NOTIF_ASKED, false)
    fun setNotificationAsked(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    fun lastProfileId(context: Context): String = sp(context).getString(KEY_LAST_PROFILE_ID, "") ?: ""
    fun setLastProfileId(context: Context, value: String) {
        if (value.isNotBlank()) sp(context).edit().putString(KEY_LAST_PROFILE_ID, value).apply()
    }

    // ------------------------------------------------------------ tool defaults

    fun monitorEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_MONITOR_ENABLED, true)
    fun setMonitorEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_MONITOR_ENABLED, value).apply()

    fun autoRotateOnFailure(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_ROTATE, false)
    fun setAutoRotateOnFailure(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_AUTO_ROTATE, value).apply()

    /** Background health-check cadence in seconds (30–3600). */
    fun monitorIntervalSec(context: Context): Int =
        sp(context).getInt(KEY_MONITOR_INTERVAL, 120).coerceIn(30, 3600)

    fun setMonitorIntervalSec(context: Context, value: Int) =
        sp(context).edit().putInt(KEY_MONITOR_INTERVAL, value.coerceIn(30, 3600)).apply()

    fun humanPasteEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_HUMAN_PASTE, true)
    fun setHumanPasteEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_HUMAN_PASTE, value).apply()

    fun humanPasteSpeed(context: Context): String =
        sp(context).getString(KEY_HUMAN_SPEED, "natural")?.takeIf { it in ToolConfig.SPEEDS } ?: "natural"

    fun setHumanPasteSpeed(context: Context, value: String) {
        if (value in ToolConfig.SPEEDS) sp(context).edit().putString(KEY_HUMAN_SPEED, value).apply()
    }

    fun trackerEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_TRACKER, true)
    fun setTrackerEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_TRACKER, value).apply()

    fun unlockSelection(context: Context): Boolean = sp(context).getBoolean(KEY_UNLOCK_SELECTION, true)
    fun setUnlockSelection(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_UNLOCK_SELECTION, value).apply()

    /** Per-profile keystroke rhythm: on by default, toggled from the profile editor. */
    fun humanPasteJitter(context: Context): Boolean = sp(context).getBoolean(KEY_HUMAN_JITTER, true)

    fun setHumanPasteJitter(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_HUMAN_JITTER, value).apply()
}
