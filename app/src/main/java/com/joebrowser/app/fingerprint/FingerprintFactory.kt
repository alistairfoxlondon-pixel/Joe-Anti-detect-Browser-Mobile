package com.joebrowser.app.fingerprint

import com.joebrowser.app.data.FingerprintConfig
import com.joebrowser.app.data.FingerprintWarning
import com.joebrowser.app.data.ProxyConfig
import java.util.UUID
import kotlin.random.Random

/**
 * Generates technically consistent fingerprint identities.
 * Ported from the Joe Browser desktop engine: a profile's OS, screen, GPU, cores,
 * memory, timezone, language and user agent always agree with each other.
 */
object FingerprintFactory {

    val osList = listOf("Android", "Windows 11", "Windows 10", "macOS", "Linux", "iPhone", "iPad")
    val osShortLabel = mapOf(
        "Android" to "Android", "Windows 11" to "Windows 11", "Windows 10" to "Windows 10",
        "macOS" to "macOS", "Linux" to "Linux", "iPhone" to "iPhone", "iPad" to "iPad"
    )
    val timezones = listOf(
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Toronto",
        "America/Sao_Paulo", "America/Mexico_City", "America/Bogota", "America/Argentina/Buenos_Aires",
        "Europe/London", "Europe/Dublin", "Europe/Lisbon", "Europe/Madrid", "Europe/Paris", "Europe/Berlin",
        "Europe/Rome", "Europe/Amsterdam", "Europe/Brussels", "Europe/Vienna", "Europe/Zurich", "Europe/Warsaw",
        "Europe/Stockholm", "Europe/Oslo", "Europe/Copenhagen", "Europe/Helsinki", "Europe/Kyiv", "Europe/Moscow",
        "Europe/Istanbul", "Asia/Dhaka", "Asia/Karachi", "Asia/Kolkata", "Asia/Dubai", "Asia/Riyadh", "Asia/Jerusalem",
        "Asia/Bangkok", "Asia/Jakarta", "Asia/Manila", "Asia/Singapore", "Asia/Kuala_Lumpur", "Asia/Hong_Kong",
        "Asia/Shanghai", "Asia/Tokyo", "Asia/Seoul", "Australia/Sydney", "Pacific/Auckland", "Africa/Cairo",
        "Africa/Lagos", "Africa/Johannesburg"
    )
    val languages = listOf(
        "en-US", "en-GB", "en-CA", "en-AU", "en-IN", "de-DE", "fr-FR", "es-ES", "es-MX", "pt-BR", "pt-PT",
        "it-IT", "nl-NL", "pl-PL", "sv-SE", "da-DK", "nb-NO", "fi-FI", "tr-TR", "ru-RU", "uk-UA",
        "ro-RO", "el-GR", "ar-SA", "he-IL", "hi-IN", "ja-JP", "ko-KR", "zh-CN", "zh-HK", "id-ID",
        "th-TH", "vi-VN", "ms-MY", "bn-BD"
    )
    val coreOptions = listOf(2, 4, 6, 8, 10, 12, 16)
    /** Chrome rounds deviceMemory to a power of two and caps at 8 — anything bigger is a tell. */
    val memoryOptions = listOf(2, 4, 8)

    /** country ISO code -> (IANA timezone, language). Used to match locale to the egress IP. */
    val countryLocale = mapOf(
        "US" to ("America/New_York" to "en-US"), "GB" to ("Europe/London" to "en-GB"),
        "CA" to ("America/Toronto" to "en-CA"), "AU" to ("Australia/Sydney" to "en-AU"),
        "DE" to ("Europe/Berlin" to "de-DE"), "FR" to ("Europe/Paris" to "fr-FR"),
        "ES" to ("Europe/Madrid" to "es-ES"), "IT" to ("Europe/Rome" to "it-IT"),
        "NL" to ("Europe/Amsterdam" to "nl-NL"), "BE" to ("Europe/Brussels" to "nl-BE"),
        "CH" to ("Europe/Zurich" to "de-CH"), "AT" to ("Europe/Vienna" to "de-AT"),
        "SE" to ("Europe/Stockholm" to "sv-SE"), "NO" to ("Europe/Oslo" to "nb-NO"),
        "DK" to ("Europe/Copenhagen" to "da-DK"), "FI" to ("Europe/Helsinki" to "fi-FI"),
        "PL" to ("Europe/Warsaw" to "pl-PL"), "PT" to ("Europe/Lisbon" to "pt-PT"),
        "IE" to ("Europe/Dublin" to "en-IE"), "RU" to ("Europe/Moscow" to "ru-RU"),
        "UA" to ("Europe/Kyiv" to "uk-UA"), "TR" to ("Europe/Istanbul" to "tr-TR"),
        "BR" to ("America/Sao_Paulo" to "pt-BR"), "MX" to ("America/Mexico_City" to "es-MX"),
        "AR" to ("America/Argentina/Buenos_Aires" to "es-AR"), "CO" to ("America/Bogota" to "es-CO"),
        "IN" to ("Asia/Kolkata" to "en-IN"), "PK" to ("Asia/Karachi" to "en-PK"),
        "BD" to ("Asia/Dhaka" to "bn-BD"), "AE" to ("Asia/Dubai" to "en-AE"),
        "SA" to ("Asia/Riyadh" to "ar-SA"), "IL" to ("Asia/Jerusalem" to "he-IL"),
        "SG" to ("Asia/Singapore" to "en-SG"), "MY" to ("Asia/Kuala_Lumpur" to "ms-MY"),
        "TH" to ("Asia/Bangkok" to "th-TH"), "VN" to ("Asia/Ho_Chi_Minh" to "vi-VN"),
        "ID" to ("Asia/Jakarta" to "id-ID"), "PH" to ("Asia/Manila" to "en-PH"),
        "HK" to ("Asia/Hong_Kong" to "zh-HK"), "CN" to ("Asia/Shanghai" to "zh-CN"),
        "JP" to ("Asia/Tokyo" to "ja-JP"), "KR" to ("Asia/Seoul" to "ko-KR"),
        "ZA" to ("Africa/Johannesburg" to "en-ZA"), "NG" to ("Africa/Lagos" to "en-NG"),
        "EG" to ("Africa/Cairo" to "ar-EG")
    )

    private data class ScreenTriple(val w: Int, val h: Int, val dpr: Double)

    private val screens = mapOf(
        "Android" to listOf(
            ScreenTriple(411, 915, 2.625), ScreenTriple(427, 952, 3.0), ScreenTriple(411, 891, 3.5),
            ScreenTriple(393, 851, 2.75), ScreenTriple(360, 780, 3.0), ScreenTriple(412, 915, 3.5)
        ),
        "Windows 11" to listOf(
            ScreenTriple(1920, 1080, 1.0), ScreenTriple(2560, 1440, 1.0), ScreenTriple(1536, 864, 1.25),
            ScreenTriple(1366, 768, 1.0), ScreenTriple(1920, 1200, 1.0), ScreenTriple(1600, 900, 1.0),
            ScreenTriple(3840, 2160, 1.5), ScreenTriple(2560, 1440, 1.25)
        ),
        "Windows 10" to listOf(
            ScreenTriple(1366, 768, 1.0), ScreenTriple(1920, 1080, 1.0), ScreenTriple(1600, 900, 1.0),
            ScreenTriple(2560, 1440, 1.0), ScreenTriple(1440, 900, 1.0), ScreenTriple(1280, 720, 1.0),
            ScreenTriple(1280, 1024, 1.0), ScreenTriple(1024, 768, 1.0)
        ),
        "macOS" to listOf(
            ScreenTriple(1512, 982, 2.0), ScreenTriple(1728, 1117, 2.0), ScreenTriple(1440, 900, 2.0),
            ScreenTriple(2560, 1440, 2.0), ScreenTriple(3024, 1964, 2.0), ScreenTriple(2560, 1600, 2.0)
        ),
        "Linux" to listOf(
            ScreenTriple(1920, 1080, 1.0), ScreenTriple(1366, 768, 1.0), ScreenTriple(2560, 1440, 1.0),
            ScreenTriple(1600, 900, 1.0), ScreenTriple(1920, 1200, 1.0), ScreenTriple(1680, 1050, 1.0)
        ),
        "iPhone" to listOf(
            ScreenTriple(393, 852, 3.0), ScreenTriple(390, 844, 3.0), ScreenTriple(430, 932, 3.0),
            ScreenTriple(420, 912, 3.0), ScreenTriple(375, 812, 3.0)
        ),
        "iPad" to listOf(
            ScreenTriple(820, 1180, 2.0), ScreenTriple(1024, 1366, 2.0), ScreenTriple(1194, 834, 2.0), ScreenTriple(744, 1133, 2.0)
        )
    )

    /** GPU pools per OS, ordered newest first; cores/memory derive from the pick index. */
    private val gpus = mapOf(
        "Android" to listOf(
            "Google Inc. (Qualcomm)" to "ANGLE (Qualcomm, Adreno (TM) 830, OpenGL ES 3.2)",
            "Google Inc. (ARM)" to "ANGLE (ARM, Immortalis-G720, OpenGL ES 3.2)",
            "Google Inc. (ARM)" to "ANGLE (ARM, Mali-G720, OpenGL ES 3.2)",
            "Google Inc. (Qualcomm)" to "ANGLE (Qualcomm, Adreno (TM) 750, OpenGL ES 3.2)",
            "Google Inc. (ARM)" to "ANGLE (ARM, Mali-G715, OpenGL ES 3.2)",
            "Google Inc. (Qualcomm)" to "ANGLE (Qualcomm, Adreno (TM) 730, OpenGL ES 3.2)",
            "Google Inc. (ARM)" to "ANGLE (ARM, Mali-G57, OpenGL ES 3.2)"
        ),
        "Windows 11" to listOf(
            "Google Inc. (NVIDIA)" to "ANGLE (NVIDIA, NVIDIA GeForce RTX 4070 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (NVIDIA)" to "ANGLE (NVIDIA, NVIDIA GeForce RTX 4060 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (AMD)" to "ANGLE (AMD, AMD Radeon RX 7800 XT Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (AMD)" to "ANGLE (AMD, AMD Radeon RX 7600 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (Intel)" to "ANGLE (Intel, Intel(R) Iris(R) Xe Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (Intel)" to "ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (NVIDIA)" to "ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 SUPER Direct3D11 vs_5_0 ps_5_0, D3D11)"
        ),
        "Windows 10" to listOf(
            "Google Inc. (NVIDIA)" to "ANGLE (NVIDIA, NVIDIA GeForce GTX 1660 SUPER Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (Intel)" to "ANGLE (Intel, Intel(R) UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (AMD)" to "ANGLE (AMD, AMD Radeon RX 580 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (Intel)" to "ANGLE (Intel, Intel(R) HD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)",
            "Google Inc. (Intel)" to "ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11 vs_5_0 ps_5_0, D3D11)"
        ),
        "macOS" to listOf(
            "Google Inc. (Apple)" to "ANGLE (Apple, ANGLE Metal Renderer: Apple M3, Unspecified Version)",
            "Google Inc. (Apple)" to "ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)",
            "Google Inc. (Apple)" to "ANGLE (Apple, ANGLE Metal Renderer: Apple M1, Unspecified Version)",
            "Google Inc. (Apple)" to "ANGLE (Apple, ANGLE Metal Renderer: Apple M4, Unspecified Version)"
        ),
        "Linux" to listOf(
            "Google Inc. (Intel)" to "ANGLE (Intel, Mesa Intel(R) Xe Graphics (TGL GT2), OpenGL 4.6)",
            "Google Inc. (Intel)" to "ANGLE (Intel, Mesa Intel(R) UHD Graphics 620 (KBL GT2), OpenGL 4.6)",
            "Google Inc. (AMD)" to "ANGLE (AMD, AMD Radeon RX 6600 (NAVI23, LLVM 19.1.1, DRM 3.59, 6.11.0-19-generic), OpenGL 4.6)",
            "Google Inc. (NVIDIA)" to "ANGLE (NVIDIA, NVIDIA GeForce RTX 4070 SUPER (0x00002705), OpenGL 4.6)"
        ),
        // iOS exposes the bare "Apple GPU" — one honest value, nothing to tier.
        "iPhone" to listOf("Apple Inc." to "Apple GPU"),
        "iPad" to listOf("Apple Inc." to "Apple GPU")
    )

    /** Android screen size -> (model, GPU) so the viewport and the silicon always match. */
    private val androidDevices = mapOf(
        "411x915" to Triple("Pixel 9", "16", gpus.getValue("Android")[0]),
        "427x952" to Triple("Pixel 9 Pro XL", "16", gpus.getValue("Android")[0]),
        "411x891" to Triple("SM-S931B", "16", gpus.getValue("Android")[5]),
        "393x851" to Triple("SM-S908B", "15", gpus.getValue("Android")[5]),
        "360x780" to Triple("SM-A155F", "15", gpus.getValue("Android")[6]),
        "412x915" to Triple("Pixel 9 Pro", "16", gpus.getValue("Android")[0])
    )
    private val iosDevices = mapOf(
        "393x852" to Triple("iPhone 16", "18.3", "26.1"),
        "390x844" to Triple("iPhone 15", "18.3", "26.1"),
        "430x932" to Triple("iPhone 16 Pro Max", "18.3", "26.1"),
        "420x912" to Triple("iPhone 17 Pro", "26.2", "26.2"),
        "375x812" to Triple("iPhone 13 mini", "18.3", "26.1"),
        "820x1180" to Triple("iPad Air 11", "18.3", "26.1"),
        "1024x1366" to Triple("iPad Pro 13", "18.3", "26.1"),
        "1194x834" to Triple("iPad Pro 11", "18.3", "26.1"),
        "744x1133" to Triple("iPad mini 7", "18.3", "26.1")
    )

    /** Real published Chromium branches (newest first) for per-profile build pinning. */
    val chromiumBuilds = listOf(
        "134.0.6998.89", "133.0.6943.98", "132.0.6834.110", "131.0.6778.85",
        "130.0.6723.116", "129.0.6668.100", "128.0.6613.119"
    )
    val firefoxBuilds = listOf("134.0.2", "133.0.3", "132.0.2", "131.0.3")

    fun isMobileOs(os: String?): Boolean = os == "Android" || os == "iPhone" || os == "iPad"
    fun deviceTypeFor(os: String?): String = when (os) {
        "Android", "iPhone" -> "mobile"
        "iPad" -> "tablet"
        else -> "desktop"
    }

    fun platformFor(os: String?): String = when (os) {
        "Windows 11", "Windows 10" -> "Win32"
        "macOS" -> "MacIntel"
        "Linux" -> "Linux x86_64"
        "Android" -> "Linux armv81"
        "iPhone" -> "iPhone"
        "iPad" -> "iPad"
        else -> "Win32"
    }

    fun newSeed(): Int = UUID.randomUUID().hashCode() xor (System.nanoTime() and 0x7fffffff).toInt()

    /** Regenerate a coherent identity in place. proxyCountryCode steers locale when set. */
    fun regenerate(fp: FingerprintConfig, os: String, proxyCountryCode: String?, browserKind: String, random: Random = Random.Default) {
        fp.os = os
        fp.deviceType = deviceTypeFor(os)
        fp.seed = newSeed()

        val screenPool = screens[os] ?: screens.getValue("Android")
        val screen = screenPool[random.nextInt(screenPool.size)]
        fp.screenW = screen.w; fp.screenH = screen.h; fp.pixelRatio = screen.dpr
        fp.availW = screen.w
        fp.availH = screen.h - if (fp.deviceType == "mobile") 0 else 48
        fp.colorDepth = 24

        val gpuPool = gpus[os] ?: gpus.getValue("Windows 11")
        val pick = if (gpuPool.size == 1) 0 else random.nextInt(gpuPool.size)
        // Android/iPhone table wins when the device is known — the GPU must be the real one.
        var gpu = gpuPool[pick]
        if (os == "Android") {
            androidDevices["${screen.w}x${screen.h}"]?.let { gpu = it.third }
        }
        fp.webglVendor = gpu.first
        fp.webglRenderer = gpu.second

        fp.cores = if (fp.deviceType == "mobile") listOf(6, 8, 8, 8)[random.nextInt(4)] else coreOptions[random.nextInt(coreOptions.size - 2)]
        fp.memory = memoryOptions[random.nextInt(memoryOptions.size)]
        fp.touchPoints = if (fp.deviceType == "desktop") 0 else 5

        // Locale follows the egress IP when a proxy country is known; otherwise a sane default.
        val locale = proxyCountryCode?.uppercase()?.let { countryLocale[it] }
        fp.timezone = locale?.first ?: timezones[random.nextInt(timezones.size)]
        fp.language = locale?.second ?: languages[random.nextInt(languages.size)]
        fp.acceptLanguage = "${fp.language},${fp.language.substringBefore('-')};q=0.9,en;q=0.8"

        osAndroidVersion(os, screen)?.let { fp.androidVersion = it }
        iosVersionOf(os, screen)?.let { fp.iosVersion = it }

        fp.browserVersion = buildFor(browserKind, random)
    }

    private fun osAndroidVersion(os: String, screen: ScreenTriple): String? =
        if (os == "Android") androidDevices["${screen.w}x${screen.h}"]?.second ?: "15" else null

    private fun iosVersionOf(os: String, screen: ScreenTriple): String? =
        if (os == "iPhone" || os == "iPad") iosDevices["${screen.w}x${screen.h}"]?.third ?: "26.1" else null

    fun iosDeviceName(os: String, w: Int, h: Int): String? =
        if (os == "iPhone" || os == "iPad") iosDevices["${w}x${h}"]?.first else null

    fun androidDeviceName(w: Int, h: Int): String? = androidDevices["${w}x${h}"]?.first

    fun buildFor(browserKind: String, random: Random = Random.Default): String =
        if (browserKind == "firefox") firefoxBuilds[random.nextInt(firefoxBuilds.size)]
        else chromiumBuilds[random.nextInt(chromiumBuilds.size)]

    private fun majorOf(build: String): String = build.substringBefore('.')

    /** The full user agent for a config, including brand and OS tokens. */
    fun userAgent(fp: FingerprintConfig, browserKind: String): String {
        if (fp.userAgentOverride.isNotBlank()) return fp.userAgentOverride.trim()
        val brand = browserKind.ifBlank { "chrome" }.lowercase()
        val chromiumMajor = majorOf(fp.browserVersion)
        val firefoxMajor = majorOf(fp.browserVersion)
        return when (fp.os) {
            "Windows 11", "Windows 10" -> when (brand) {
                "firefox" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:$firefoxMajor.0) Gecko/20100101 Firefox/$firefoxMajor.0"
                "edge" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Safari/537.36 Edg/$chromiumMajor.0.0.0"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Safari/537.36"
            }
            "macOS" -> when (brand) {
                "firefox" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:$firefoxMajor.0) Gecko/20100101 Firefox/$firefoxMajor.0"
                "edge" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Safari/537.36 Edg/$chromiumMajor.0.0.0"
                else -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Safari/537.36"
            }
            "Linux" -> when (brand) {
                "firefox" -> "Mozilla/5.0 (X11; Linux x86_64; rv:$firefoxMajor.0) Gecko/20100101 Firefox/$firefoxMajor.0"
                else -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Safari/537.36"
            }
            "Android" -> {
                val av = if (fp.androidVersion == "auto" || fp.androidVersion.isBlank()) "16" else fp.androidVersion
                val model = androidDeviceName(fp.screenW, fp.screenH) ?: "Pixel 9"
                when (brand) {
                    "firefox" -> "Mozilla/5.0 (Android $av; Mobile; rv:$firefoxMajor.0) Gecko/$firefoxMajor.0 Firefox/$firefoxMajor.0"
                    "edge" -> "Mozilla/5.0 (Linux; Android $av; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Mobile Safari/537.36 EdgA/$chromiumMajor.0.0.0"
                    else -> "Mozilla/5.0 (Linux; Android $av; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Mobile Safari/537.36"
                }
            }
            "iPhone", "iPad" -> {
                val token = fp.iosVersion.replace('.', '_').ifBlank { "18_3" }
                val device = if (fp.os == "iPhone") "iPhone" else "iPad"
                val cpu = if (fp.os == "iPhone") "CPU iPhone OS $token" else "CPU OS $token"
                val safari = fp.iosVersion.ifBlank { "18.3" }
                when (brand) {
                    "firefox" -> "Mozilla/5.0 ($device; $cpu like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/$firefoxMajor.0 Mobile/15E148 Safari/605.1.15"
                    "edge" -> "Mozilla/5.0 ($device; $cpu like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) EdgiOS/$chromiumMajor.0.0.0 Mobile/15E148 Safari/605.1.15"
                    "chrome" -> "Mozilla/5.0 ($device; $cpu like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/$chromiumMajor.0.0.0 Mobile/15E148 Safari/605.1.15"
                    else -> "Mozilla/5.0 ($device; $cpu like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/$safari Mobile/15E148 Safari/604.1"
                }
            }
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Safari/537.36"
        }
    }

    /** A 12-surface signature used to keep profiles from being identical twins. */
    fun signature(fp: FingerprintConfig): String = listOf(
        fp.os, fp.screenW, fp.screenH, fp.pixelRatio, fp.webglRenderer, fp.cores,
        fp.memory, fp.touchPoints, fp.timezone, fp.language, fp.browserVersion, fp.seed
    ).joinToString("|")

    /** Re-roll noise seeds until the signature differs from every existing one (bounded). */
    fun ensureUnique(fp: FingerprintConfig, existing: Collection<String>) {
        var tries = 0
        while (tries++ < 32 && existing.contains(signature(fp))) {
            fp.seed = newSeed()
        }
    }

    /** Manual-mode consistency checker: contradictory picks inflate detection risk. */
    fun warnings(fp: FingerprintConfig, browserKind: String): List<FingerprintWarning> {
        val out = mutableListOf<FingerprintWarning>()
        val desktop = fp.os == "Windows 11" || fp.os == "Windows 10" || fp.os == "macOS" || fp.os == "Linux"
        if (desktop && fp.touchPoints != 0) out += FingerprintWarning("Desktop OS with touch points > 0 — most desktops report 0", "high")
        if (!desktop && fp.touchPoints == 0) out += FingerprintWarning("${fp.os} with 0 touch points — mobile devices report 5", "high")
        if (fp.memory !in memoryOptions) out += FingerprintWarning("deviceMemory ${fp.memory}GB is not a Chrome-advertised power of two", "medium")
        if (fp.availW > fp.screenW) out += FingerprintWarning("availWidth wider than screen — contradiction", "high")
        if (fp.availH > fp.screenH) out += FingerprintWarning("availHeight taller than screen — contradiction", "high")
        if (fp.os == "iPhone" || fp.os == "iPad") {
            if (!fp.webglRenderer.contains("Apple GPU")) out += FingerprintWarning("iOS profiles always report the bare Apple GPU renderer", "high")
        }
        if (fp.os == "Android" && fp.webglRenderer.contains("Direct3D")) out += FingerprintWarning("Android with a Direct3D renderer string — never happens", "high")
        if (fp.os.startsWith("Windows") && fp.webglRenderer.contains("OpenGL ES")) out += FingerprintWarning("Windows with an OpenGL ES renderer string — never happens", "high")
        if (browserKind.lowercase() == "firefox" && fp.browserVersion.split(".").firstOrNull()?.toIntOrNull()?.let { it < 100 } == true)
            out += FingerprintWarning("Firefox ${fp.browserVersion} is below every supported channel", "medium")
        if (fp.userAgentOverride.isNotBlank()) out += FingerprintWarning("Custom user agent overrides the generated identity — contradictions are on you", "medium")
        return out
    }
}
