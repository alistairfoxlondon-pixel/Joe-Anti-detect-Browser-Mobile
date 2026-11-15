package com.joebrowser.app.browser

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * AdsPower / GoLogin-compatible Cookie Manager.
 *
 * Parses and exports cookies in both:
 *  1. **JSON Array** format (Cookie-Editor / EditThisCookie / AdsPower / GoLogin / Multilogin):
 *     `[{"domain":".example.com","name":"sid","value":"abc","path":"/","secure":true,"httpOnly":true,"expirationDate":1893456000}]`
 *  2. **Netscape HTTP Cookie File** format (`# Netscape HTTP Cookie File`, tab-separated 7 columns).
 *
 * Applies imported cookies directly into the isolated per-profile `CookieManager` (never leaking
 * into the global jar when `MULTI_PROFILE` is active) and flushes immediately.
 */
object CookieExporter {

    data class CookieEntry(
        val domain: String,
        val name: String,
        val value: String,
        val path: String = "/",
        val secure: Boolean = false,
        val httpOnly: Boolean = false,
        val session: Boolean = true,
        val expirationDate: Long = 0L,
        val sameSite: String = ""
    ) {
        fun targetUrl(): String {
            val cleanHost = domain.trim().trimStart('.')
            val scheme = if (secure) "https" else "http"
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            return "$scheme://$cleanHost$cleanPath"
        }

        fun toSetCookieHeader(): String = buildString {
            append(name).append('=').append(value)
            if (domain.startsWith(".")) {
                append("; Domain=").append(domain.trimStart('.'))
            }
            append("; Path=").append(path.ifBlank { "/" })
            if (!session && expirationDate > 0L) {
                val dateStr = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("GMT")
                }.format(java.util.Date(expirationDate * 1000L))
                append("; Expires=").append(dateStr)
            }
            if (secure) append("; Secure")
            if (httpOnly) append("; HttpOnly")
            when (sameSite.lowercase()) {
                "lax" -> append("; SameSite=Lax")
                "strict" -> append("; SameSite=Strict")
                "none", "no_restriction" -> {
                    append("; SameSite=None")
                    if (!secure) append("; Secure")
                }
            }
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("domain", domain)
            put("name", name)
            put("value", value)
            put("path", path)
            put("secure", secure)
            put("httpOnly", httpOnly)
            put("session", session)
            if (!session && expirationDate > 0L) put("expirationDate", expirationDate)
            if (sameSite.isNotBlank()) put("sameSite", sameSite)
        }
    }

    /** Parses either JSON Cookie-Editor/AdsPower/GoLogin format or Netscape tab-delimited text. */
    fun parse(raw: String): List<CookieEntry> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("[") || text.startsWith("{")) {
            val fromJson = parseJson(text)
            if (fromJson.isNotEmpty()) return fromJson
        }
        return parseNetscapeOrHeader(text)
    }

    private fun parseJson(text: String): List<CookieEntry> {
        val out = mutableListOf<CookieEntry>()
        val array: JSONArray = when {
            text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            text.startsWith("{") -> {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
                obj.optJSONArray("cookies") ?: JSONArray().put(obj)
            }
            else -> return emptyList()
        }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name", "").trim()
            val domain = item.optString("domain", item.optString("host", "")).trim()
            if (name.isEmpty() || domain.isEmpty()) continue
            val value = item.optString("value", "")
            val path = item.optString("path", "/").ifBlank { "/" }
            val secure = item.optBoolean("secure", domain.startsWith(".") || true)
            val httpOnly = item.optBoolean("httpOnly", false)
            val expRaw = item.optDouble("expirationDate", item.optDouble("expires", 0.0))
            val expSec = if (expRaw > 100_000_000_000.0) (expRaw / 1000.0).toLong() else expRaw.toLong()
            val session = item.optBoolean("session", expSec <= 0L)
            val sameSite = item.optString("sameSite", "")
            out.add(
                CookieEntry(
                    domain = domain,
                    name = name,
                    value = value,
                    path = path,
                    secure = secure,
                    httpOnly = httpOnly,
                    session = session,
                    expirationDate = expSec,
                    sameSite = sameSite
                )
            )
        }
        return out
    }

    private fun parseNetscapeOrHeader(text: String): List<CookieEntry> {
        val out = mutableListOf<CookieEntry>()
        text.lineSequence().forEach { rawLine ->
            var line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            var httpOnly = false
            if (line.startsWith("#HttpOnly_", ignoreCase = true)) {
                httpOnly = true
                line = line.substring("#HttpOnly_".length)
            } else if (line.startsWith("#")) {
                return@forEach
            }
            val cols = line.split('\t')
            if (cols.size >= 7) {
                val domain = cols[0].trim()
                val path = cols[2].trim().ifBlank { "/" }
                val secure = cols[3].trim().equals("TRUE", ignoreCase = true)
                val expiry = cols[4].trim().toLongOrNull() ?: 0L
                val name = cols[5].trim()
                val value = cols.subList(6, cols.size).joinToString("\t")
                if (domain.isNotEmpty() && name.isNotEmpty()) {
                    out.add(
                        CookieEntry(
                            domain = domain,
                            name = name,
                            value = value,
                            path = path,
                            secure = secure,
                            httpOnly = httpOnly,
                            session = expiry <= 0L,
                            expirationDate = expiry
                        )
                    )
                }
            }
        }
        return out
    }

    /** Writes parsed cookies into [cookieManager] and flushes. Returns count applied. */
    fun importInto(cookieManager: CookieManager, entries: List<CookieEntry>): Int {
        var count = 0
        cookieManager.setAcceptCookie(true)
        entries.forEach { entry ->
            runCatching {
                cookieManager.setCookie(entry.targetUrl(), entry.toSetCookieHeader())
                count++
            }
        }
        runCatching { cookieManager.flush() }
        return count
    }

    /**
     * Reads cookies known to [cookieManager] across [candidateUrls] and formats them as an
     * AdsPower/GoLogin-compatible JSON array.
     */
    fun exportJsonForUrls(cookieManager: CookieManager, candidateUrls: Collection<String>): Pair<String, Int> {
        val seen = linkedMapOf<String, CookieEntry>()
        candidateUrls.forEach { rawUrl ->
            val host = runCatching { URI(rawUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            val secure = rawUrl.startsWith("https://", ignoreCase = true)
            val header = runCatching { cookieManager.getCookie(rawUrl) }.getOrNull().orEmpty()
            if (header.isBlank()) return@forEach
            header.split(';').forEach { pair ->
                val trimmed = pair.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val name = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim()
                    val key = "$host|$name"
                    if (name.isNotEmpty() && !seen.containsKey(key)) {
                        seen[key] = CookieEntry(
                            domain = host,
                            name = name,
                            value = value,
                            path = "/",
                            secure = secure,
                            session = true
                        )
                    }
                }
            }
        }
        val arr = JSONArray()
        seen.values.forEach { arr.put(it.toJson()) }
        return arr.toString(2) to seen.size
    }
}
