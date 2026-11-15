package com.joebrowser.app.proxy

import android.content.Context
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.data.ProxyConfig
import com.joebrowser.app.data.SavedProxy
import com.joebrowser.app.fingerprint.FingerprintFactory
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.zip.GZIPInputStream

/** Outcome of one proxy health check. */
data class ProxyTestResult(
    val ok: Boolean,
    val ip: String = "",
    val country: String = "",
    val countryCode: String = "",
    val city: String = "",
    val timezone: String = "",
    val org: String = "",
    val latencyMs: Int = -1,
    val error: String = ""
)

/**
 * Verifies a proxy by tunnelling real HTTPS traffic through it.
 *
 * A throwaway [LocalProxyRelay] is started on loopback and `HttpURLConnection` is pointed at
 * it, which means the check exercises exactly the code path the WebView uses — including
 * SOCKS5 username/password negotiation — rather than a simplified "can I open a socket"
 * probe that would pass for a proxy that cannot actually carry traffic.
 *
 * Always call from a background thread; [testAndPersist] is safe to call from anywhere
 * because it only touches the database synchronously on that same background thread.
 */
object ProxyTester {

    private const val IP_ECHO = "https://api.ipify.org?format=json"
    private const val IP_GEO = "http://ip-api.com/json/?fields=status,country,countryCode,city,timezone,org,query"
    private const val DEFAULT_TIMEOUT = 12_000

    /** Checks the machine's own public IP (no proxy) — used by the "This device" card. */
    fun checkDirectDevice(timeoutMs: Int = DEFAULT_TIMEOUT): ProxyTestResult {
        val started = System.currentTimeMillis()
        return try {
            val body = httpGet(IP_ECHO, Proxy.NO_PROXY, timeoutMs)
                ?: return fail("IP echo did not answer", started)
            val ip = runCatching { JSONObject(body).optString("ip") }.getOrDefault("")
            if (ip.isBlank()) return fail("Exit IP could not be parsed", started)
            val latency = (System.currentTimeMillis() - started).toInt()
            val geo = geoFor(ip, timeoutMs)
            ProxyTestResult(
                ok = true, ip = ip, country = geo.first, countryCode = geo.second,
                city = geo.third, timezone = geo.fourth, latencyMs = latency
            )
        } catch (t: Throwable) {
            fail(t.message ?: "Unreachable", started)
        }
    }

    /** Checks [config]; does not persist anything. */
    fun test(config: ProxyConfig, timeoutMs: Int = DEFAULT_TIMEOUT): ProxyTestResult {
        if (!config.enabled) return ProxyTestResult(ok = false, error = "No proxy configured")
        val started = System.currentTimeMillis()
        var relay: LocalProxyRelay? = null
        return try {
            relay = LocalProxyRelay.start(config)
            val local = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relay.port))
            val body = httpGet(IP_ECHO, local, timeoutMs)
                ?: return fail("Proxy did not return an exit IP", started)
            val ip = runCatching { JSONObject(body).optString("ip") }.getOrDefault("")
            if (ip.isBlank()) return fail("Exit IP could not be parsed", started)
            val latency = (System.currentTimeMillis() - started).toInt()
            val geo = geoForVia(local, timeoutMs).takeIf { it.second.isNotBlank() }
                ?: geoFor(ip, timeoutMs)
            ProxyTestResult(
                ok = true, ip = ip, country = geo.first, countryCode = geo.second,
                city = geo.third, timezone = geo.fourth, latencyMs = latency
            )
        } catch (t: Throwable) {
            fail(t.message ?: "Unreachable", started)
        } finally {
            runCatching { relay?.close() }
        }
    }

    /**
     * Tests the saved proxy [proxyId] and writes the outcome back into its row.
     * Returns null when the proxy no longer exists. Runs synchronously — background only.
     */
    fun testAndPersist(context: Context, proxyId: String, timeoutMs: Int = DEFAULT_TIMEOUT): ProxyTestResult? {
        val database = Database.get(context)
        val item = database.getProxy(proxyId) ?: return null
        val result = test(item.config, timeoutMs)
        val updated = item.config.duplicate().apply {
            lastCheckedAt = System.currentTimeMillis()
            latencyMs = result.latencyMs
            lastError = if (result.ok) "" else result.error
            failStreak = if (result.ok) 0 else failStreak + 1
            if (result.ok) {
                if (lastIp.isNotBlank() && result.ip.isNotBlank() && lastIp != result.ip) rotations += 1
                lastIp = result.ip
                lastCountry = result.country
                lastCountryCode = result.countryCode
                lastCity = result.city
                lastTimezone = result.timezone
            }
        }
        database.saveProxy(SavedProxy(id = item.id, name = item.name, note = item.note, config = updated))
        return result
    }

    /**
     * Tests a profile's own proxy and persists the result on the profile, keeping the
     * locale glued to the exit country when auto-match is enabled.
     */
    fun testForProfile(context: Context, profileId: String, timeoutMs: Int = DEFAULT_TIMEOUT): ProxyTestResult? {
        val database = Database.get(context)
        val profile = database.getProfile(profileId) ?: return null
        if (!profile.proxy.enabled) return ProxyTestResult(ok = false, error = "No proxy configured")
        val result = test(profile.proxy, timeoutMs)
        profile.proxy = profile.proxy.duplicate().apply {
            lastCheckedAt = System.currentTimeMillis()
            latencyMs = result.latencyMs
            lastError = if (result.ok) "" else result.error
            failStreak = if (result.ok) 0 else failStreak + 1
            if (result.ok) {
                if (lastIp.isNotBlank() && result.ip.isNotBlank() && lastIp != result.ip) rotations += 1
                lastIp = result.ip
                lastCountry = result.country
                lastCountryCode = result.countryCode
                lastCity = result.city
                lastTimezone = result.timezone
            }
        }
        if (result.ok && profile.fp.autoMatchProxy && profile.tools.autoSyncLocale) {
            FingerprintFactory.applyProxyLocale(profile.fp, result.countryCode)
        }
        database.saveProfile(profile)
        return result
    }

    /**
     * Checks every saved proxy, in parallel across the shared background pool, and persists
     * the results. [onProgress] receives (completed, total) on the main thread; [onDone]
     * receives the split totals.
     */
    fun checkAll(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onDone: (checked: Int, online: Int, offline: Int) -> Unit = { _, _, _ -> }
    ) {
        val app = context.applicationContext
        Async.compute({ Database.get(app).getAllProxies() }) { proxies ->
            val list = proxies ?: emptyList()
            if (list.isEmpty()) {
                onDone(0, 0, 0)
                return@compute
            }
            val total = list.size
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val online = java.util.concurrent.atomic.AtomicInteger(0)
            val offline = java.util.concurrent.atomic.AtomicInteger(0)
            list.forEach { proxy ->
                Async.bg {
                    val result = runCatching { testAndPersist(app, proxy.id) }.getOrNull()
                    if (result?.ok == true) online.incrementAndGet() else offline.incrementAndGet()
                    val done = completed.incrementAndGet()
                    Async.onMain { onProgress(done, total) }
                    if (done == total) {
                        ProfileRepository.notifyExternalChange()
                        onDone(done, online.get(), offline.get())
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun geoFor(ip: String, timeoutMs: Int): Quadruple {
        return try {
            val body = httpGet("http://ip-api.com/json/$ip?fields=status,country,countryCode,city,timezone,org", Proxy.NO_PROXY, timeoutMs)
            if (body == null) Quadruple("", "", "", "")
            else {
                val json = JSONObject(body)
                if (json.optString("status") != "success") Quadruple("", "", "", "")
                else Quadruple(
                    json.optString("country", ""),
                    json.optString("countryCode", ""),
                    json.optString("city", ""),
                    json.optString("timezone", "")
                )
            }
        } catch (t: Throwable) {
            Quadruple("", "", "", "")
        }
    }

    private fun geoForVia(proxy: Proxy, timeoutMs: Int): Quadruple {
        return try {
            val body = httpGet(IP_GEO, proxy, timeoutMs) ?: return Quadruple("", "", "", "")
            val json = JSONObject(body)
            if (json.optString("status") != "success") Quadruple("", "", "", "")
            else Quadruple(
                json.optString("country", ""),
                json.optString("countryCode", ""),
                json.optString("city", ""),
                json.optString("timezone", "")
            )
        } catch (t: Throwable) {
            Quadruple("", "", "", "")
        }
    }

    private data class Quadruple(val first: String, val second: String, val third: String, val fourth: String)

    private fun fail(message: String, started: Long): ProxyTestResult =
        ProxyTestResult(ok = false, latencyMs = (System.currentTimeMillis() - started).toInt(), error = message)

    private fun httpGet(url: String, proxy: Proxy, timeoutMs: Int): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", "JoeBrowser/2 (proxy-health-check)")
            }
            if (connection.responseCode !in 200..299) return null
            val stream = if ("gzip".equals(connection.contentEncoding, true)) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
