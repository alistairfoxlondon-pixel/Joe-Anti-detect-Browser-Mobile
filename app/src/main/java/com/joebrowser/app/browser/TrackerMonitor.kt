package com.joebrowser.app.browser

import android.os.Handler
import android.os.Looper
import java.util.LinkedHashMap

data class TrackerEntry(val host: String, val count: Int, val tracker: Boolean)

/**
 * Native request counter + tracker detector (the built-in successor of the desktop's
 * "HTTP Tracker Plus"). Feeds the badge in the browser toolbar and the bottom sheet report.
 */
class TrackerMonitor(private val onChange: (total: Int, trackers: Int) -> Unit) {

    private val counts = LinkedHashMap<String, Int>()
    private var total = 0

    @Volatile
    private var trackers = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    fun record(url: String) {
        val host = hostOf(url) ?: return
        synchronized(this) {
            total++
            val flagged = isTracker(host)
            if (flagged) trackers++
            counts[host] = (counts[host] ?: 0) + 1
        }
        notifyChange()
    }

    fun snapshot(): List<TrackerEntry> = synchronized(this) {
        counts.entries
            .sortedByDescending { it.value }
            .take(120)
            .map { (host, count) -> TrackerEntry(host, count, isTracker(host)) }
    }

    fun totals(): Pair<Int, Int> = synchronized(this) { total to trackers }

    fun clear() {
        synchronized(this) { counts.clear(); total = 0; trackers = 0 }
        notifyChange()
    }

    private fun notifyChange() {
        val t: Int; val k: Int
        synchronized(this) { t = total; k = trackers }
        mainHandler.post { onChange(t, k) }
    }

    private fun hostOf(url: String): String? = try {
        val after = url.substringAfter("://", missingDelimiterValue = "")
        val host = after.substringBefore('/').substringBefore('?').substringBefore('#')
        host.substringAfterLast('@').substringBefore(':').lowercase().ifBlank { null }
    } catch (t: Throwable) {
        null
    }

    private fun isTracker(host: String): Boolean {
        val h = host.lowercase()
        return KNOWN_TRACKERS.any { h == it || h.endsWith(".$it") || h.contains(it) }
    }

    companion object {
        private val KNOWN_TRACKERS = listOf(
            "doubleclick.net", "google-analytics.com", "googletagmanager.com", "googlesyndication.com",
            "googleadservices.com", "connect.facebook.net", "facebook.net/tr", "analytics.tiktok.com",
            "bat.bing.com", "scorecardresearch.com", "quantserve.com", "hotjar.com", "mixpanel.com",
            "segment.com", "segment.io", "criteo.com", "criteo.net", "adsrvr.org", "taboola.com",
            "outbrain.com", "clarity.ms", "fullstory.com", "amplitude.com", "branch.io",
            "appsflyer.com", "kochava.com", "adjust.com", "ads-twitter.com", "analytics.twitter.com",
            "snap.licdn.com", "px.ads.linkedin.com", "static.ads-twitter.com", "stats.g.doubleclick.net",
            "sb.scorecardresearch.com", "cm.g.doubleclick.net", "pagead2.googlesyndication.com"
        )
    }
}
