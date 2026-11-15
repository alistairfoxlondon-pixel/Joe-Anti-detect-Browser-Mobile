package com.joebrowser.app.proxy

import com.joebrowser.app.data.ProxyConfig

/**
 * Parses the proxy line formats people actually paste.
 *
 * Supported on every line (separated by newlines, commas or semicolons):
 *
 * ```text
 * host:port
 * host:port:user:pass
 * user:pass:host:port
 * user:pass@host:port
 * scheme://host:port
 * scheme://user:pass@host:port
 * socks5://user:pass@host:port
 * [2001:db8::1]:8080
 * [2001:db8::1]:8080:user:pass
 * ```
 *
 * The parser is intentionally strict about ports (`1..65535`) and hosts, because a silently
 * accepted malformed line becomes an unreachable proxy later and looks like a bug.
 */
object ProxyParser {

    private val schemePattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.+)$")
    private val ipv4Pattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val hostPattern = Regex("^[A-Za-z0-9._-]+$")

    data class ParseResult(val proxies: List<ProxyConfig>, val rejected: List<String>) {
        val accepted: Int get() = proxies.size
    }

    fun parseAll(text: String, defaultType: String = "http"): ParseResult {
        val good = mutableListOf<ProxyConfig>()
        val bad = mutableListOf<String>()
        text.split(Regex("[\\r\\n,;]+")).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parsed = parseLine(line, defaultType)
            if (parsed != null) {
                // De-duplicate identical endpoints inside one paste.
                if (good.none { it.host == parsed.host && it.port == parsed.port && it.type == parsed.type }) {
                    good.add(parsed)
                }
            } else {
                bad.add(line)
            }
        }
        return ParseResult(good, bad)
    }

    fun parseLine(line: String, defaultType: String = "http"): ProxyConfig? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        var type = ProxyConfig.normalizeType(defaultType)
        var rest = trimmed

        schemePattern.matchEntire(rest)?.let { match ->
            type = ProxyConfig.normalizeType(match.groupValues[1])
            rest = match.groupValues[2].trim()
        }
        if (rest.isEmpty()) return null

        // userinfo is taken from the LAST '@' so passwords may contain '@'
        var user = ""
        var password = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val userInfo = rest.substring(0, at)
            rest = rest.substring(at + 1)
            val separator = userInfo.indexOf(':')
            if (separator >= 0) {
                user = userInfo.substring(0, separator)
                password = userInfo.substring(separator + 1)
            } else {
                user = userInfo
            }
        }

        if (rest.startsWith("[")) {
            val end = rest.indexOf(']')
            if (end <= 0) return null
            val host = rest.substring(1, end)
            val after = rest.substring(end + 1)
            val parts = after.removePrefix(":").split(':').filter { it.isNotEmpty() }
            val port = parts.firstOrNull()?.toIntOrNull() ?: return null
            if (!isValidPort(port) || !isValidHost(host)) return null
            if (parts.size >= 3 && user.isEmpty()) {
                user = parts[1]
                password = parts.drop(2).joinToString(":")
            }
            return finish(type, host, port, user, password)
        }

        val parts = rest.split(':')
        if (parts.isEmpty()) return null

        val host: String
        val port: Int
        when {
            parts.size == 1 -> {
                // Bare host: fall back to the scheme's default port.
                host = parts[0]
                port = defaultPort(type)
            }
            parts.size == 2 -> {
                host = parts[0]
                port = parts[1].toIntOrNull() ?: return null
            }
            parts.size == 3 -> {
                // host:port:user is ambiguous — reject instead of guessing.
                return null
            }
            else -> {
                // Either host:port:user:pass or user:pass:host:port. A numeric second
                // field means the line starts with the host.
                val secondIsPort = parts[1].toIntOrNull()?.let { isValidPort(it) } == true
                val lastIsPort = parts.last().toIntOrNull()?.let { isValidPort(it) } == true
                when {
                    secondIsPort -> {
                        host = parts[0]
                        port = parts[1].toInt()
                        if (user.isEmpty()) {
                            user = parts[2]
                            password = parts.drop(3).joinToString(":")
                        }
                    }
                    lastIsPort -> {
                        host = parts[parts.size - 2]
                        port = parts.last().toInt()
                        if (user.isEmpty()) {
                            user = parts[0]
                            password = parts.subList(1, parts.size - 2).joinToString(":")
                        }
                    }
                    else -> return null
                }
            }
        }
        if (!isValidPort(port) || !isValidHost(host)) return null
        return finish(type, host, port, user, password)
    }

    private fun finish(type: String, host: String, port: Int, user: String, password: String): ProxyConfig =
        ProxyConfig(type = type, host = host, port = port, user = user, password = password)

    fun isValidPort(port: Int): Boolean = port in 1..65535

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253) return false
        if (ipv4Pattern.matches(host)) return true
        if (host.contains(':')) return host.length <= 64   // IPv6 literal
        return hostPattern.matches(host) && !host.startsWith(".") && !host.endsWith(".")
    }

    fun defaultPort(type: String): Int = when (ProxyConfig.normalizeType(type)) {
        "socks5" -> 1080
        "https" -> 443
        else -> 8080
    }

    /** Human summary of a paste, used by the add-proxy sheet. */
    fun describe(result: ParseResult): String = when {
        result.accepted == 0 -> "No proxy recognized in that text"
        result.rejected.isEmpty() -> "${result.accepted} proxy imported"
        else -> "${result.accepted} imported · ${result.rejected.size} line(s) skipped"
    }
}
