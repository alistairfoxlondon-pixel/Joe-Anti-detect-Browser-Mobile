package com.joebrowser.app.browser

import android.os.Handler
import android.os.Looper
import java.util.LinkedHashMap

data class TrackerEntry(val host: String, val count: Int, val tracker: Boolean)

/**
 * Native request counter and tracker detector.
 *
 * Every request the WebView makes passes through `shouldInterceptRequest`, which is where a
 * tracking SDK is visible even when the page's own JavaScript is obfuscated: the request has
 * to reach a known collection host eventually. The monitor keeps a per-session histogram,
 * flags known hosts, and throttles UI updates so a page with 800 subresources does not
 * trigger 800 layout passes.
 */
class TrackerMonitor(private val onChange: (total: Int, trackers: Int) -> Unit) {

    private val counts = LinkedHashMap<String, Int>()
    private var total = 0
    private var trackers = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotify = 0L
    private var pendingNotify = false

    fun record(url: String) {
        val host = hostOf(url) ?: return
        synchronized(this) {
            total++
            if (isTracker(host)) trackers++
            counts[host] = (counts[host] ?: 0) + 1
        }
        scheduleNotify()
    }

    fun snapshot(): List<TrackerEntry> = synchronized(this) {
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { isTracker(it.key) }.thenByDescending { it.value })
            .take(150)
            .map { (host, count) -> TrackerEntry(host, count, isTracker(host)) }
    }

    fun totals(): Pair<Int, Int> = synchronized(this) { total to trackers }

    fun trackerCount(): Int = synchronized(this) { trackers }

    fun reset() {
        synchronized(this) {
            counts.clear()
            total = 0
            trackers = 0
        }
        scheduleNotify(force = true)
    }

    /**
     * Plain-text report for the clipboard.
     *
     * Says so when the host list is truncated (the sheet shows at most 150 hosts), because a
     * report that quietly drops rows reads as the complete picture.
     */
    fun report(profileName: String): String = buildString {
        val (requests, flagged) = totals()
        val rows = snapshot()
        val hostTotal = synchronized(this@TrackerMonitor) { counts.size }
        appendLine("Joe Browser HTTP Tracker")
        appendLine("Profile: $profileName")
        appendLine("Requests: $requests · Trackers: $flagged · Hosts: $hostTotal")
        appendLine()
        rows.forEach {
            append(if (it.tracker) "[tracker] " else "[ok]      ")
            append(it.host)
            append("  ×")
            appendLine(it.count)
        }
        if (hostTotal > rows.size) {
            appendLine()
            appendLine("Showing the top " + rows.size + " of " + hostTotal + " hosts.")
        }
    }

    private fun scheduleNotify(force: Boolean = false) {
        val now = System.currentTimeMillis()
        // force (reset / user action) must never be dropped or delayed behind a pending post.
        if (force) {
            lastNotify = now
            dispatch()
            return
        }
        if (now - lastNotify < NOTIFY_THROTTLE_MS) {
            if (pendingNotify) return
            pendingNotify = true
            mainHandler.postDelayed({
                pendingNotify = false
                lastNotify = System.currentTimeMillis()
                dispatch()
            }, NOTIFY_THROTTLE_MS)
            return
        }
        lastNotify = now
        dispatch()
    }

    private fun dispatch() {
        val current = synchronized(this) { total to trackers }
        mainHandler.post {
            runCatching { onChange(current.first, current.second) }
        }
    }

    private fun hostOf(url: String): String? {
        return try {
            val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
            if (afterScheme.isEmpty()) return null
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            authority.substringAfterLast('@').substringBefore(':').lowercase().ifBlank { null }
        } catch (t: Throwable) {
            null
        }
    }

    fun isTracker(host: String): Boolean {
        val h = host.lowercase().trim('.')
        if (h.isEmpty()) return false
        return KNOWN_TRACKER_HOSTS.any { known ->
            // Exact host or a real subdomain. A bare endsWith() would also match
            // 'notdoubleclick.net', which is not the tracker it looks like.
            h == known || h.endsWith("." + known)
        }
    }

    companion object {
        private const val NOTIFY_THROTTLE_MS = 400L

        /** Known collection / analytics hosts. Suffix-matched, so subdomains are covered. */
        private val KNOWN_TRACKER_HOSTS = listOf(
            // Hosts only. Entries previously included path fragments ('facebook.com/tr',
            // 'youtube.com/api/stats/ads') which never match a host and silently did nothing.
            "doubleclick.net", "google-analytics.com", "googletagmanager.com", "googlesyndication.com",
            "googleadservices.com", "google-analytics.cn", "connect.facebook.net",
            "analytics.tiktok.com", "bat.bing.com", "clarity.ms", "scorecardresearch.com", "quantserve.com",
            "hotjar.com", "hotjar.io", "mixpanel.com", "segment.com", "segment.io", "criteo.com", "criteo.net",
            "adsrvr.org", "taboola.com", "outbrain.com", "fullstory.com", "amplitude.com", "branch.io",
            "appsflyer.com", "kochava.com", "adjust.com", "ads-twitter.com", "analytics.twitter.com",
            "static.ads-twitter.com", "snap.licdn.com", "px.ads.linkedin.com", "ads.linkedin.com",
            "analytics.yahoo.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "casalemedia.com",
            "moatads.com", "serving-sys.com", "mathtag.com", "bluekai.com", "exelator.com", "demdex.net",
            "omtrdc.net", "2o7.net", "everesttech.net", "krxd.net", "tidaltv.com", "innovid.com",
            "sentry.io", "bugsnag.com", "newrelic.com", "nr-data.net", "optimizely.com", "vwo.com",
            "heapanalytics.com", "statcounter.com",
            // Analytics subdomains only: 'yandex.ru' itself is the search engine, not an
            // analytics endpoint, and flagging it would mislabel normal browsing.
            "mc.yandex.ru", "top-fwz1.mail.ru",
            "ads.mail.ru", "ad.mail.ru", "counter.yadro.ru", "histats.com", "liveinternet.ru"
        )
    }
}
