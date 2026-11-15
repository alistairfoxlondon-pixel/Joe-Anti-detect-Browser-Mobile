package com.joebrowser.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.joebrowser.app.R

/**
 * Shared UI helpers.
 *
 * The rule behind these helpers: no screen composes raw dp arithmetic by hand and no screen
 * invents a colour. Everything routes through here or through the theme, which is what keeps
 * spacing consistent and light/dark identical.
 */
object UiKit {

    private val avatarPalette = intArrayOf(
        0xFF2563EB.toInt(), 0xFF7C4DE0.toInt(), 0xFF0E8F7E.toInt(), 0xFFCC5A35.toInt(),
        0xFF2F9E52.toInt(), 0xFFB27A18.toInt(), 0xFF4B63C9.toInt(), 0xFFB04278.toInt()
    )

    fun avatarColor(key: String): Int {
        var hash = 7
        key.forEach { hash = (hash * 31 + it.code) and 0x7FFFFFFF }
        return avatarPalette[hash % avatarPalette.size]
    }

    fun avatarBackground(key: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(avatarColor(key))
    }

    fun dotBackground(colorRes: Int, context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ContextCompat.getColor(context, colorRes))
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun dpFloat(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    fun hideKeyboard(view: View?) {
        if (view == null) return
        runCatching {
            val manager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    fun showKeyboard(view: View?) {
        if (view == null) return
        runCatching {
            view.requestFocus()
            val manager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }


    fun rangeFilter(min: Int, max: Int): InputFilter = InputFilter { source, _, _, dest, dstart, dend ->
        val candidate = source.toString()
        if (candidate.isEmpty()) return@InputFilter null
        if (!candidate.all { it.isDigit() }) return@InputFilter ""
        val text = (dest.subSequence(0, dstart).toString() + candidate + dest.subSequence(dend, dest.length))
        val value = text.toIntOrNull() ?: return@InputFilter ""
        if (value in min..max) null else ""
    }


    fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color)
    )


    fun flagEmoji(countryCode: String?): String {
        val code = countryCode?.uppercase()?.takeIf { it.length == 2 } ?: return ""
        val first = 0x1F1E6 - 'A'.code + code[0].code
        val second = 0x1F1E6 - 'A'.code + code[1].code
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun osIconRes(os: String?): Int = when (os) {
        "Windows 11", "Windows 10" -> R.drawable.ic_os_windows
        "macOS" -> R.drawable.ic_os_apple
        "iPhone" -> R.drawable.ic_os_phone
        "iPad" -> R.drawable.ic_os_tablet
        "Android" -> R.drawable.ic_os_android
        "Linux" -> R.drawable.ic_os_linux
        else -> R.drawable.ic_os_android
    }


    fun formatRelative(ts: Long): String {
        if (ts <= 0L) return "Never"
        val delta = System.currentTimeMillis() - ts
        return when {
            delta < 60_000L -> "Just now"
            delta < 3_600_000L -> "${delta / 60_000L}m ago"
            delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
            delta < 7L * 86_400_000L -> "${delta / 86_400_000L}d ago"
            else -> java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
                .format(java.util.Date(ts))
        }
    }

    fun formatLatency(ms: Int): String = if (ms < 0) "—" else "$ms ms"
}
