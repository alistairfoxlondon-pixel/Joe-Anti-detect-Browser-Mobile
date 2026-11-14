package com.joebrowser.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.joebrowser.app.R

/** Shared UI helpers. */
object UiKit {
    private val avatarPalette = intArrayOf(
        0xFF5B9BF7.toInt(), 0xFF8E6BE0.toInt(), 0xFF2FB3A6.toInt(), 0xFFE07A5F.toInt(),
        0xFF4EC38A.toInt(), 0xFFD4A24E.toInt(), 0xFF6E8FE8.toInt(), 0xFFC76BA8.toInt()
    )

    fun avatarColor(key: String): Int {
        var h = 0
        for (c in key) h = (h * 31 + c.code) and 0x7fffffff
        return avatarPalette[h % avatarPalette.size]
    }

    fun avatarDrawable(color: Int, sizeDp: Int, context: Context): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    fun textAvatar(context: Context, initials: String, color: Int, sizeDp: Int): TextView {
        val dp = context.resources.displayMetrics.density
        val px = (sizeDp * dp).toInt()
        return TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(px, px)
            gravity = Gravity.CENTER
            text = initials
            setTextColor(0xFFFFFFFF.toInt())
            textSize = sizeDp * 0.38f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    val FLAG_EMOJI = mapOf(
        "US" to "🇺🇸", "GB" to "🇬🇧", "CA" to "🇨🇦", "AU" to "🇦🇺", "DE" to "🇩🇪", "FR" to "🇫🇷",
        "ES" to "🇪🇸", "IT" to "🇮🇹", "NL" to "🇳🇱", "BE" to "🇧🇪", "CH" to "🇨🇭", "AT" to "🇦🇹",
        "SE" to "🇸🇪", "NO" to "🇳🇴", "DK" to "🇩🇰", "FI" to "🇫🇮", "PL" to "🇵🇱", "PT" to "🇵🇹",
        "IE" to "🇮🇪", "RU" to "🇷🇺", "UA" to "🇺🇦", "TR" to "🇹🇷", "BR" to "🇧🇷", "MX" to "🇲🇽",
        "AR" to "🇦🇷", "CO" to "🇨🇴", "IN" to "🇮🇳", "PK" to "🇵🇰", "BD" to "🇧🇩", "AE" to "🇦🇪",
        "SA" to "🇸🇦", "IL" to "🇮🇱", "SG" to "🇸🇬", "MY" to "🇲🇾", "TH" to "🇹🇭", "VN" to "🇻🇳",
        "ID" to "🇮🇩", "PH" to "🇵🇭", "HK" to "🇭🇰", "CN" to "🇨🇳", "JP" to "🇯🇵", "KR" to "🇰🇷",
        "ZA" to "🇿🇦", "NG" to "🇳🇬", "EG" to "🇪🇬"
    )

    fun flag(code: String?): String = code?.uppercase()?.let { FLAG_EMOJI[it] } ?: "🌐"

    fun osIconRes(os: String?): Int = when (os) {
        "Windows 11", "Windows 10" -> R.drawable.ic_os_windows
        "macOS" -> R.drawable.ic_os_apple
        "iPhone" -> R.drawable.ic_os_phone
        "iPad" -> R.drawable.ic_os_tablet
        "Android" -> R.drawable.ic_os_android
        "Linux" -> R.drawable.ic_os_linux
        else -> R.drawable.ic_os_windows
    }
}
