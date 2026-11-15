package com.joebrowser.app.fingerprint

import com.joebrowser.app.data.BrowserEngine
import com.joebrowser.app.data.FingerprintConfig
import com.joebrowser.app.data.FingerprintWarning
import com.joebrowser.app.data.OsCatalog
import java.util.UUID
import kotlin.random.Random

/**
 * Generates technically self-consistent fingerprint identities.
 *
 * The rule the engine enforces: every observable surface must agree. A Windows identity
 * gets a Direct3D renderer, zero touch points and a desktop viewport; an Android identity
 * gets an Adreno/Mali renderer, five touch points and a phone viewport whose DPR matches
 * the panel. Contradictions are what detector services flag, so the generator never
 * produces them, and the manual editor surfaces [warnings] when a user forces one.
 */
object FingerprintFactory {

    // ------------------------------------------------------------ vocabularies

    val osList: List<String> = OsCatalog.all

    val timezones = listOf(
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Toronto",
        "America/Sao_Paulo", "America/Mexico_City", "America/Bogota", "America/Argentina/Buenos_Aires",
        "Europe/London", "Europe/Dublin", "Europe/Lisbon", "Europe/Madrid", "Europe/Paris", "Europe/Berlin",
        "Europe/Rome", "Europe/Amsterdam", "Europe/Brussels", "Europe/Vienna", "Europe/Zurich", "Europe/Warsaw",
        "Europe/Stockholm", "Europe/Oslo", "Europe/Copenhagen", "Europe/Helsinki", "Europe/Kyiv", "Europe/Moscow",
        "Europe/Istanbul", "Asia/Dhaka", "Asia/Karachi", "Asia/Kolkata", "Asia/Dubai", "Asia/Riyadh",
        "Asia/Jerusalem", "Asia/Bangkok", "Asia/Jakarta", "Asia/Manila", "Asia/Singapore", "Asia/Kuala_Lumpur",
        "Asia/Hong_Kong", "Asia/Shanghai", "Asia/Tokyo", "Asia/Seoul", "Australia/Sydney", "Pacific/Auckland",
        "Africa/Cairo", "Africa/Lagos", "Africa/Johannesburg"
    )

    val languages = listOf(
        "en-US", "en-GB", "en-CA", "en-AU", "en-IN", "de-DE", "fr-FR", "es-ES", "es-MX", "pt-BR", "pt-PT",
        "it-IT", "nl-NL", "pl-PL", "sv-SE", "da-DK", "nb-NO", "fi-FI", "tr-TR", "ru-RU", "uk-UA", "ro-RO",
        "el-GR", "ar-SA", "he-IL", "hi-IN", "ja-JP", "ko-KR", "zh-CN", "zh-HK", "id-ID", "th-TH", "vi-VN",
        "ms-MY", "bn-BD"
    )

    val coreOptions = listOf(2, 4, 6, 8, 10, 12, 16)

    /** Chrome rounds deviceMemory to a power of two and caps it at 8 — anything bigger is a tell. */
    val memoryOptions = listOf(2, 4, 8)

    val screenPresets: List<Triple<Int, Int, Double>> = listOf(
        Triple(360, 740, 3.0), Triple(393, 851, 2.75), Triple(411, 915, 2.625),
        Triple(412, 915, 3.5), Triple(427, 952, 3.0), Triple(480, 1016, 2.5)
    )

    /** Country ISO code -> (IANA timezone, language). Keeps locale glued to the exit IP. */
    val countryLocale: Map<String, Pair<String, String>> = mapOf(
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

    // -------------------------------------------------------------- hardware

    private data class ScreenTriple(val w: Int, val h: Int, val dpr: Double)

    private val screens: Map<String, List<ScreenTriple>> = mapOf(
        "Android" to listOf(
            ScreenTriple(411, 915, 2.625), ScreenTriple(427, 952, 3.0), ScreenTriple(411, 891, 3.5),
            ScreenTriple(393, 851, 2.75), ScreenTriple(360, 780, 3.0), ScreenTriple(412, 915, 3.5)
        ),
        "iPhone" to listOf(
            ScreenTriple(393, 852, 3.0), ScreenTriple(390, 844, 3.0), ScreenTriple(430, 932, 3.0),
            ScreenTriple(420, 912, 3.0), ScreenTriple(375, 812, 3.0)
        ),
        "iPad" to listOf(
            ScreenTriple(820, 1180, 2.0), ScreenTriple(1024, 1366, 2.0), ScreenTriple(1194, 834, 2.0),
            ScreenTriple(744, 1133, 2.0)
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
        )
    )

    private val gpus: Map<String, List<Pair<String, String>>> = mapOf(
        "Android" to listOf(
            "Google Inc. (Qualcomm)" to "ANGLE (Qualcomm, Adreno (TM) 830, OpenGL ES 3.2)",
            "Google Inc. (ARM)" to "ANGLE (ARM, Immortalis-G720, OpenGL ES 3.2)",
            "Google Inc. (ARM)" to "ANGLE (ARM, Mali-G720, OpenGL ES 3.2)",
            "Google Inc. (Qualcomm)" to "ANGLE (Qualcomm, Adreno (TM) 750, OpenGL ES 3.2)",
            "Google Inc. (ARM)" to "ANGLE (ARM, Mali-G715, OpenGL ES 3.2)",
            "Google Inc. (Qualcomm)" to "ANGLE (Qualcomm, Adreno (TM) 730, OpenGL ES 3.2)",
            "Google Inc. (ARM)" to "ANGLE (ARM, Mali-G57, OpenGL ES 3.2)"
        ),
        "iPhone" to listOf("Apple Inc." to "Apple GPU"),
        "iPad" to listOf("Apple Inc." to "Apple GPU"),
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
        )
    )

    /** Android screen size -> (model, API level, GPU) so viewport and silicon always match. */
    private val androidDevices: Map<String, Triple<String, String, Pair<String, String>>> = mapOf(
        "411x915" to Triple("Pixel 9", "16", gpus.getValue("Android")[0]),
        "427x952" to Triple("Pixel 9 Pro XL", "16", gpus.getValue("Android")[0]),
        "411x891" to Triple("SM-S931B", "16", gpus.getValue("Android")[5]),
        "393x851" to Triple("SM-S908B", "15", gpus.getValue("Android")[5]),
        "360x780" to Triple("SM-A155F", "15", gpus.getValue("Android")[6]),
        "412x915" to Triple("Pixel 9 Pro", "16", gpus.getValue("Android")[0])
    )

    private val iosDevices: Map<String, Triple<String, String, String>> = mapOf(
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

    fun buildsFor(engine: BrowserEngine): List<String> =
        if (engine == BrowserEngine.FIREFOX) firefoxBuilds else chromiumBuilds

    /** Every renderer string the OS pool can produce — drives the editor's GPU dropdown. */
    fun renderersForOs(os: String?): List<String> {
        val pool = gpus[os] ?: gpus.getValue("Windows 11")
        return pool.map { it.second }.distinct()
    }

    /** Inverse of the GPU table: the vendor string that pairs with a renderer. */
    fun vendorForRenderer(renderer: String): String {
        gpus.values.forEach { pool ->
            pool.firstOrNull { it.second == renderer }?.let { return it.first }
        }
        return when {
            renderer.contains("NVIDIA") -> "Google Inc. (NVIDIA)"
            renderer.contains("AMD") -> "Google Inc. (AMD)"
            renderer.contains("Intel") -> "Google Inc. (Intel)"
            renderer.contains("Apple") -> "Google Inc. (Apple)"
            renderer.contains("Adreno") -> "Google Inc. (Qualcomm)"
            renderer.contains("Mali") || renderer.contains("Immortalis") -> "Google Inc. (ARM)"
            else -> "Google Inc."
        }
    }

    // ------------------------------------------------------------ generation

    fun newSeed(): Int = UUID.randomUUID().hashCode() xor (System.nanoTime() and 0x7fffffff).toInt()

    fun isMobileOs(os: String?): Boolean = OsCatalog.isMobile(os ?: "")

    fun platformFor(os: String?): String = when (os) {
        "Windows 11", "Windows 10" -> "Win32"
        "macOS" -> "MacIntel"
        "Linux" -> "Linux x86_64"
        "Android" -> "Linux armv81"
        "iPhone" -> "iPhone"
        "iPad" -> "iPad"
        else -> "Win32"
    }

    /**
     * Regenerates a coherent identity in place.
     *
     * @param proxyCountryCode when supplied (and auto-match is on) the timezone and language
     *   are pinned to that country so the locale never contradicts the exit IP.
     */
    fun regenerate(
        fp: FingerprintConfig,
        os: String,
        proxyCountryCode: String?,
        engine: BrowserEngine,
        random: Random = Random.Default
    ) {
        val resolvedOs = os.takeIf { it in osList } ?: "Android"
        fp.os = resolvedOs
        fp.deviceType = OsCatalog.deviceType(resolvedOs)
        fp.seed = newSeed()

        // ---- screen + DPR
        val pool = screens[resolvedOs] ?: screens.getValue("Android")
        val screen = pool[random.nextInt(pool.size)]
        fp.screenW = screen.w
        fp.screenH = screen.h
        fp.pixelRatio = screen.dpr
        fp.availW = screen.w
        // Desktops lose ~48 CSS px to the OS chrome; mobile reports the full viewport.
        fp.availH = if (fp.deviceType == "mobile") screen.h else screen.h - 48
        fp.colorDepth = 24

        // ---- GPU: the device table wins on Android so silicon and panel agree
        val gpuPool = gpus[resolvedOs] ?: gpus.getValue("Windows 11")
        var gpu = gpuPool[if (gpuPool.size == 1) 0 else random.nextInt(gpuPool.size)]
        if (resolvedOs == "Android") {
            androidDevices["${screen.w}x${screen.h}"]?.let { gpu = it.third }
        }
        fp.webglVendor = gpu.first
        fp.webglRenderer = gpu.second

        // ---- hardware
        fp.cores = if (fp.deviceType == "mobile") listOf(6, 8, 8, 8)[random.nextInt(4)]
        else coreOptions[random.nextInt(coreOptions.size - 2)]
        fp.memory = memoryOptions[random.nextInt(memoryOptions.size)]
        fp.touchPoints = if (fp.deviceType == "desktop") 0 else 5

        // ---- device model
        fp.deviceModel = when (resolvedOs) {
            "Android" -> androidDeviceName(screen.w, screen.h) ?: "Pixel 9"
            "iPhone", "iPad" -> iosDeviceName(resolvedOs, screen.w, screen.h) ?: "iPhone 16"
            else -> ""
        }

        // ---- locale: follows the egress IP when known
        val locale = proxyCountryCode?.uppercase()?.let { countryLocale[it] }
        fp.timezone = locale?.first ?: timezones[random.nextInt(timezones.size)]
        fp.language = locale?.second ?: languages[random.nextInt(languages.size)]
        fp.acceptLanguage = acceptLanguageFor(fp.language)

        osAndroidVersion(resolvedOs, screen)?.let { fp.androidVersion = it }
        iosVersionOf(resolvedOs, screen)?.let { fp.iosVersion = it }

        fp.browserVersion = buildFor(engine, random)
    }

    fun acceptLanguageFor(language: String): String {
        val base = language.substringBefore('-')
        return if (base == "en") "$language,en;q=0.9" else "$language,$base;q=0.9,en;q=0.8"
    }

    /** Applies the proxy country to an existing identity without re-rolling hardware. */
    fun applyProxyLocale(fp: FingerprintConfig, countryCode: String?) {
        val locale = countryCode?.uppercase()?.let { countryLocale[it] } ?: return
        fp.timezone = locale.first
        fp.language = locale.second
        fp.acceptLanguage = acceptLanguageFor(fp.language)
    }

    private fun osAndroidVersion(os: String, screen: ScreenTriple): String? =
        if (os == "Android") androidDevices["${screen.w}x${screen.h}"]?.second ?: "15" else null

    private fun iosVersionOf(os: String, screen: ScreenTriple): String? =
        if (os == "iPhone" || os == "iPad") iosDevices["${screen.w}x${screen.h}"]?.third ?: "26.1" else null

    fun iosDeviceName(os: String, w: Int, h: Int): String? =
        if (os == "iPhone" || os == "iPad") iosDevices["${w}x${h}"]?.first else null

    fun androidDeviceName(w: Int, h: Int): String? = androidDevices["${w}x${h}"]?.first

    fun buildFor(engine: BrowserEngine, random: Random = Random.Default): String {
        val pool = buildsFor(engine)
        return pool[random.nextInt(pool.size)]
    }

    /** Picks the newest known build for an engine (used when only the brand changes). */
    fun latestBuildFor(engine: BrowserEngine): String = buildsFor(engine).first()

    // ------------------------------------------------------------------- UA

    private fun majorOf(build: String): String = build.substringBefore('.')

    /** The full user agent for a config, including brand and OS tokens. */
    fun userAgent(fp: FingerprintConfig, engine: BrowserEngine): String {
        if (fp.userAgentOverride.isNotBlank()) return fp.userAgentOverride.trim()
        val chromium = majorOf(fp.browserVersion)
        val firefox = majorOf(fp.browserVersion)
        return when (fp.os) {
            "Windows 11", "Windows 10" -> when (engine) {
                BrowserEngine.FIREFOX -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:$firefox.0) Gecko/20100101 Firefox/$firefox.0"
                BrowserEngine.EDGE -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Safari/537.36 Edg/$chromium.0.0.0"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Safari/537.36"
            }
            "macOS" -> when (engine) {
                BrowserEngine.FIREFOX -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:$firefox.0) Gecko/20100101 Firefox/$firefox.0"
                BrowserEngine.EDGE -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Safari/537.36 Edg/$chromium.0.0.0"
                else -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Safari/537.36"
            }
            "Linux" -> when (engine) {
                BrowserEngine.FIREFOX -> "Mozilla/5.0 (X11; Linux x86_64; rv:$firefox.0) Gecko/20100101 Firefox/$firefox.0"
                else -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Safari/537.36"
            }
            "Android" -> {
                val version = fp.androidVersion.takeIf { it.isNotBlank() && it != "auto" }
                    ?: androidVersionFor(fp.screenW, fp.screenH)
                val model = fp.deviceModel.takeIf { it.isNotBlank() }
                    ?: androidDeviceName(fp.screenW, fp.screenH) ?: "Pixel 9"
                when (engine) {
                    BrowserEngine.FIREFOX -> "Mozilla/5.0 (Android $version; Mobile; rv:$firefox.0) Gecko/$firefox.0 Firefox/$firefox.0"
                    BrowserEngine.EDGE -> "Mozilla/5.0 (Linux; Android $version; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Mobile Safari/537.36 EdgA/$chromium.0.0.0"
                    BrowserEngine.BRAVE -> "Mozilla/5.0 (Linux; Android $version; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Mobile Safari/537.36"
                    else -> "Mozilla/5.0 (Linux; Android $version; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Mobile Safari/537.36"
                }
            }
            "iPhone", "iPad" -> {
                val token = (fp.iosVersion.takeIf { it.isNotBlank() && it != "auto" } ?: "18_3").replace('.', '_')
                val device = if (fp.os == "iPhone") "iPhone" else "iPad"
                val cpu = if (fp.os == "iPhone") "CPU iPhone OS $token" else "CPU OS $token"
                val safari = fp.iosVersion.takeIf { it.isNotBlank() && it != "auto" } ?: "18.3"
                when (engine) {
                    BrowserEngine.FIREFOX -> "Mozilla/5.0 ($device; $cpu like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/$firefox.0 Mobile/15E148 Safari/605.1.15"
                    BrowserEngine.EDGE -> "Mozilla/5.0 ($device; $cpu like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) EdgiOS/$chromium.0.0.0 Mobile/15E148 Safari/605.1.15"
                    BrowserEngine.CHROME -> "Mozilla/5.0 ($device; $cpu like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/$chromium.0.0.0 Mobile/15E148 Safari/605.1.15"
                    else -> "Mozilla/5.0 ($device; $cpu like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/$safari Mobile/15E148 Safari/604.1"
                }
            }
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromium.0.0.0 Safari/537.36"
        }
    }

    private fun androidVersionFor(w: Int, h: Int): String =
        androidDevices["${w}x${h}"]?.second ?: "15"

    // ----------------------------------------------------------- consistency

    /** A 12-surface signature used to keep profiles from being identical twins. */
    fun signature(fp: FingerprintConfig): String = listOf(
        fp.os, fp.screenW, fp.screenH, fp.pixelRatio, fp.webglRenderer, fp.cores,
        fp.memory, fp.touchPoints, fp.timezone, fp.language, fp.browserVersion, fp.seed
    ).joinToString("|")

    /** Re-rolls the noise seed until the signature differs from every existing one (bounded). */
    fun ensureUnique(fp: FingerprintConfig, existing: Collection<String>) {
        var tries = 0
        while (tries++ < 32 && existing.contains(signature(fp))) {
            fp.seed = newSeed()
        }
    }

    /** Manual-mode consistency checker: contradictory picks inflate detection risk. */
    fun warnings(fp: FingerprintConfig, engine: BrowserEngine): List<FingerprintWarning> {
        val out = mutableListOf<FingerprintWarning>()
        val desktop = !isMobileOs(fp.os)

        if (desktop && fp.touchPoints != 0) {
            out += FingerprintWarning("Desktop OS with touch points — real desktops report 0", "high")
        }
        if (!desktop && fp.touchPoints == 0) {
            out += FingerprintWarning(fp.os + " with 0 touch points — mobile reports 5", "high")
        }
        if (fp.memory !in memoryOptions) {
            out += FingerprintWarning("deviceMemory ${fp.memory} GB is not a Chrome-advertised power of two", "medium")
        }
        if (fp.availW > fp.screenW) out += FingerprintWarning("availWidth wider than screen", "high")
        if (fp.availH > fp.screenH) out += FingerprintWarning("availHeight taller than screen", "high")
        if (fp.screenW > fp.screenH) out += FingerprintWarning("Landscape viewport on a mobile identity looks unusual", "low")
        if (fp.os == "iPhone" || fp.os == "iPad") {
            if (!fp.webglRenderer.contains("Apple GPU")) {
                out += FingerprintWarning("iOS always reports the bare Apple GPU renderer", "high")
            }
        }
        if (fp.os == "Android" && fp.webglRenderer.contains("Direct3D")) {
            out += FingerprintWarning("Android with a Direct3D renderer — impossible", "high")
        }
        if (fp.os.startsWith("Windows") && fp.webglRenderer.contains("OpenGL ES")) {
            out += FingerprintWarning("Windows with an OpenGL ES renderer — impossible", "high")
        }
        if (engine == BrowserEngine.FIREFOX && fp.os == "Android") {
            out += FingerprintWarning("Gecko identities are UA-level only on Android WebView", "low")
        }
        if (engine == BrowserEngine.FIREFOX && !fp.browserVersion.startsWith("1")) {
            out += FingerprintWarning("Firefox build ${fp.browserVersion} does not look like a Gecko version", "medium")
        }
        if (fp.userAgentOverride.isNotBlank()) {
            out += FingerprintWarning("Custom user agent overrides the generated identity", "medium")
        }
        if (fp.webrtcMode == "public") {
            out += FingerprintWarning("WebRTC set to report the public IP — it can bypass the proxy", "medium")
        }
        if (fp.geoMode == "custom" && (fp.geoLat.toDoubleOrNull() == null || fp.geoLng.toDoubleOrNull() == null)) {
            out += FingerprintWarning("Custom geolocation is not a valid coordinate pair", "medium")
        }
        return out
    }

    /** 0–100 coherence score for the editor badge (100 = no findings). */
    fun coherenceScore(warnings: List<FingerprintWarning>): Int {
        var penalty = 0
        for (warning in warnings) {
            penalty += when (warning.severity) {
                "high" -> 30
                "medium" -> 14
                else -> 6
            }
        }
        return (100 - penalty).coerceIn(0, 100)
    }
}
