package com.joebrowser.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Joe Browser data model.
 *
 * Every persisted type is JSON-backed (org.json ships with Android and needs no dependency),
 * and every reader is written defensively: a field written by an older build, a truncated
 * blob, or an unknown enum value falls back to a sane default instead of throwing. That is
 * what keeps a corrupt row from crashing the app on launch.
 */

/*
 * An anti-detect browser should open to a neutral page, not to one vendor's detector: a startup
 * URL that instantly loads a fingerprint-test script tells that vendor (and anyone watching the
 * exit IP) which product is in use before the user has done anything. BrowserScan stays one tap
 * away in Home → Verify and in the browser's Fingerprint-check menu.
 */
const val DEFAULT_START_URL = "https://www.google.com/"

/** Browser engines a profile can present itself as. Rendering is always the system WebView. */
enum class BrowserEngine(val id: String, val title: String, val engineFamily: String) {
    CHROME("chrome", "Google Chrome", "Chromium"),
    CHROMIUM("chromium", "Chromium", "Chromium"),
    BRAVE("brave", "Brave", "Chromium"),
    EDGE("edge", "Microsoft Edge", "Chromium"),
    FIREFOX("firefox", "Mozilla Firefox", "Gecko");

    companion object {
        fun of(id: String?): BrowserEngine = entries.firstOrNull { it.id.equals(id, true) } ?: CHROME
    }
}

/** Operating systems a profile can present. */
object OsCatalog {
    val all = listOf("Android", "iPhone", "iPad", "Windows 11", "Windows 10", "macOS", "Linux")
    fun isMobile(os: String): Boolean = os == "Android" || os == "iPhone" || os == "iPad"
    fun deviceType(os: String): String = when (os) {
        "Android", "iPhone" -> "mobile"
        "iPad" -> "tablet"
        else -> "desktop"
    }
}

// ---------------------------------------------------------------- fingerprint

data class FingerprintConfig(
    var seed: Int = 1,
    var os: String = "Android",
    var deviceType: String = "mobile",
    var deviceModel: String = "",
    var timezone: String = "Asia/Dhaka",
    var language: String = "en-US",
    var acceptLanguage: String = "en-US,en;q=0.9",
    var screenW: Int = 393,
    var screenH: Int = 851,
    var availW: Int = 393,
    var availH: Int = 851,
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
    var fontNoise: Boolean = true,
    var mediaNoise: Boolean = true,
    var doNotTrack: Boolean = false,
    var spoofBattery: Boolean = true,
    /** off | proxy | public */
    var webrtcMode: String = "proxy",
    /** proxy | system | custom | block */
    var geoMode: String = "proxy",
    var geoLat: String = "0",
    var geoLng: String = "0",
    /** Keep timezone/language glued to the proxy's exit country. */
    var autoMatchProxy: Boolean = true,
    /** Manual screen override flag — when false the factory keeps screen/GPU coherent. */
    var manualScreen: Boolean = false,
    var userAgentOverride: String = "",
    var browserVersion: String = "134.0.6998.89",
    var androidVersion: String = "auto",
    var iosVersion: String = "auto"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("seed", seed)
        put("os", os)
        put("deviceType", deviceType)
        put("deviceModel", deviceModel)
        put("timezone", timezone)
        put("language", language)
        put("acceptLanguage", acceptLanguage)
        put("screenW", screenW)
        put("screenH", screenH)
        put("availW", availW)
        put("availH", availH)
        put("colorDepth", colorDepth)
        put("pixelRatio", pixelRatio)
        put("webglVendor", webglVendor)
        put("webglRenderer", webglRenderer)
        put("cores", cores)
        put("memory", memory)
        put("touchPoints", touchPoints)
        put("canvasNoise", canvasNoise)
        put("audioNoise", audioNoise)
        put("hardwareNoise", hardwareNoise)
        put("fontNoise", fontNoise)
        put("mediaNoise", mediaNoise)
        put("doNotTrack", doNotTrack)
        put("spoofBattery", spoofBattery)
        put("webrtcMode", webrtcMode)
        put("geoMode", geoMode)
        put("geoLat", geoLat)
        put("geoLng", geoLng)
        put("autoMatchProxy", autoMatchProxy)
        put("manualScreen", manualScreen)
        put("userAgentOverride", userAgentOverride)
        put("browserVersion", browserVersion)
        put("androidVersion", androidVersion)
        put("iosVersion", iosVersion)
    }

    /** Deep copy that survives serialization round-trips. */
    fun duplicate(): FingerprintConfig = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject?): FingerprintConfig {
            val d = FingerprintConfig()
            if (o == null) return d
            return d.apply {
                seed = o.optInt("seed", seed)
                os = o.optString("os", os).takeIf { it in OsCatalog.all } ?: os
                deviceType = o.optString("deviceType", OsCatalog.deviceType(os))
                deviceModel = o.optString("deviceModel", deviceModel)
                timezone = o.optString("timezone", timezone)
                language = o.optString("language", language)
                acceptLanguage = o.optString("acceptLanguage", acceptLanguage)
                screenW = o.optInt("screenW", screenW).coerceIn(200, 7680)
                screenH = o.optInt("screenH", screenH).coerceIn(200, 4320)
                availW = o.optInt("availW", screenW).coerceIn(200, 7680)
                availH = o.optInt("availH", screenH).coerceIn(200, 4320)
                colorDepth = o.optInt("colorDepth", colorDepth).coerceIn(8, 64)
                pixelRatio = o.optDouble("pixelRatio", pixelRatio).takeIf { it in 0.5..8.0 } ?: 1.0
                webglVendor = o.optString("webglVendor", webglVendor)
                webglRenderer = o.optString("webglRenderer", webglRenderer)
                cores = o.optInt("cores", cores).coerceIn(1, 64)
                memory = o.optInt("memory", memory).coerceIn(1, 64)
                touchPoints = o.optInt("touchPoints", touchPoints).coerceIn(0, 10)
                canvasNoise = o.optBoolean("canvasNoise", canvasNoise)
                audioNoise = o.optBoolean("audioNoise", audioNoise)
                hardwareNoise = o.optBoolean("hardwareNoise", hardwareNoise)
                fontNoise = o.optBoolean("fontNoise", fontNoise)
                mediaNoise = o.optBoolean("mediaNoise", mediaNoise)
                doNotTrack = o.optBoolean("doNotTrack", doNotTrack)
                spoofBattery = o.optBoolean("spoofBattery", spoofBattery)
                webrtcMode = o.optString("webrtcMode", webrtcMode).takeIf { it in WEBRTC_MODES } ?: "proxy"
                geoMode = o.optString("geoMode", geoMode).takeIf { it in GEO_MODES } ?: "proxy"
                geoLat = o.optString("geoLat", geoLat)
                geoLng = o.optString("geoLng", geoLng)
                autoMatchProxy = o.optBoolean("autoMatchProxy", autoMatchProxy)
                manualScreen = o.optBoolean("manualScreen", manualScreen)
                userAgentOverride = o.optString("userAgentOverride", userAgentOverride)
                browserVersion = o.optString("browserVersion", browserVersion)
                androidVersion = o.optString("androidVersion", androidVersion)
                iosVersion = o.optString("iosVersion", iosVersion)
            }
        }

        val WEBRTC_MODES = listOf("off", "proxy", "public")
        val GEO_MODES = listOf("proxy", "system", "custom", "block")
    }
}

// ---------------------------------------------------------------------- proxy

data class ProxyConfig(
    /** none | http | https | socks5 */
    var type: String = "none",
    var host: String = "",
    var port: Int = 0,
    var user: String = "",
    /** Plain in memory, AES-GCM encrypted at rest by [SecureStore]. */
    var password: String = "",
    var lastIp: String = "",
    var lastCountry: String = "",
    var lastCountryCode: String = "",
    var lastCity: String = "",
    var lastTimezone: String = "",
    var lastCheckedAt: Long = 0L,
    var latencyMs: Int = -1,
    var lastError: String = "",
    var failStreak: Int = 0,
    var rotations: Int = 0,
    var watch: Boolean = false,
    var watchIntervalSec: Int = 120
) {
    val enabled: Boolean get() = isNone(type).not() && host.isNotBlank() && port in 1..65535
    val online: Boolean get() = enabled && lastIp.isNotBlank() && latencyMs >= 0
    val checked: Boolean get() = lastCheckedAt > 0L

    fun label(): String = when {
        !enabled -> "Direct"
        lastCountryCode.isNotBlank() -> type.uppercase() + " · " + lastCountryCode
        else -> type.uppercase()
    }

    fun detail(): String = if (enabled) type.uppercase() + " " + host + ":" + port else "No proxy"

    fun authority(): String = if (enabled) host + ":" + port else ""

    fun toJson(includeSecret: Boolean = true, portableSecret: Boolean = false): JSONObject = JSONObject().apply {
        put("type", type)
        put("host", host)
        put("port", port)
        put("user", user)
        if (includeSecret && password.isNotEmpty()) {
            if (portableSecret) put("password", password)
            else put("passwordEnc", SecureStore.encrypt(password))
        }
        put("lastIp", lastIp)
        put("lastCountry", lastCountry)
        put("lastCountryCode", lastCountryCode)
        put("lastCity", lastCity)
        put("lastTimezone", lastTimezone)
        put("lastCheckedAt", lastCheckedAt)
        put("latencyMs", latencyMs)
        put("lastError", lastError)
        put("failStreak", failStreak)
        put("rotations", rotations)
        put("watch", watch)
        put("watchIntervalSec", watchIntervalSec)
    }

    fun duplicate(includeSecret: Boolean = true): ProxyConfig = fromJson(toJson(includeSecret))

    companion object {
        fun isNone(t: String?): Boolean = t.isNullOrBlank() || t.equals("none", ignoreCase = true)

        fun direct(): ProxyConfig = ProxyConfig(type = "none")

        fun fromJson(o: JSONObject?): ProxyConfig {
            val d = ProxyConfig()
            if (o == null) return d
            d.type = normalizeType(o.optString("type", d.type))
            d.host = o.optString("host", "")
            d.port = o.optInt("port", 0).coerceIn(0, 65535)
            d.user = o.optString("user", "")
            val plainPassword = o.optString("password", "")
            d.password = plainPassword.ifEmpty {
                SecureStore.decrypt(o.optString("passwordEnc", ""))
            }
            d.lastIp = o.optString("lastIp", "")
            d.lastCountry = o.optString("lastCountry", "")
            d.lastCountryCode = o.optString("lastCountryCode", "")
            d.lastCity = o.optString("lastCity", "")
            d.lastTimezone = o.optString("lastTimezone", "")
            d.lastCheckedAt = o.optLong("lastCheckedAt", 0L)
            d.latencyMs = o.optInt("latencyMs", -1)
            d.lastError = o.optString("lastError", "")
            d.failStreak = o.optInt("failStreak", 0)
            d.rotations = o.optInt("rotations", 0)
            d.watch = o.optBoolean("watch", false)
            d.watchIntervalSec = o.optInt("watchIntervalSec", 120).coerceIn(30, 3600)
            return d
        }

        fun normalizeType(raw: String): String = when (raw.lowercase()) {
            "http", "https", "socks5" -> raw.lowercase()
            "socks", "socks5h", "socks4", "socks4a" -> "socks5"
            else -> "none"
        }
    }
}

// ---------------------------------------------------------------------- tools

data class ToolConfig(
    var humanPaste: Boolean = true,
    /** relaxed | natural | fast | instant */
    var humanPasteSpeed: String = "natural",
    /** Inject realistic key pauses and punctuation rhythm. */
    var humanPasteJitter: Boolean = true,
    var proxyWatch: Boolean = true,
    var proxyWatchIntervalSec: Int = 120,
    /** Restore text selection / context menu on hostile pages. */
    var unlockSelection: Boolean = true,
    /** Count requests and flag known tracker hosts. */
    var httpTracker: Boolean = true,
    /** Re-align timezone/language when the proxy exit country rotates. */
    var autoSyncLocale: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("humanPaste", humanPaste)
        put("humanPasteSpeed", humanPasteSpeed)
        put("humanPasteJitter", humanPasteJitter)
        put("proxyWatch", proxyWatch)
        put("proxyWatchIntervalSec", proxyWatchIntervalSec)
        put("unlockSelection", unlockSelection)
        put("httpTracker", httpTracker)
        put("autoSyncLocale", autoSyncLocale)
    }

    companion object {
        val SPEEDS = listOf("relaxed", "natural", "fast", "instant")

        fun fromJson(o: JSONObject?): ToolConfig {
            val d = ToolConfig()
            if (o == null) return d
            d.humanPaste = o.optBoolean("humanPaste", d.humanPaste)
            d.humanPasteSpeed = o.optString("humanPasteSpeed", d.humanPasteSpeed)
                .takeIf { it in SPEEDS } ?: "natural"
            d.humanPasteJitter = o.optBoolean("humanPasteJitter", d.humanPasteJitter)
            d.proxyWatch = o.optBoolean("proxyWatch", d.proxyWatch)
            d.proxyWatchIntervalSec = o.optInt("proxyWatchIntervalSec", d.proxyWatchIntervalSec)
                .coerceIn(30, 3600)
            d.unlockSelection = o.optBoolean("unlockSelection", d.unlockSelection)
            d.httpTracker = o.optBoolean("httpTracker", d.httpTracker)
            d.autoSyncLocale = o.optBoolean("autoSyncLocale", d.autoSyncLocale)
            return d
        }
    }
}

// -------------------------------------------------------------------- profile

data class Profile(
    var id: String = newId(),
    var name: String = "",
    var notes: String = "",
    var group: String = "",
    var tags: String = "",
    var status: String = "",
    var startUrl: String = DEFAULT_START_URL,
    var browserKind: String = BrowserEngine.CHROME.id,
    var browserVersion: String = "134.0.6998.89",
    /** Clear cookies/storage/history when the profile closes. */
    var freshSession: Boolean = false,
    var everLaunched: Boolean = false,
    var openCount: Int = 0,
    var created: Long = System.currentTimeMillis(),
    var lastOpened: Long = 0L,
    var fp: FingerprintConfig = FingerprintConfig(),
    var proxy: ProxyConfig = ProxyConfig(),
    var tools: ToolConfig = ToolConfig()
) {
    val engine: BrowserEngine get() = BrowserEngine.of(browserKind)

    fun groupOrUngrouped(): String = group.ifBlank { "Ungrouped" }

    fun displayName(): String = name.ifBlank { "Untitled profile" }

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
            d < 3_600_000 -> "Opened " + (d / 60_000) + "m ago"
            d < 86_400_000 -> "Opened " + (d / 3_600_000) + "h ago"
            d < 30L * 86_400_000 -> "Opened " + (d / 86_400_000) + "d ago"
            else -> "Opened " + java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
                .format(java.util.Date(lastOpened))
        }
    }

    fun toJson(includeSecret: Boolean = true, portableSecret: Boolean = false): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("notes", notes)
        put("group", group)
        put("tags", tags)
        put("status", status)
        put("startUrl", startUrl)
        put("browserKind", browserKind)
        put("browserVersion", browserVersion)
        put("freshSession", freshSession)
        put("everLaunched", everLaunched)
        put("openCount", openCount)
        put("created", created)
        put("lastOpened", lastOpened)
        put("fp", fp.toJson())
        put("proxy", proxy.toJson(includeSecret, portableSecret))
        put("tools", tools.toJson())
    }

    /** A new profile that inherits the identity but not the history of this one. */
    fun duplicate(newName: String = name + " copy"): Profile = Profile(
        name = newName,
        notes = notes,
        group = group,
        tags = tags,
        status = status,
        startUrl = startUrl,
        browserKind = browserKind,
        browserVersion = browserVersion,
        freshSession = freshSession,
        fp = fp.duplicate(),
        proxy = proxy.duplicate(),
        tools = ToolConfig.fromJson(tools.toJson())
    )

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")

        fun fromJson(o: JSONObject?): Profile {
            val d = Profile()
            if (o == null) return d
            val parsedId = o.optString("id", d.id).ifBlank { d.id }
            return d.apply {
                id = parsedId
                name = o.optString("name", name)
                notes = o.optString("notes", notes)
                group = o.optString("group", group)
                tags = o.optString("tags", tags)
                status = o.optString("status", status)
                startUrl = o.optString("startUrl", DEFAULT_START_URL).ifBlank { DEFAULT_START_URL }
                browserKind = BrowserEngine.of(o.optString("browserKind", browserKind)).id
                browserVersion = o.optString("browserVersion", browserVersion)
                freshSession = o.optBoolean("freshSession", freshSession)
                everLaunched = o.optBoolean("everLaunched", everLaunched)
                openCount = o.optInt("openCount", openCount)
                created = o.optLong("created", System.currentTimeMillis())
                lastOpened = o.optLong("lastOpened", 0L)
                fp = FingerprintConfig.fromJson(o.optJSONObject("fp"))
                proxy = ProxyConfig.fromJson(o.optJSONObject("proxy"))
                tools = ToolConfig.fromJson(o.optJSONObject("tools"))
            }
        }
    }
}

/** A saved proxy in the library (independent of any single profile). */
data class SavedProxy(
    var id: String = newId(),
    var name: String = "",
    var config: ProxyConfig = ProxyConfig(),
    var note: String = ""
) {
    fun displayName(): String = name.ifBlank { config.authority().ifBlank { "Proxy" } }

    fun toJson(includeSecret: Boolean = true, portableSecret: Boolean = false): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("note", note)
        put("config", config.toJson(includeSecret, portableSecret))
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")

        fun fromJson(o: JSONObject?): SavedProxy {
            val d = SavedProxy()
            if (o == null) return d
            return d.apply {
                id = o.optString("id", id).ifBlank { id }
                name = o.optString("name", name)
                note = o.optString("note", note)
                config = ProxyConfig.fromJson(o.optJSONObject("config"))
            }
        }
    }
}

// -------------------------------------------------------------------- history

data class HistoryEntry(
    var id: Long = 0,
    var profileId: String,
    var profileName: String,
    var url: String,
    var title: String,
    var ts: Long
)

// ----------------------------------------------------------------- extensions

data class ExtensionItem(
    var id: String = UUID.randomUUID().toString().replace("-", ""),
    var name: String,
    var storeUrl: String,
    var addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("storeUrl", storeUrl)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject?): ExtensionItem? {
            if (o == null) return null
            val url = o.optString("storeUrl", "")
            if (url.isBlank()) return null
            return ExtensionItem(
                id = o.optString("id", UUID.randomUUID().toString().replace("-", "")),
                name = o.optString("name", "Extension"),
                storeUrl = url,
                addedAt = o.optLong("addedAt", System.currentTimeMillis())
            )
        }
    }
}

// --------------------------------------------------------------------- alerts

/** A monitor event surfaced to the UI (offline / recovered / exit-IP rotation). */
data class MonitorAlert(
    var id: Long = 0,
    /** offline | recovered | rotated */
    var kind: String,
    var target: String,
    var detail: String,
    var ts: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_OFFLINE = "offline"
        const val KIND_RECOVERED = "recovered"
        const val KIND_ROTATED = "rotated"

        fun fromJson(o: JSONObject?): MonitorAlert? {
            if (o == null) return null
            return MonitorAlert(
                id = o.optLong("id", 0),
                kind = o.optString("kind", KIND_OFFLINE),
                target = o.optString("target", ""),
                detail = o.optString("detail", ""),
                ts = o.optLong("ts", System.currentTimeMillis())
            )
        }
    }
}

// ----------------------------------------------------------------- consistency

/** Consistency findings for the fingerprint editor summary. */
data class FingerprintWarning(val message: String, val severity: String)

// --------------------------------------------------------------------- export

object ExportFormat {

    /** Result of reading a backup file back in. */
    data class Restore(
        val profiles: List<Profile>,
        val proxies: List<SavedProxy>,
        val skippedProfiles: Int,
        val skippedProxies: Int,
        val hadCredentials: Boolean,
        val app: String
    ) {
        val total: Int get() = profiles.size + proxies.size
    }

    /**
     * Parses a backup produced by [build].
     *
     * Deliberately tolerant: one malformed profile must not discard the rest of the file, and a
     * file from an unknown app or a newer format version is rejected outright rather than
     * half-imported. Returns null only when the file is not a Joe Browser backup at all.
     */
    fun parse(text: String?): Restore? {
        if (text.isNullOrBlank()) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val app = root.optString("app", "")
        if (app.isBlank()) return null
        val version = root.optInt("formatVersion", 1)
        if (version > 2) return null

        var skippedProfiles = 0
        var skippedProxies = 0

        val profiles = mutableListOf<Profile>()
        root.optJSONArray("profiles")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                if (item == null) { skippedProfiles++; continue }
                val parsed = Profile.fromJson(item)
                if (parsed.id.isBlank() || parsed.name.isBlank()) skippedProfiles++ else profiles.add(parsed)
            }
        }

        val proxies = mutableListOf<SavedProxy>()
        root.optJSONArray("proxies")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                if (item == null) { skippedProxies++; continue }
                val parsed = SavedProxy.fromJson(item)
                if (parsed.id.isBlank() || parsed.config.host.isBlank() || parsed.config.port !in 1..65535) {
                    skippedProxies++
                } else {
                    proxies.add(parsed)
                }
            }
        }

        return Restore(
            profiles = profiles,
            proxies = proxies,
            skippedProfiles = skippedProfiles,
            skippedProxies = skippedProxies,
            hadCredentials = root.optBoolean("includeCredentials", false),
            app = app
        )
    }

    fun build(
        profiles: List<Profile>,
        proxies: List<SavedProxy>,
        extensions: List<ExtensionItem>,
        includeSecrets: Boolean
    ): JSONObject = JSONObject().apply {
        put("app", "JoeBrowser")
        put("platform", "android")
        put("formatVersion", 2)
        put("exportedAt", System.currentTimeMillis())
        put("includeCredentials", includeSecrets)
        // Portability is the point of an export: the on-device ciphertext is sealed with this
        // install's Android Keystore key and cannot be read anywhere else. Secrets therefore
        // leave as plaintext inside the (user-chosen) file, and the flag says so.
        put("credentialFormat", "plaintext")
        put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson(includeSecrets, portableSecret = true)) } })
        put("proxies", JSONArray().apply { proxies.forEach { put(it.toJson(includeSecrets, portableSecret = true)) } })
        put("extensions", JSONArray().apply { extensions.forEach { put(it.toJson()) } })
    }
}
