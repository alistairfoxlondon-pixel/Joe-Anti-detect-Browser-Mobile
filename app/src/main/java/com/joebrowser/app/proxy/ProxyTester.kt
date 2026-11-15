package com.joebrowser.app.proxy

import android.content.Context
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.ProxyConfig
import com.joebrowser.app.data.SavedProxy
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.HttpURLConnection
import java.util.zip.GZIPInputStream

/** Outcome of a single proxy check. */
data class ProxyTestResult(
    val ok: Boolean,
    val ip: String = "",
    val country: String = "",
    val countryCode: String = "",
    val city: String = "",
    val org: String = "",
    val latencyMs: Int = -1,
    val error: String = ""
)

/**
 * Verifies a proxy by tunnelling an HTTPS echo request through it.
 * Implementation: spin up a throwaway [LocalProxyRelay] on 127.0.0.1 and point
 * HttpURLConnection at it — the exact same path the WebView uses, SOCKS5 auth included,
 * with zero credentials leaked into process-wide Authenticators. Call on a background thread.
 */
object ProxyTester {

    private const val IP_ECHO = "https://api.ipify.org?format=json"
    private const val IP_GEO = "http://ip-api.com/json/?fields=status,country,countryCode,city,org,query"

    /** Test [config] and return the result; does not persist anything. */
    fun test(config: ProxyConfig, timeoutMs: Int = 12_000): ProxyTestResult {
        val started = System.currentTimeMillis()
        var relay: LocalProxyRelay? = null
        return try {
            relay = LocalProxyRelay.start(config)
            val localProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relay.port))
            val ipBody = httpGet(IP_ECHO, localProxy, timeoutMs) ?: return fail("Echo endpoint did not respond", started)
            val ip = runCatching { JSONObject(ipBody).optString("ip") }.getOrDefault("")
            if (ip.isBlank()) return fail("Exit IP could not be parsed", started)
            val latency = (System.currentTimeMillis() - started).toInt()
            var country = ""; var countryCode = ""; var city = ""; var org = ""
            try {
                val geoBody = httpGet(IP_GEO, localProxy, timeoutMs)
                if (geoBody != null) {
                    val j = JSONObject(geoBody)
                    if (j.optString("status") == "success") {
                        country = j.optString("country", "")
                        countryCode = j.optString("countryCode", "")
                        city = j.optString("city", "")
                        org = j.optString("org", "")
                    }
                }
            } catch (_: Exception) { /* geo lookup is best effort */ }
            ProxyTestResult(true, ip, country, countryCode, city, org, latency)
        } catch (e: Exception) {
            fail(e.message ?: "Unreachable", started)
        } finally {
            try { relay?.close() } catch (_: Exception) {}
        }
    }

    /** Test the saved proxy [proxyId] and persist the outcome into its config fields. */
    fun testAndPersist(context: Context, proxyId: String, timeoutMs: Int = 12_000): ProxyTestResult? {
        val db = Database.get(context)
        val item = db.getProxy(proxyId) ?: return null
        val result = test(item.config, timeoutMs)
        val now = System.currentTimeMillis()
        val updated = ProxyConfig.fromJson(item.config.toJson()).apply {
            lastCheckedAt = now
            lastIp = result.ip
            lastCountry = result.country
            lastCountryCode = result.countryCode
            lastCity = result.city
            latencyMs = result.latencyMs
        }
        db.saveProxy(SavedProxy(id = item.id, name = item.name, note = item.note, config = updated))
        return result
    }

    private fun fail(message: String, started: Long): ProxyTestResult =
        ProxyTestResult(ok = false, latencyMs = (System.currentTimeMillis() - started).toInt(), error = message)

    private fun httpGet(urlString: String, proxy: Proxy, timeoutMs: Int): String? {
        val conn = (URL(urlString).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "JoeBrowser/2.1 (compatible; proxy-check)")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val stream = if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
