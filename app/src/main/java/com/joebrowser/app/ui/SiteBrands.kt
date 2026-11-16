package com.joebrowser.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.TextView
import com.joebrowser.app.R

/**
 * The detection services a user actually tests with, with enough identity that a tile reads as
 * the service rather than as a generic row.
 *
 * Brand marks: where the vendor publishes a mark that can be reproduced as vector geometry
 * (Chrome, Brave, Firefox, Huawei) the real mark is used from `brand_*`. For the fingerprint
 * services there is no redistributable mark, so the tile carries the service's own wordmark
 * initial on a plate in the service's brand colour — accurate, and never a lookalike of a logo
 * the vendor would not recognise as theirs.
 *
 * [url] is the canonical test page, so a user who taps a tile tests against the same page every
 * time instead of landing on a marketing homepage.
 */
enum class SiteBrand(
    val label: String,
    val purpose: String,
    val url: String,
    /** Wordmark initial on the plate. */
    val initial: String,
    /** The service's own brand colour, used only for its plate. */
    val plateColor: Int,
    /** A real vector brand mark, when the vendor has one that can be reproduced. */
    val markRes: Int? = null
) {
    BROWSERSCAN(
        "BrowserScan", "Fingerprint report", "https://www.browserscan.net/",
        "B", 0xFF2B5CE6.toInt()
    ),
    IPHEY(
        "IPHey", "Trust score", "https://iphey.com/",
        "iP", 0xFF1DA97A.toInt()
    ),
    PIXELSCAN(
        "PixelScan", "Consistency", "https://pixelscan.net/",
        "P", 0xFF8B5CF6.toInt()
    ),
    IPFIGHTER(
        "IPFighter", "Leak test", "https://www.ipfighter.com/browser-fingerprint",
        "iF", 0xFFE4572E.toInt()
    ),
    WHOER(
        "Whoer", "Anonymity", "https://whoer.net/",
        "W", 0xFF3B82F6.toInt()
    ),
    CREEPJS(
        "CreepJS", "Deep probe", "https://abrahamjuliot.github.io/creepjs/",
        "C", 0xFF64748B.toInt()
    ),
    SANNYSOFT(
        "Sannysoft", "Headless check", "https://bot.sannysoft.com/",
        "S", 0xFF0EA5E9.toInt()
    ),
    DEVICEINFO(
        "DeviceInfo", "Hardware", "https://www.deviceinfo.me/",
        "D", 0xFF475569.toInt()
    );

    companion object {
        /** The four services the app treats as first-class, in test order. */
        val primary: List<SiteBrand> = listOf(BROWSERSCAN, IPHEY, PIXELSCAN, IPFIGHTER)

        /** Everything the lab can open. */
        val all: List<SiteBrand> = entries

        fun ofUrl(url: String?): SiteBrand? =
            entries.firstOrNull { it.url == url || (url != null && url.contains(hostOf(it.url))) }

        private fun hostOf(url: String): String =
            url.substringAfter("://").substringBefore("/")
    }

    /**
     * Paint the brand plate.
     *
     * Draws the initial as a wordmark on a solid plate of the service's colour: two characters
     * at most, so the tile stays legible at 32dp and never turns into a squashed logo.
     */
    fun bindPlate(context: Context, plate: TextView, mark: ImageView) {
        markRes?.let {
            plate.visibility = android.view.View.GONE
            mark.visibility = android.view.View.VISIBLE
            mark.setImageResource(it)
            return
        }
        mark.visibility = android.view.View.GONE
        plate.visibility = android.view.View.VISIBLE
        plate.text = initial
        plate.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * context.resources.displayMetrics.density
            setColor(plateColor)
        }
    }

    /** The tile's secondary line: what the service measures, in three words or fewer. */
    fun subtitle(): String = purpose
}
