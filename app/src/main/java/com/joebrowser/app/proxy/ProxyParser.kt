package com.joebrowser.app.proxy

import com.joebrowser.app.data.ProxyConfig

/**
 * Forgiving proxy-list parser. One entry per line (commas and semicolons also work).
 * Recognized shapes (with and without scheme):
 *   scheme://user:pass@host:port     http / https / socks5 / socks
 *   user:pass@host:port
 *   host:port
 *   host:port:user:pass
 *   user:pass:host:port              (also tolerates passwords containing ':')
 *   [v6::addr]:port[:user:pass]
 */
object ProxyParser {

    private val schemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.+)$")
    private val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val hostRegex = Regex("^[A-Za-z0-9._-]+$")

    data class ParseResult(val proxies: List<ProxyConfig>, val rejected: List<String>)

    fun parseAll(text: String, defaultType: String = "http"): ParseResult {
        val good = mutableListOf<ProxyConfig>()
        val bad = mutableListOf<String>()
        text.split(Regex("[\\r\\n,;]+")).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val p = parseLine(line, defaultType)
            if (p != null) good.add(p) else bad.add(line)
        }
        return ParseResult(good, bad)
    }

    fun parseLine(line: String, defaultType: String = "http"): ProxyConfig? {
        var rest = line.trim()
        var type = defaultType

        schemeRegex.matchEntire(rest)?.let { m ->
            type = normalizeScheme(m.groupValues[1])
            rest = m.groupValues[2].trim()
        }

        // Split userinfo from authority at the LAST '@' (passwords may not contain '@')
        var user = ""
        var pass = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val userInfo = rest.substring(0, at)
            rest = rest.substring(at + 1)
            val sep = userInfo.indexOf(':')
            if (sep >= 0) {
                user = userInfo.substring(0, sep)
                pass = userInfo.substring(sep + 1)
            } else {
                user = userInfo
            }
        }

        val hp = parseHostPort(rest, type) ?: return null
        var host = hp.first
        var port = hp.second

        if (user.isEmpty() && pass.isEmpty() && host.isEmpty()) return null

        // Bare multi-part forms: host:port:user:pass  /  user:pass:host:port
        if (at < 0) {
            val parts = rest.split(':')
            when {
                parts.size == 2 && isValidPort(parts[1]) -> {
                    host = parts[0]; port = parts[1].toInt()
                }
                parts.size >= 4 && isValidPort(parts[1]) -> {
                    host = parts[0]; port = parts[1].toInt()
                    user = parts[2]; pass = parts.subList(3, parts.size).joinToString(":")
                }
                parts.size >= 4 && isValidPort(parts[parts.size - 1]) -> {
                    user = parts[0]; pass = parts.subList(1, parts.size - 2).joinToString(":")
                    host = parts[parts.size - 2]; port = parts[parts.size - 1].toInt()
                }
                parts.size == 3 && isValidPort(parts[2]) -> {
                    // user:pass:host is missing a port — reject (ambiguous)
                    return null
                }
                else -> {
                    // host only (scheme default port)
                    if (host.isNotEmpty() && port > 0) {
                        // parsed above from bracket/host:port path
                    } else return null
                }
            }
        }

        if (!isValidHost(host) || port <= 0 || port > 65535) return null
        return ProxyConfig(type = type, host = host, port = port, user = user, password = pass)
    }

    private fun parseHostPort(text: String, type: String): Pair<String, Int>? {
        val t = text.trim()
        if (t.startsWith("[")) { // [v6]:port
            val end = t.indexOf(']')
            if (end > 0) {
                val host = t.substring(1, end)
                val after = t.substring(end + 1).removePrefix(":")
                if (after.isNotEmpty() && isValidPort(after)) return host to after.toInt()
                return null
            }
            return null
        }
        // bracketed results always carry port; plain host:port handled by caller for multi-part
        val idx = t.lastIndexOf(':')
        if (idx > 0 && isValidPort(t.substring(idx + 1)) && t.count { it == ':' } == 1) {
            return t.substring(0, idx) to t.substring(idx + 1).toInt()
        }
        return t to defaultPort(type)
    }

    private fun normalizeScheme(s: String): String = when (s.lowercase()) {
        "socks", "socks5", "socks5h" -> "socks5"
        "socks4", "socks4a" -> "socks5" // closest supported
        "http", "https" -> s.lowercase()
        else -> "http"
    }

    private fun defaultPort(type: String): Int = when (type) {
        "socks5" -> 1080
        "https" -> 443
        else -> 8080
    }

    private fun isValidPort(s: String): Boolean = s.toIntOrNull()?.let { it in 1..65535 } ?: false

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253) return false
        return ipv4Regex.matches(host) || hostRegex.matches(host) || (host.contains(':') && host.length <= 64)
    }
}
