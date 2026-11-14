package com.joebrowser.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Joe Browser data model. JSON is the storage format; org.json is built into Android. */

const val BROWSERSCAN_URL = "https://www.browserscan.net/"
const val DEFAULT_START_URL = BROWSERSCAN_URL

data class FingerprintConfig(
    var seed: Int = 1,
    var os: String = "Android",
    var deviceType: String = "mobile",
    var timezone: String = "Asia/Dhaka",
    var language: String = "en-US",
    var acceptLanguage: String = "en-US,en;q=0.9",
    var screenW: Int = 393,
    var screenH: Int = 851,
    var availW: Int = 393,
    var availH: Int = 851 - 48,
    var colorDepth: Int = 24,
    var pixelRatio: Double = 2.75,
    var webglVendor: String = "Google Inc. (Qualcomm)",
    var webglRenderer: String = "ANGLE (Qualcomm, Adreno (TM) 730, OpenGL ES 3.2)",
    var cores: Int = 8,
    var memory: Int = 8,
    var touchPoints: Int = 5,
    var canvasNoise: Boolean = true,
    var audioNoise: Boolean = true,
    var hardwareNoise: Boolean = true,
    var doNotTrack: Boolean = false,
    var spoofBattery: Boolean = true,
    var webrtcMode: String = "proxy",   // off | proxy | public
    var geoMode: String = "proxy",      // proxy | system | custom | block
    var geoLat: String = "",
    var geoLng: String = "",
    var autoMatchProxy: Boolean = true,
    var userAgentOverride: String = "",
    var browserVersion: String = "131.0.6778.85",
    var androidVersion: String = "auto",
    var iosVersion: String = "auto"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("seed", seed); put("os", os); put("deviceType", deviceType)
        put("timezone", timezone); put("language", language); put("acceptLanguage", acceptLanguage)
        put("screenW", screenW); put("screenH", screenH); put("availW", availW); put("availH", availH)
        put("colorDepth", colorDepth); put("pixelRatio", pixelRatio)
        put("webglVendor", webglVendor); put("webglRenderer", webglRenderer)
        put("cores", cores); put("memory", memory); put("touchPoints", touchPoints)
        put("canvasNoise", canvasNoise); put("audioNoise", audioNoise); put("hardwareNoise", hardwareNoise)
        put("doNotTrack", doNotTrack); put("spoofBattery", spoofBattery)
        put("webrtcMode", webrtcMode); put("geoMode", geoMode)
        put("geoLat", geoLat); put("geoLng", geoLng)
        put("autoMatchProxy", autoMatchProxy); put("userAgentOverride", userAgentOverride)
        put("browserVersion", browserVersion)
        put("androidVersion", androidVersion); put("iosVersion", iosVersion)
    }

    companion object {
        fun fromJson(o: JSONObject?): FingerprintConfig {
            val d = FingerprintConfig()
            if (o == null) return d
            d.seed = o.optInt("seed", d.seed)
            d.os = o.optString("os", d.os)
            d.deviceType = o.optString("deviceType", d.deviceType)
            d.timezone = o.optString("timezone", d.timezone)
            d.language = o.optString("language", d.language)
            d.acceptLanguage = o.optString("acceptLanguage", d.acceptLanguage)
            d.screenW = o.optInt("screenW", d.screenW)
            d.screenH = o.optInt("screenH", d.screenH)
            d.availW = o.optInt("availW", d.availW)
            d.availH = o.optInt("availH", d.availH)
            d.colorDepth = o.optInt("colorDepth", d.colorDepth)
            d.pixelRatio = o.optDouble("pixelRatio", d.pixelRatio)
            d.webglVendor = o.optString("webglVendor", d.webglVendor)
            d.webglRenderer = o.optString("webglRenderer", d.webglRenderer)
            d.cores = o.optInt("cores", d.cores)
            d.memory = o.optInt("memory", d.memory)
            d.touchPoints = o.optInt("touchPoints", d.touchPoints)
            d.canvasNoise = o.optBoolean("canvasNoise", d.canvasNoise)
            d.audioNoise = o.optBoolean("audioNoise", d.audioNoise)
            d.hardwareNoise = o.optBoolean("hardwareNoise", d.hardwareNoise)
            d.doNotTrack = o.optBoolean("doNotTrack", d.doNotTrack)
            d.spoofBattery = o.optBoolean("spoofBattery", d.spoofBattery)
            d.webrtcMode = o.optString("webrtcMode", d.webrtcMode)
            d.geoMode = o.optString("geoMode", d.geoMode)
            d.geoLat = o.optString("geoLat", d.geoLat)
            d.geoLng = o.optString("geoLng", d.geoLng)
            d.autoMatchProxy = o.optBoolean("autoMatchProxy", d.autoMatchProxy)
            d.userAgentOverride = o.optString("userAgentOverride", d.userAgentOverride)
            d.browserVersion = o.optString("browserVersion", d.browserVersion)
            d.androidVersion = o.optString("androidVersion", d.androidVersion)
            d.iosVersion = o.optString("iosVersion", d.iosVersion)
            return d
        }
    }
}

data class ProxyConfig(
    var type: String = "none",   // none | http | https | socks5
    var host: String = "",
    var port: Int = 0,
    var user: String = "",
    var password: String = "",   // plaintext in memory, encrypted at rest
    var lastIp: String = "",
    var lastCountry: String = "",
    var lastCountryCode: String = "",
    var lastCity: String = "",
    var lastTimezone: String = "",
    var lastCheckedAt: Long = 0L,
    var latencyMs: Int = -1,
    var watch: Boolean = false,
    var watchIntervalSec: Int = 120
) {
    val enabled: Boolean get() = !isNone(type) && host.isNotBlank() && port in 1..65535

    fun toJson(includeSecret: Boolean = true): JSONObject = JSONObject().apply {
        put("type", type); put("host", host); put("port", port); put("user", user)
        if (includeSecret && password.isNotEmpty()) put("passwordEnc", SecureStore.encrypt(password))
        put("lastIp", lastIp); put("lastCountry", lastCountry); put("lastCountryCode", lastCountryCode)
        put("lastCity", lastCity); put("lastTimezone", lastTimezone)
        put("lastCheckedAt", lastCheckedAt); put("latencyMs", latencyMs)
        put("watch", watch); put("watchIntervalSec", watchIntervalSec)
    }

    fun copy() = ProxyConfig(type, host, port, user, password, lastIp, lastCountry, lastCountryCode,
        lastCity, lastTimezone, lastCheckedAt, latencyMs, watch, watchIntervalSec)

    fun label(): String = when {
        !enabled -> "Direct"
        lastCountryCode.isNotBlank() -> "${type.uppercase()} · $lastCountryCode"
        else -> type.uppercase()
    }

    fun detail(): String = if (enabled) "${type.uppercase()} $host:$port" else "No proxy"

    companion object {
        fun isNone(t: String?): Boolean = t.isNullOrBlank() || t.equals("none", ignoreCase = true)
        fun fromJson(o: JSONObject?): ProxyConfig {
            val d = ProxyConfig()
            if (o == null) return d
            d.type = o.optString("type", d.type)
            d.host = o.optString("host", d.host)
            d.port = o.optInt("port", d.port)
            d.user = o.optString("user", d.user)
            d.password = SecureStore.decrypt(o.optString("passwordEnc", ""))
            d.lastIp = o.optString("lastIp", "")
            d.lastCountry = o.optString("lastCountry", "")
            d.lastCountryCode = o.optString("lastCountryCode", "")
            d.lastCity = o.optString("lastCity", "")
            d.lastTimezone = o.optString("lastTimezone", "")
            d.lastCheckedAt = o.optLong("lastCheckedAt", 0L)
            d.latencyMs = o.optInt("latencyMs", -1)
            d.watch = o.optBoolean("watch", false)
            d.watchIntervalSec = o.optInt("watchIntervalSec", d.watchIntervalSec)
            return d
        }
    }
}

data class ToolConfig(
    var humanPaste: Boolean = true,
    /** relaxed | natural | fast | instant */
    var humanPasteSpeed: String = "natural",
    var proxyWatch: Boolean = true,
    var proxyWatchIntervalSec: Int = 120,
    /** Restore native context menu / selection on pages that block them. */
    var unlockSelection: Boolean = true,
    /** Count requests and flag known tracker hosts. */
    var httpTracker: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("humanPaste", humanPaste); put("humanPasteSpeed", humanPasteSpeed)
        put("proxyWatch", proxyWatch); put("proxyWatchIntervalSec", proxyWatchIntervalSec)
        put("unlockSelection", unlockSelection); put("httpTracker", httpTracker)
    }

    companion object {
        fun fromJson(o: JSONObject?): ToolConfig {
            val d = ToolConfig()
            if (o == null) return d
            d.humanPaste = o.optBoolean("humanPaste", d.humanPaste)
            d.humanPasteSpeed = o.optString("humanPasteSpeed", d.humanPasteSpeed)
            d.proxyWatch = o.optBoolean("proxyWatch", d.proxyWatch)
            d.proxyWatchIntervalSec = o.optInt("proxyWatchIntervalSec", d.proxyWatchIntervalSec)
            d.unlockSelection = o.optBoolean("unlockSelection", d.unlockSelection)
            d.httpTracker = o.optBoolean("httpTracker", d.httpTracker)
            return d
        }
    }
}

data class Profile(
    var id: String = UUID.randomUUID().toString().replace("-", ""),
    var name: String = "",
    var notes: String = "",
    var group: String = "",
    var tags: String = "",
    var status: String = "",
    var startUrl: String = DEFAULT_START_URL,
    /** chrome | chromium | brave | firefox | edge — the identity the profile reports. */
    var browserKind: String = "chrome",
    var browserVersion: String = "131.0.6778.85",
    /** Clear cookies/storage/history when the profile closes. */
    var freshSession: Boolean = false,
    /** Whether the profile has ever been launched (drives the first-run BrowserScan open). */
    var everLaunched: Boolean = false,
    var openCount: Int = 0,
    var created: Long = System.currentTimeMillis(),
    var lastOpened: Long = 0L,
    var fp: FingerprintConfig = FingerprintConfig(),
    var proxy: ProxyConfig = ProxyConfig(),
    var tools: ToolConfig = ToolConfig()
) {
    fun groupOrUngrouped(): String = group.ifBlank { "Ungrouped" }

    fun initials(): String {
        val n = name.trim()
        if (n.isEmpty()) return "JB"
        val parts = n.split(Regex("[\\s\\-_]+")).filter { it.isNotEmpty() }
        return when {
            parts.size >= 2 -> (parts[0].first().toString() + parts[1].first().toString()).uppercase()
            n.length >= 2 -> n.substring(0, 2).uppercase()
            else -> n.uppercase()
        }
    }

    fun lastOpenedText(): String {
        if (lastOpened <= 0L) return "Never opened"
        val d = System.currentTimeMillis() - lastOpened
        return when {
            d < 90_000 -> "Opened just now"
            d < 3_600_000 -> "Opened ${d / 60_000}m ago"
            d < 86_400_000 -> "Opened ${d / 3_600_000}h ago"
            d < 30L * 86_400_000 -> "Opened ${d / 86_400_000}d ago"
            else -> "Opened " + java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(lastOpened))
        }
    }

    fun toJson(includeSecret: Boolean = true): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("notes", notes)
        put("group", group); put("tags", tags); put("status", status)
        put("startUrl", startUrl)
        put("browserKind", browserKind); put("browserVersion", browserVersion)
        put("freshSession", freshSession); put("everLaunched", everLaunched)
        put("openCount", openCount); put("created", created); put("lastOpened", lastOpened)
        put("fp", fp.toJson()); put("proxy", proxy.toJson(includeSecret)); put("tools", tools.toJson())
    }

    fun copy(freshId: Boolean = true): Profile = Profile(
        id = if (freshId) UUID.randomUUID().toString().replace("-", "") else id,
        name = name, notes = notes, group = group, tags = tags, status = status, startUrl = startUrl,
        browserKind = browserKind, browserVersion = browserVersion, freshSession = freshSession,
        everLaunched = false, openCount = if (freshId) 0 else openCount,
        created = if (freshId) System.currentTimeMillis() else created,
        lastOpened = if (freshId) 0L else lastOpened,
        fp = FingerprintConfig.fromJson(fp.toJson()),
        proxy = proxy.copy(),
        tools = ToolConfig.fromJson(tools.toJson())
    )

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val d = Profile()
            d.id = o.optString("id", d.id)
            d.name = o.optString("name", d.name)
            d.notes = o.optString("notes", "")
            d.group = o.optString("group", "")
            d.tags = o.optString("tags", "")
            d.status = o.optString("status", "")
            d.startUrl = o.optString("startUrl", DEFAULT_START_URL)
            d.browserKind = o.optString("browserKind", d.browserKind)
            d.browserVersion = o.optString("browserVersion", d.browserVersion)
            d.freshSession = o.optBoolean("freshSession", false)
            d.everLaunched = o.optBoolean("everLaunched", false)
            d.openCount = o.optInt("openCount", 0)
            d.created = o.optLong("created", System.currentTimeMillis())
            d.lastOpened = o.optLong("lastOpened", 0L)
            d.fp = FingerprintConfig.fromJson(o.optJSONObject("fp"))
            d.proxy = ProxyConfig.fromJson(o.optJSONObject("proxy"))
            d.tools = ToolConfig.fromJson(o.optJSONObject("tools"))
            return d
        }
    }
}

/** A saved proxy in the proxy library (independent of any single profile). */
data class SavedProxy(
    var id: String = UUID.randomUUID().toString().replace("-", ""),
    var name: String = "",
    var config: ProxyConfig = ProxyConfig(),
    var note: String = ""
) {
    fun toJson(includeSecret: Boolean = true): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("note", note)
        put("config", config.toJson(includeSecret))
    }

    companion object {
        fun fromJson(o: JSONObject): SavedProxy = SavedProxy(
            id = o.optString("id", UUID.randomUUID().toString().replace("-", "")),
            name = o.optString("name", ""),
            note = o.optString("note", ""),
            config = ProxyConfig.fromJson(o.optJSONObject("config"))
        )
    }
}

data class HistoryEntry(
    var id: Long = 0,
    var profileId: String,
    var profileName: String,
    var url: String,
    var title: String,
    var ts: Long
)

data class ExtensionItem(
    var id: String = UUID.randomUUID().toString().replace("-", ""),
    var name: String,
    var storeUrl: String,
    var addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("storeUrl", storeUrl); put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ExtensionItem = ExtensionItem(
            id = o.optString("id", UUID.randomUUID().toString().replace("-", "")),
            name = o.optString("name", "Extension"),
            storeUrl = o.optString("storeUrl", ""),
            addedAt = o.optLong("addedAt", System.currentTimeMillis())
        )
    }
}

/** Consistency findings for the fingerprint editor summary. */
data class FingerprintWarning(val message: String, val severity: String)

object ExportFormat {
    fun build(profiles: List<Profile>, proxies: List<SavedProxy>, extensions: List<ExtensionItem>,
              includeSecrets: Boolean): JSONObject = JSONObject().apply {
        put("app", "JoeBrowser")
        put("platform", "android")
        put("formatVersion", 1)
        put("exportedAt", System.currentTimeMillis())
        put("includeCredentials", includeSecrets)
        put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson(includeSecrets)) } })
        put("proxies", JSONArray().apply { proxies.forEach { put(it.toJson(includeSecrets)) } })
        put("extensions", JSONArray().apply { extensions.forEach { put(it.toJson()) } })
    }
}
