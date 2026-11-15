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
    private const val MAX_BODY_CHARS = 64 * 1024

    /** Checks the machine's own public IP (no proxy) — used by the "This device" card. */
    fun checkDirectDevice(timeoutMs: Int = DEFAULT_TIMEOUT): ProxyTestResult {
        val started = System.currentTimeMillis()
        return try {
            val outcome = httpGet(IP_ECHO, Proxy.NO_PROXY, timeoutMs)
            val body = outcome.bodyOrNull()
                ?: return fail(outcome.errorOrBlank(), started)
            val ip = runCatching { JSONObject(body).optString("ip") }.getOrDefault("")
            if (ip.isBlank()) return fail("Upstream answered with an unexpected body (no exit IP)", started)
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
            val outcome = httpGet(IP_ECHO, local, timeoutMs)
            val body = outcome.bodyOrNull()
                ?: return fail(outcome.errorOrBlank(), started)
            val ip = runCatching { JSONObject(body).optString("ip") }.getOrDefault("")
            if (ip.isBlank()) return fail("Upstream answered with an unexpected body (no exit IP)", started)
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
            val body = httpGet(
                "http://ip-api.com/json/$ip?fields=status,country,countryCode,city,timezone,org",
                Proxy.NO_PROXY,
                timeoutMs
            ).bodyOrNull()
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
            val body = httpGet(IP_GEO, proxy, timeoutMs).bodyOrNull()
                ?: return Quadruple("", "", "", "")
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

    /**
     * One HTTP GET with an explicit proxy, returning either a body or a *specific* reason.
     *
     * The previous version returned `null` for every failure, so a proxy with wrong credentials,
     * a proxy that does not answer, and a DNS problem all surfaced as "did not return an exit
     * IP" — the one message that tells the user nothing. Failures are now classified so the badge
     * and the monitor can say what actually happened.
     */
    private fun httpGet(url: String, proxy: Proxy, timeoutMs: Int): HttpOutcome {
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
            val status = connection.responseCode
            if (status !in 200..299) {
                return when (status) {
                    407 -> HttpOutcome.problem("Proxy rejected the credentials (HTTP 407)")
                    403 -> HttpOutcome.problem("Proxy refused the request (HTTP 403)")
                    429 -> HttpOutcome.problem("Proxy rate-limited the check (HTTP 429)")
                    in 500..599 -> HttpOutcome.problem("Upstream proxy failed (HTTP $status)")
                    else -> HttpOutcome.problem("Unexpected response from upstream (HTTP $status)")
                }
            }
            val stream = if ("gzip".equals(connection.contentEncoding, true)) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            // readText with a cap: the echo services return a few dozen bytes, so anything large
            // is a captive portal or an error page, not our answer.
            val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val buffer = CharArray(4096)
                val out = StringBuilder()
                while (out.length < MAX_BODY_CHARS) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
            HttpOutcome.ok(body)
        } catch (t: Throwable) {
            HttpOutcome.problem(describe(t, timeoutMs))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Turns a transport exception into something a user can act on. */
    private fun describe(t: Throwable, timeoutMs: Int): String = when (t) {
        is java.net.SocketTimeoutException ->
            "Upstream did not answer within ${timeoutMs / 1000} s"
        is java.net.UnknownHostException ->
            "Cannot resolve the proxy address"
        is java.net.ConnectException ->
            "Proxy refused the connection"
        is javax.net.ssl.SSLException ->
            "TLS failed through the proxy"
        is java.net.NoRouteToHostException ->
            "No route to the proxy"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "Unreachable"
    }

    private class HttpOutcome(private val body: String?, private val error: String) {
        fun bodyOrNull(): String? = body
        fun errorOrBlank(): String = error

        companion object {
            fun ok(body: String): HttpOutcome = HttpOutcome(body, "")
            fun problem(reason: String): HttpOutcome = HttpOutcome(null, reason)
        }
    }
}
