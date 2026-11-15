package com.joebrowser.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.browser.BrowserKernel
import com.joebrowser.app.browser.HumanPaste
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.BrowserEngine
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.FingerprintConfig
import com.joebrowser.app.data.OsCatalog
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.data.ProfileStorage
import com.joebrowser.app.data.ProxyConfig
import com.joebrowser.app.data.ToolConfig
import com.joebrowser.app.databinding.ActivityProfileEditorBinding
import com.joebrowser.app.databinding.RowSwitchBinding
import com.joebrowser.app.fingerprint.FingerprintFactory
import com.joebrowser.app.proxy.ProxyTestResult
import com.joebrowser.app.proxy.ProxyTester

/**
 * Profile editor — the single place where a profile's identity, proxy and tooling are defined.
 *
 * Design notes
 * ------------
 *  - The activity owns one mutable [Profile] working copy; every control writes into it as the
 *    user interacts, so "Save" is only a persist step and no control can be forgotten.
 *  - Changing the OS re-derives the identity through [FingerprintFactory], so the user cannot
 *    end up with a Windows panel driven by a Mali GPU. The coherence badge recomputes on every
 *    change and lists exactly what is contradictory.
 *  - Numeric fields are bounded by InputFilters ([UiKit.rangeFilter]) so impossible values
 *    cannot be typed; the DPR field works in tenths to keep a decimal keyboard usable.
 *  - Proxy checks run through the real relay path ([ProxyTester.test]) off the main thread and
 *    write the exit IP/country back into the profile, then re-align the locale when
 *    auto-match is on — which is the whole point of matching a proxy.
 *  - Storage size and isolation mode are read from [ProfileStorage]/[BrowserKernel], so the
 *    user can see that each profile really owns its directory and WebView bucket.
 */
class ProfileEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditorBinding
    private var editingId: String? = null
    private lateinit var profile: Profile
    private var dirty = false
    private var saving = false

    private val engines = BrowserEngine.entries
    private val speedOptions = HumanPaste.speeds
    private val watchIntervals = listOf(30, 60, 120, 300, 600)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_JoeBrowser)
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val requestedEngine = BrowserEngine.of(intent.getStringExtra(EXTRA_ENGINE))
        editingId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }

        binding.editorTitle.setText(
            if (editingId == null) R.string.editor_title_new else R.string.editor_title_edit
        )
        binding.btnEditorDelete.visibility = if (editingId == null) View.GONE else View.VISIBLE

        Async.compute({ loadProfileSync(requestedEngine) }) { loaded ->
            if (isFinishing || isDestroyed) return@compute
            profile = loaded ?: Profile(browserKind = requestedEngine.id)
            if (editingId != null && profile.name.isBlank() && profile.openCount == 0 && profile.lastOpened == 0L) {
                // Row disappeared between list and edit
                editingId = null
                binding.editorTitle.setText(R.string.editor_title_new)
                binding.btnEditorDelete.visibility = View.GONE
            }
            setupDropdowns()
        setupTemplates()
            configureRows()
            populate()
            bindActions()
            updateFingerprintSummary()
            updateProxyVisibility()
            updateGeoVisibility()
            updateWatchVisibility()
            refreshStorage()
        }
    }

    // ------------------------------------------------------------------- load

    private fun loadProfileSync(requestedEngine: BrowserEngine): Profile {
        val existingId = editingId
        if (existingId != null) {
            val existing = runCatching { Database.get(this).getProfile(existingId) }.getOrNull()
            if (existing != null) return existing
        }
        val fresh = Profile(browserKind = requestedEngine.id)
        FingerprintFactory.regenerate(fresh.fp, "Android", null, requestedEngine)
        val existing = runCatching {
            Database.get(this).getAllProfiles().map { FingerprintFactory.signature(it.fp) }
        }.getOrDefault(emptyList())
        FingerprintFactory.ensureUnique(fresh.fp, existing)
        fresh.browserVersion = fresh.fp.browserVersion
        fresh.tools = ToolConfig(
            humanPaste = Prefs.humanPasteEnabled(this),
            humanPasteSpeed = Prefs.humanPasteSpeed(this),
            humanPasteJitter = Prefs.humanPasteJitter(this),
            proxyWatch = Prefs.monitorEnabled(this),
            proxyWatchIntervalSec = Prefs.monitorIntervalSec(this),
            unlockSelection = Prefs.unlockSelection(this),
            httpTracker = Prefs.trackerEnabled(this)
        )
        fresh.startUrl = Prefs.defaultStartUrl(this)
        fresh.group = Prefs.defaultGroup(this)
        return fresh
    }

    /** Labels offered by the Status picker; index 0 means "no explicit status" (Active). */
    private var statusOptions: List<String> = emptyList()

    // -------------------------------------------------------------- dropdowns

    private fun setupDropdowns() {
        bindDropdown(binding.editorEngine, engines.map { it.title }) { position ->
            applyEngine(engines[position])
        }
        bindDropdown(binding.editorOs, OsCatalog.all) { position ->
            applyOs(OsCatalog.all[position])
        }
        // Status is a label, not a behaviour: it drives the chip on the profile card and search,
        // so one picker is enough and "Active" is the sensible default for a new profile.
        statusOptions = resources.getStringArray(R.array.editor_status_options).toList()
        bindDropdown(binding.editorStatus, statusOptions) { position ->
            profile.status = if (position == 0) "" else statusOptions[position]
        }
        bindDropdown(
            binding.editorDevicePreset,
            FingerprintFactory.screenPresets.map { "${it.first} × ${it.second} @ ${it.third}x" }
        ) { position ->
            val preset = FingerprintFactory.screenPresets[position]
            binding.editorScreenW.setText(preset.first.toString())
            binding.editorScreenH.setText(preset.second.toString())
            binding.editorDpr.setText((preset.third * 10).toInt().toString())
            profile.fp.manualScreen = true
            markDirty()
            updateFingerprintSummary()
        }
        bindDropdown(binding.editorCores, FingerprintFactory.coreOptions.map { "$it" }) { position ->
            profile.fp.cores = FingerprintFactory.coreOptions[position]
            markDirty()
            updateFingerprintSummary()
        }
        bindDropdown(binding.editorMemory, FingerprintFactory.memoryOptions.map { "$it GB" }) { position ->
            profile.fp.memory = FingerprintFactory.memoryOptions[position]
            markDirty()
            updateFingerprintSummary()
        }
        bindDropdown(binding.editorTimezone, FingerprintFactory.timezones) { position ->
            profile.fp.timezone = FingerprintFactory.timezones[position]
            markDirty()
            updateFingerprintSummary()
        }
        bindDropdown(binding.editorLanguage, FingerprintFactory.languages) { position ->
            profile.fp.language = FingerprintFactory.languages[position]
            profile.fp.acceptLanguage = FingerprintFactory.acceptLanguageFor(profile.fp.language)
            markDirty()
            updateFingerprintSummary()
        }
        bindDropdown(binding.editorProxyType, listOf("HTTP", "HTTPS", "SOCKS5")) { position ->
            profile.proxy.type = when (position) {
                2 -> "socks5"
                1 -> "https"
                else -> "http"
            }
            markDirty()
        }
        bindDropdown(binding.editorWebrtc, FingerprintConfig.WEBRTC_MODES) { position ->
            profile.fp.webrtcMode = FingerprintConfig.WEBRTC_MODES[position]
            markDirty()
            updateFingerprintSummary()
        }
        bindDropdown(binding.editorGeo, FingerprintConfig.GEO_MODES) { position ->
            profile.fp.geoMode = FingerprintConfig.GEO_MODES[position]
            updateGeoVisibility()
            markDirty()
            updateFingerprintSummary()
        }
        bindDropdown(binding.editorPasteSpeed, speedOptions.map { it.label }) { position ->
            profile.tools.humanPasteSpeed = speedOptions[position].id
            markDirty()
        }

        // The GPU list comes from the OS pool, so it is rebuilt whenever the OS changes.
        rebuildGpuDropdown()
    }

    private fun bindDropdown(view: AutoCompleteTextView, items: List<String>, onPick: (Int) -> Unit) {
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items))
        view.setOnItemClickListener { _, _, position, _ -> onPick(position) }
        view.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) runCatching { view.dismissDropDown() }
        }
    }

    private fun rebuildGpuDropdown() {
        val renderers = FingerprintFactory.renderersForOs(profile.fp.os)
        bindDropdown(binding.editorGpuRenderer, renderers) { position ->
            val renderer = renderers[position]
            profile.fp.webglRenderer = renderer
            profile.fp.webglVendor = FingerprintFactory.vendorForRenderer(renderer)
            binding.gpuVendorText.text = profile.fp.webglVendor
            markDirty()
            updateFingerprintSummary()
        }
    }

    // ------------------------------------------------------------------- rows

    private fun configureRows() {
        configureSwitch(binding.rowCanvas, R.string.editor_canvas) { profile.fp.canvasNoise = it }
        configureSwitch(binding.rowAudio, R.string.editor_audio) { profile.fp.audioNoise = it }
        configureSwitch(binding.rowWebgl, R.string.editor_hardware_noise) { profile.fp.hardwareNoise = it }
        configureSwitch(binding.rowFont, R.string.editor_font_noise, R.string.editor_font_noise_hint) {
            profile.fp.fontNoise = it
        }
        configureSwitch(binding.rowMedia, R.string.editor_media_noise) { profile.fp.mediaNoise = it }
        configureSwitch(binding.rowDnt, R.string.editor_dnt) { profile.fp.doNotTrack = it }
        configureSwitch(binding.rowBattery, R.string.editor_battery) { profile.fp.spoofBattery = it }
        configureSwitch(binding.rowUnlock, R.string.tools_unlock) { profile.tools.unlockSelection = it }
        configureSwitch(binding.rowFresh, R.string.editor_fresh, R.string.editor_fresh_hint) {
            profile.freshSession = it
        }
        configureSwitch(binding.rowHuman, R.string.editor_human_paste) { profile.tools.humanPaste = it }
        configureSwitch(binding.rowJitter, R.string.editor_human_jitter) { profile.tools.humanPasteJitter = it }
        configureSwitch(binding.rowWatch, R.string.editor_watch) {
            profile.tools.proxyWatch = it
            updateWatchVisibility()
            // Monitoring alerts are notifications: ask for the permission when the user opts in
            // rather than on app launch.
            if (it) ensureNotificationPermission()
        }

        binding.rowWatchInterval.setOnClickListener {
            val labels = watchIntervals.map { intervalLabel(it) }.toTypedArray()
            val current = watchIntervals.indexOf(profile.tools.proxyWatchIntervalSec).coerceAtLeast(2)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_watch_interval)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    profile.tools.proxyWatchIntervalSec = watchIntervals[which]
                    binding.watchIntervalValue.text =
                        getString(R.string.editor_interval_value, intervalLabel(watchIntervals[which]))
                    markDirty()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun RowSwitchBinding.configureCheck(checked: Boolean) {
        rowSwitch.setOnCheckedChangeListener(null)
        rowSwitch.isChecked = checked
    }

    private fun configureSwitch(
        row: RowSwitchBinding,
        labelRes: Int,
        hintRes: Int? = null,
        onChange: (Boolean) -> Unit
    ) {
        row.rowLabel.setText(labelRes)
        if (hintRes != null) {
            row.rowHint.setText(hintRes)
            row.rowHint.visibility = View.VISIBLE
        }
        row.rowSwitch.isChecked = false
        row.rowSwitch.setOnCheckedChangeListener { _, checked ->
            if (populating) return@setOnCheckedChangeListener
            onChange(checked)
            markDirty()
            updateFingerprintSummary()
        }
        row.root.setOnClickListener {
            row.rowSwitch.toggle()
        }
    }

    // ------------------------------------------------------------------ render

    private fun populate() {
        populating = true
        try {
            val fp = profile.fp
            binding.editorName.setText(profile.name)
            binding.editorGroup.setText(profile.group)
            binding.editorTags.setText(profile.tags)
            binding.editorNotes.setText(profile.notes)
            binding.editorStartUrl.setText(profile.startUrl)
            binding.editorEngine.setText(profile.engine.title, false)
            binding.editorOs.setText(fp.os, false)
            binding.editorStatus.setText(
                profile.status.takeIf { it.isNotBlank() } ?: statusOptions.firstOrNull().orEmpty(),
                false
            )

            binding.editorCores.setText(fp.cores.toString(), false)
            binding.editorMemory.setText(fp.memory.toString() + " GB", false)
            binding.gpuVendorText.text = fp.webglVendor
            binding.editorUserAgent.setText(fp.userAgentOverride)
            binding.editorTimezone.setText(fp.timezone, false)
            binding.editorLanguage.setText(fp.language, false)
            binding.switchAutoMatch.isChecked = fp.autoMatchProxy
            binding.switchAutoMatch.setOnCheckedChangeListener { _, checked ->
                if (populating) return@setOnCheckedChangeListener
                fp.autoMatchProxy = checked
                markDirty()
            }

            binding.rowCanvas.configureCheck(fp.canvasNoise)
            binding.rowAudio.configureCheck(fp.audioNoise)
            binding.rowWebgl.configureCheck(fp.hardwareNoise)
            binding.rowFont.configureCheck(fp.fontNoise)
            binding.rowMedia.configureCheck(fp.mediaNoise)
            binding.rowDnt.configureCheck(fp.doNotTrack)
            binding.rowBattery.configureCheck(fp.spoofBattery)
            binding.rowUnlock.configureCheck(profile.tools.unlockSelection)
            binding.rowFresh.configureCheck(profile.freshSession)
            binding.rowHuman.configureCheck(profile.tools.humanPaste)
            binding.rowJitter.configureCheck(profile.tools.humanPasteJitter)
            binding.rowWatch.configureCheck(profile.tools.proxyWatch)

            binding.switchProxy.isChecked = profile.proxy.enabled
            val proxyLabel = when (profile.proxy.type.lowercase()) {
                "socks5" -> "SOCKS5"
                "https" -> "HTTPS"
                else -> "HTTP"
            }
            binding.editorProxyType.setText(proxyLabel, false)
            binding.editorProxyHost.setText(profile.proxy.host)
            binding.editorProxyPort.setText(
                if (profile.proxy.port > 0) profile.proxy.port.toString() else ""
            )
            binding.editorProxyUser.setText(profile.proxy.user)
            binding.editorProxyPass.setText(profile.proxy.password)

            binding.editorWebrtc.setText(fp.webrtcMode, false)
            binding.editorGeo.setText(fp.geoMode, false)
            binding.editorLat.setText(fp.geoLat)
            binding.editorLng.setText(fp.geoLng)
            binding.editorPasteSpeed.setText(HumanPaste.speedById(profile.tools.humanPasteSpeed).label, false)
            binding.watchIntervalValue.text =
                getString(R.string.editor_interval_value, intervalLabel(profile.tools.proxyWatchIntervalSec))
            binding.proxyStatus.setText(R.string.not_checked)

            populateIdentityFields()
        } finally {
            populating = false
        }

        // Numeric field guards.
        binding.editorScreenW.filters = arrayOf(UiKit.rangeFilter(200, 7680))
        binding.editorScreenH.filters = arrayOf(UiKit.rangeFilter(200, 4320))
        binding.editorProxyPort.filters = arrayOf(UiKit.rangeFilter(1, 65535))

        binding.editorTouchSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val fp = profile.fp
            fp.touchPoints = value.toInt()
            fp.deviceType = if (value.toInt() == 0) "desktop" else OsCatalog.deviceType(fp.os)
            binding.touchLabel.text = getString(R.string.editor_touch) + " · " + value.toInt()
            markDirty()
            updateFingerprintSummary()
        }

        listOf(
            binding.editorName, binding.editorGroup, binding.editorTags, binding.editorNotes,
            binding.editorStartUrl, binding.editorScreenW, binding.editorScreenH, binding.editorDpr,
            binding.editorUserAgent,
            binding.editorProxyHost, binding.editorProxyPort, binding.editorProxyUser,
            binding.editorProxyPass, binding.editorLat, binding.editorLng
        ).forEach { field ->
            field.doAfterTextChanged {
                if (!populating) {
                    markDirty()
                    syncFromInputs()
                }
            }
        }
    }

    private var populating = false

    /** Re-renders only the identity-derived fields (after regenerate / OS change / locale sync). */
    private fun populateIdentityFields() {
        populating = true
        try {
            val fp = profile.fp
            binding.editorScreenW.setText(fp.screenW.toString())
            binding.editorScreenH.setText(fp.screenH.toString())
            binding.editorDpr.setText(formatDpr(fp.pixelRatio))
            binding.editorDevicePreset.setText(
                fp.screenW.toString() + " × " + fp.screenH + " @ " + formatDpr(fp.pixelRatio) + "x", false
            )
            binding.editorTouchSlider.value = fp.touchPoints.coerceIn(0, 10).toFloat()
            binding.touchLabel.text = getString(R.string.editor_touch) + " · " + fp.touchPoints
            binding.editorCores.setText(fp.cores.toString(), false)
            binding.editorMemory.setText(fp.memory.toString() + " GB", false)
            rebuildGpuDropdown()
            binding.editorGpuRenderer.setText(fp.webglRenderer, false)
            binding.gpuVendorText.text = fp.webglVendor
            binding.editorTimezone.setText(fp.timezone, false)
            binding.editorLanguage.setText(fp.language, false)
            updateFingerprintSummary()
        } finally {
            populating = false
        }
    }

    private fun formatDpr(dpr: Double): String {
        val text = String.format(java.util.Locale.US, "%.3f", dpr).trimEnd('0').trimEnd('.')
        return if (text.contains('.')) text else "$text.0"
    }

    // ---------------------------------------------------------------- actions

    private fun bindActions() {
        binding.btnEditorBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnRegenerate.setOnClickListener { regenerateIdentity() }
        installBackGuard()
        binding.btnEditorSave.setOnClickListener { save(thenLaunch = false) }
        binding.btnEditorSaveOpen.setOnClickListener { save(thenLaunch = true) }
        binding.btnEditorDelete.setOnClickListener { confirmDelete() }
        binding.switchProxy.setOnCheckedChangeListener { _, checked ->
            profile.proxy.type = when {
                !checked -> "none"
                profile.proxy.type == "none" -> "http"
                else -> profile.proxy.type
            }
            updateProxyVisibility()
            markDirty()
        }
        binding.btnProxyCheck.setOnClickListener { checkProxy() }
        binding.btnProxyLibrary.setOnClickListener { pickFromLibrary() }
        binding.btnClearData.setOnClickListener { confirmClearData() }
    }

    private fun applyEngine(engine: BrowserEngine) {
        if (profile.engine == engine) return
        profile.browserKind = engine.id
        // Build numbers are engine-specific (Gecko 134.0.2 vs Blink 134.0.6998.89).
        // On iOS the browser version tracks the iOS release, not a desktop Chromium build.
        profile.fp.browserVersion = if (profile.fp.os == "iPhone" || profile.fp.os == "iPad") {
            FingerprintFactory.buildForIos(engine, profile.fp.iosVersion)
        } else {
            FingerprintFactory.latestBuildFor(engine)
        }
        profile.browserVersion = profile.fp.browserVersion
        markDirty()
        updateFingerprintSummary()
    }

    /**
     * Quick-start identity templates (AdsPower/GoLogin-style one-tap device presets).
     * Visible for new profiles only; applying one sets engine + OS + locale/timezone coherently.
     */
    private data class IdentityTemplate(
        val label: String,
        val engine: BrowserEngine,
        val os: String,
        val timezone: String,
        val language: String
    )

    private val identityTemplates = listOf(
        IdentityTemplate("US · Win11 · Chrome", BrowserEngine.CHROME, "Windows 11", "America/New_York", "en-US"),
        IdentityTemplate("UK · Win11 · Edge", BrowserEngine.EDGE, "Windows 11", "Europe/London", "en-GB"),
        IdentityTemplate("DE · Win11 · Firefox", BrowserEngine.FIREFOX, "Windows 11", "Europe/Berlin", "de-DE"),
        IdentityTemplate("US · iPhone · Chrome", BrowserEngine.CHROME, "iPhone", "America/New_York", "en-US"),
        IdentityTemplate("US · Android · Brave", BrowserEngine.BRAVE, "Android", "America/Los_Angeles", "en-US"),
        IdentityTemplate("BD · Android · Chrome", BrowserEngine.CHROME, "Android", "Asia/Dhaka", "bn-BD"),
    )

    private fun setupTemplates() {
        if (editingId != null) return  // templates are a new-profile convenience
        binding.templateScroll.visibility = View.VISIBLE
        identityTemplates.forEach { template ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = template.label
                isCheckable = false
                isClickable = true
                setOnClickListener { applyTemplate(template) }
            }
            binding.templateChips.addView(chip)
        }
    }

    private fun applyTemplate(template: IdentityTemplate) {
        // profile.engine is a read-only view over browserKind; applyEngine performs the swap
        // (engine id, pinned build, iOS build selection) and refreshes the summary.
        binding.editorEngine.setText(template.engine.title, false)
        applyEngine(template.engine)
        applyOs(template.os)
        binding.editorOs.setText(template.os, false)
        profile.fp.timezone = template.timezone
        binding.editorTimezone.setText(template.timezone, false)
        profile.fp.language = template.language
        profile.fp.acceptLanguage = FingerprintFactory.acceptLanguageFor(template.language)
        binding.editorLanguage.setText(template.language, false)
        markDirty()
        updateFingerprintSummary()
        Async.toast(this, getString(R.string.editor_template_applied, template.label))
    }

    private fun applyOs(os: String) {
        if (profile.fp.os == os) return
        FingerprintFactory.regenerate(profile.fp, os, proxyCountry(), profile.engine)
        profile.browserVersion = profile.fp.browserVersion
        populateIdentityFields()
        markDirty()
    }

    private fun regenerateIdentity() {
        FingerprintFactory.regenerate(profile.fp, profile.fp.os, proxyCountry(), profile.engine)
        profile.browserVersion = profile.fp.browserVersion
        populateIdentityFields()
        markDirty()
        Async.toast(this, getString(R.string.editor_regenerated))
    }

    private fun proxyCountry(): String? = profile.proxy.lastCountryCode.ifBlank { null }

    // --------------------------------------------------------------- summary

    private fun updateFingerprintSummary() {
        val fp = profile.fp
        val gpu = fp.webglRenderer
            .removePrefix("ANGLE (")
            .substringBefore(", Direct3D")
            .substringBefore(", OpenGL")
            .take(52)
        binding.fpSummary.text = buildString {
            appendLine(fp.os + " · " + fp.deviceType + " · " + profile.engine.engineFamily + " " + fp.browserVersion)
            appendLine(fp.screenW.toString() + "×" + fp.screenH + " @" + fp.pixelRatio + "x · " + fp.colorDepth + "-bit")
            appendLine("GPU " + gpu)
            appendLine(fp.cores.toString() + " cores · " + fp.memory + " GB · touch " + fp.touchPoints)
            appendLine(fp.timezone + " · " + fp.language)
            append("WebRTC " + fp.webrtcMode + " · geo " + fp.geoMode)
        }

        val warnings = FingerprintFactory.warnings(fp, profile.engine)
        val score = FingerprintFactory.coherenceScore(warnings)
        binding.editorScore.text = getString(R.string.editor_coherence, score)
        val scoreColor = when {
            score >= 90 -> R.color.success
            score >= 60 -> R.color.warning
            else -> R.color.danger
        }
        val color = ContextCompat.getColor(this, scoreColor)
        binding.editorScore.setTextColor(color)
        binding.editorScore.backgroundTintList = ColorStateList.valueOf(UiKit.withAlpha(color, 0.14f))

        if (warnings.isEmpty()) {
            binding.fpWarnings.visibility = View.GONE
            binding.fpOk.visibility = View.VISIBLE
        } else {
            binding.fpWarnings.text = warnings.joinToString("\n") { "• " + it.message }
            binding.fpWarnings.visibility = View.VISIBLE
            binding.fpOk.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ proxy

    private fun updateProxyVisibility() {
        binding.switchProxy.setOnCheckedChangeListener(null)
        binding.switchProxy.isChecked = profile.proxy.enabled
        binding.switchProxy.setOnCheckedChangeListener { _, checked ->
            profile.proxy.type = when {
                !checked -> "none"
                profile.proxy.type == "none" -> "http"
                else -> profile.proxy.type
            }
            updateProxyVisibility()
            markDirty()
        }
        binding.proxyFields.visibility = if (profile.proxy.enabled) View.VISIBLE else View.GONE
        binding.editorProxyType.setText(profile.proxy.type.uppercase(), false)
        binding.editorProxyHost.setText(profile.proxy.host)
        binding.editorProxyPort.setText(profile.proxy.port.takeIf { it in 1..65535 }?.toString().orEmpty())
        binding.editorProxyUser.setText(profile.proxy.user)
        binding.editorProxyPass.setText(profile.proxy.password)
        binding.proxyStatus.text = describeProxyState(profile.proxy)
        binding.proxyStatus.setTextColor(ContextCompat.getColor(this, proxyStateColor(profile.proxy)))
    }

    private fun proxyStateColor(config: ProxyConfig): Int = when {
        config.online -> R.color.success
        config.checked -> R.color.danger
        else -> R.color.text_muted
    }

    private fun describeProxyState(config: ProxyConfig): String = when {
        !config.enabled -> getString(R.string.not_checked)
        config.online -> config.lastIp + " · " + config.latencyMs + " ms" +
            if (config.lastCountryCode.isNotBlank()) " · " + config.lastCountry else ""
        config.checked -> config.lastError.ifBlank { getString(R.string.proxies_failed) }
        else -> getString(R.string.not_checked)
    }

    private fun updateGeoVisibility() {
        binding.geoCoordinates.visibility =
            if (profile.fp.geoMode == "custom") View.VISIBLE else View.GONE
    }

    private fun updateWatchVisibility() {
        binding.rowWatchInterval.visibility =
            if (profile.tools.proxyWatch) View.VISIBLE else View.GONE
    }

    private fun checkProxy() {
        val candidate = buildProxyFromInputs()
        if (candidate.host.isBlank() || candidate.port !in 1..65535) {
            binding.proxyStatus.setText(R.string.proxies_import_invalid)
            return
        }
        binding.proxyStatus.setText(R.string.status_checking)
        binding.btnProxyCheck.isEnabled = false
        Async.bg {
            val result = ProxyTester.test(candidate)
            Async.onMain {
                if (isFinishing || isDestroyed) return@onMain
                binding.btnProxyCheck.isEnabled = true
                bindProxyResult(candidate, result)
            }
        }
    }

    private fun bindProxyResult(candidate: ProxyConfig, result: ProxyTestResult) {
        profile.proxy = candidate
        // One code path for "what a finished check means", shared with the saved-profile and
        // monitor paths so the editor can never record a different story.
        ProxyTester.applyResult(profile, result)
        binding.proxyStatus.text = describeProxyState(profile.proxy)
        binding.proxyStatus.setTextColor(ContextCompat.getColor(this, proxyStateColor(profile.proxy)))

        populateIdentityFields()
        // A check is a real measurement, not a draft: persist it for an existing profile so
        // leaving the editor without pressing Save does not throw the probe away. Identity edits
        // still need Save, which is what the unsaved-changes guard is for.
        val id = editingId
        if (id != null) {
            ProfileRepository.saveProfile(applicationContext, profile) {
                AppEvents.emitDataChanged(applicationContext, "proxy-checked")
            }
        }
        markDirty()
    }

    /**
     * POST_NOTIFICATIONS (API 33+) gates monitor alerts only — the foreground-service
     * notification keeps working without it, so a denial degrades rather than breaks.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS) }
    }

    private fun buildProxyFromInputs(): ProxyConfig {
        val type = when (binding.editorProxyType.text?.toString()?.uppercase()) {
            "SOCKS5" -> "socks5"
            "HTTPS" -> "https"
            else -> "http"
        }
        return ProxyConfig(
            type = type,
            host = binding.editorProxyHost.text?.toString()?.trim().orEmpty(),
            port = binding.editorProxyPort.text?.toString()?.trim()?.toIntOrNull() ?: 0,
            user = binding.editorProxyUser.text?.toString()?.trim().orEmpty(),
            password = binding.editorProxyPass.text?.toString().orEmpty(),
            watch = profile.tools.proxyWatch,
            watchIntervalSec = profile.tools.proxyWatchIntervalSec
        ).apply {
            // Preserve previous measurements only while the endpoint itself is unchanged.
            val previous = profile.proxy
            if (previous.host == host && previous.port == port && previous.type == type) {
                lastIp = previous.lastIp
                lastCountry = previous.lastCountry
                lastCountryCode = previous.lastCountryCode
                lastCity = previous.lastCity
                lastTimezone = previous.lastTimezone
                lastCheckedAt = previous.lastCheckedAt
                latencyMs = previous.latencyMs
                lastError = previous.lastError
                rotations = previous.rotations
                failStreak = previous.failStreak
            }
        }
    }

    private fun pickFromLibrary() {
        val app = applicationContext
        ProfileRepository.proxies(app) { saved ->
            if (isFinishing || isDestroyed) return@proxies
            if (saved.isEmpty()) {
                Async.toast(this, getString(R.string.proxies_empty_title))
                return@proxies
            }
            val labels = saved.map { item ->
                val config = item.config
                val state = if (config.online) config.latencyMs.toString() + " ms"
                else getString(R.string.not_checked)
                item.displayName() + "\n" + config.type.uppercase() + " " + config.authority() + " · " + state
            }.toTypedArray()

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_proxy_library)
                .setItems(labels) { _, which ->
                    val picked = saved[which].config.duplicate()
                    profile.proxy = picked
                    updateProxyVisibility()
                    if (profile.fp.autoMatchProxy && picked.lastCountryCode.isNotBlank()) {
                        FingerprintFactory.applyProxyLocale(profile.fp, picked.lastCountryCode)
                        populateIdentityFields()
                    }
                    markDirty()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ---------------------------------------------------------------- storage

    private fun refreshStorage() {
        val app = applicationContext
        val profileId = profile.id
        val isolated = BrowserKernel.supportsIsolation()
        binding.isolationValue.text = getString(
            if (isolated) R.string.editor_isolated else R.string.editor_shared
        )
        binding.isolationValue.setTextColor(
            ContextCompat.getColor(this, if (isolated) R.color.success else R.color.warning)
        )
        Async.bg {
            val bytes = ProfileStorage.sizeBytes(app, profileId)
            Async.onMain {
                if (isFinishing || isDestroyed) return@onMain
                binding.storageValue.text = ProfileStorage.humanSize(bytes)
            }
        }
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_clear_data)
            .setMessage(getString(R.string.browser_clear_data_body, profile.displayName()))
            .setPositiveButton(R.string.action_clear) { _, _ ->
                val app = applicationContext
                val id = profile.id
                Async.bg {
                    runCatching {
                        ProfileStorage.erase(app, id)
                        ProfileStorage.ensure(app, id)
                    }
                    ProfileRepository.clearHistory(app, id)
                    Async.onMain {
                        if (isFinishing || isDestroyed) return@onMain
                        Async.toast(this, getString(R.string.browser_cleared))
                        refreshStorage()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------- save

    private fun syncFromInputs() {
        val fp = profile.fp
        val prevW = fp.screenW
        val prevH = fp.screenH
        val prevRatio = fp.pixelRatio
        binding.editorScreenW.text?.toString()?.toIntOrNull()?.let { fp.screenW = it.coerceIn(200, 7680) }
        binding.editorScreenH.text?.toString()?.toIntOrNull()?.let { fp.screenH = it.coerceIn(200, 4320) }
        binding.editorDpr.text?.toString()?.replace(',', '.')?.toDoubleOrNull()?.let { parsed ->
            val ratio = if (parsed > 8.0) parsed / 10.0 else parsed
            if (ratio in 0.5..8.0) fp.pixelRatio = ratio
        }
        fp.availW = fp.screenW
        fp.availH = if (fp.deviceType == "mobile") fp.screenH else (fp.screenH - 48).coerceAtLeast(200)
        if (fp.screenW != prevW || fp.screenH != prevH || fp.pixelRatio != prevRatio) {
            fp.manualScreen = true
        }
        // Assign unconditionally: with `takeIf { it.isNotEmpty() }` a user who cleared the fields
        // kept the old coordinates, so deleting a custom location silently did nothing.
        fp.geoLat = binding.editorLat.text?.toString()?.trim().orEmpty()
        fp.geoLng = binding.editorLng.text?.toString()?.trim().orEmpty()
        profile.name = binding.editorName.text?.toString()?.trim().orEmpty()
        profile.group = binding.editorGroup.text?.toString()?.trim().orEmpty()
        profile.tags = binding.editorTags.text?.toString()?.trim().orEmpty()
        profile.notes = binding.editorNotes.text?.toString()?.trim().orEmpty()
        fp.userAgentOverride = binding.editorUserAgent.text?.toString()?.trim().orEmpty()
        profile.startUrl = binding.editorStartUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { Prefs.defaultStartUrl(this) }
        val proxyEnabled = binding.switchProxy.isChecked
        val rebuilt = buildProxyFromInputs()
        profile.proxy = if (proxyEnabled) rebuilt else rebuilt.apply { type = "none" }
        updateFingerprintSummary()
    }

    private fun markDirty() {
        dirty = true
    }

    private fun save(thenLaunch: Boolean): Boolean {
        if (saving) return false
        syncFromInputs()

        val name = binding.editorName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.editorName.error = getString(R.string.editor_name_required)
            binding.editorName.requestFocus()
            return false
        }
        binding.editorName.error = null

        saving = true
        binding.btnEditorSave.isEnabled = false
        binding.btnEditorSaveOpen.isEnabled = false

        profile.name = name
        profile.browserVersion = profile.fp.browserVersion
        profile.status = ""
        profile.fp.deviceType =
            if (profile.fp.touchPoints == 0) "desktop" else OsCatalog.deviceType(profile.fp.os)

        val app = applicationContext
        val wasNew = editingId == null

        ProfileRepository.saveProfile(app, profile) { saved ->
            saving = false
            binding.btnEditorSave.isEnabled = true
            binding.btnEditorSaveOpen.isEnabled = true
            dirty = false
            // Tell the screens behind this one (Home counters, the profile list) to repaint.
            // They also refresh on resume; this makes the change visible immediately for the
            // "save and open" path, which leaves the editor on screen over the list.
            AppEvents.emitDataChanged(app, "profile-saved")
            Async.toast(this, getString(if (wasNew) R.string.editor_created else R.string.status_saved))
            if (thenLaunch) {
                BrowserActivity.launch(this, saved.id)
                finish()
            } else {
                editingId = saved.id
                profile = saved
                binding.editorTitle.setText(R.string.editor_title_edit)
                binding.btnEditorDelete.visibility = View.VISIBLE
                refreshStorage()
            }
        }
        return true
    }

    private fun confirmDelete() {
        if (editingId == null) return
        val keepTrash = Prefs.trashEnabled(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profiles_delete_title, profile.displayName()))
            .setMessage(
                getString(
                    if (keepTrash) R.string.profiles_delete_body_trash
                    else R.string.profiles_delete_body_forever
                )
            )
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ProfileRepository.deleteProfile(this, profile, keepTrash) {
                    AppEvents.emitDataChanged(applicationContext, "profile-deleted")
                    finish()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Unsaved-changes guard.
     *
     * Registered on the dispatcher instead of overriding the deprecated `onBackPressed()`, so the
     * same dialog appears for the system back gesture, the toolbar back button and any caller
     * that goes through `onBackPressedDispatcher` (the editor's own back arrow already does).
     */
    private fun installBackGuard() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                MaterialAlertDialogBuilder(this@ProfileEditorActivity)
                    .setTitle(R.string.editor_unsaved_title)
                    .setMessage(R.string.editor_unsaved_body)
                    .setPositiveButton(R.string.action_save) { _, _ ->
                        if (save(thenLaunch = false)) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .setNeutralButton(R.string.action_discard) { _, _ ->
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    .show()
            }
        })
    }

    private fun intervalLabel(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_ENGINE = "engine"
        private const val REQ_NOTIFICATIONS = 41

        fun launch(context: Context, profileId: String? = null) {
            val intent = Intent(context, ProfileEditorActivity::class.java)
            if (profileId != null) intent.putExtra(EXTRA_PROFILE_ID, profileId)
            runCatching { context.startActivity(intent) }
        }

        fun launchForEngine(context: Context, engine: BrowserEngine) {
            runCatching {
                context.startActivity(
                    Intent(context, ProfileEditorActivity::class.java)
                        .putExtra(EXTRA_ENGINE, engine.id)
                )
            }
        }
    }
}
