package com.joebrowser.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.FingerprintConfig
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProxyConfig
import com.joebrowser.app.databinding.ActivityProfileEditorBinding
import com.joebrowser.app.fingerprint.FingerprintFactory
import com.joebrowser.app.proxy.ProxyTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditorBinding
    private var editingId: String? = null
    private lateinit var profile: Profile
    private val scope = CoroutineScope(Dispatchers.Main)

    private val browserKinds = listOf("chrome" to "Google Chrome", "chromium" to "Chromium",
        "brave" to "Brave", "edge" to "Microsoft Edge", "firefox" to "Mozilla Firefox")
    private val webrtcModes = listOf("off", "proxy", "public")
    private val geoModes = listOf("proxy", "system", "custom", "block")

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_JoeBrowser)
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
        val requestedKind = intent.getStringExtra(EXTRA_BROWSER_KIND)

        val existing = editingId?.let { Database.get(this).getProfile(it) }
        profile = existing?.copy(freshId = false) ?: Profile().apply {
            fp = FingerprintConfig()
            FingerprintFactory.regenerate(fp, "Android", null, requestedKind ?: "chrome")
            browserKind = requestedKind ?: "chrome"
            browserVersion = fp.browserVersion
        }
        binding.editorTitle.text = if (existing != null) "Edit profile" else "New profile"
        binding.btnEditorDelete.visibility = if (existing != null) View.VISIBLE else View.GONE
        binding.btnEditorLaunch.visibility = if (existing != null) View.VISIBLE else View.GONE

        setupDropdowns(requestedKind)
        populateFields()
        bindActions()
        updateFingerprintSummary()
        updateProxyFieldsVisibility()
    }

    // ---------------- setup ----------------

    private fun setupDropdowns(requestedKind: String?) {
        val kindLabels = browserKinds.map { it.second }
        binding.editorBrowserKind.setAdapter(adapter(kindLabels))
        binding.editorOs.setAdapter(adapter(FingerprintFactory.osList))
        binding.editorWebrtcMode.setAdapter(adapter(webrtcModes))
        binding.editorGeoMode.setAdapter(adapter(geoModes))
        binding.editorProxyScheme.setAdapter(adapter(listOf("HTTP", "HTTPS", "SOCKS5")))

        val initialKind = requestedKind ?: profile.browserKind
        binding.editorBrowserKind.setText(browserKinds.firstOrNull { it.first == initialKind }?.second ?: "Google Chrome", false)
        binding.editorOs.setText(profile.fp.os, false)

        binding.editorOs.setOnItemClickListener { _, _, _, _ ->
            // Regenerate in place so GPU/screen/cores stay coherent with the new OS.
            val os = binding.editorOs.text?.toString() ?: "Android"
            FingerprintFactory.regenerate(profile.fp, os, proxyCountryCodeHint(), selectedBrowserKind())
            profile.browserKind = selectedBrowserKind()
            profile.browserVersion = profile.fp.browserVersion
            updateFingerprintSummary()
        }
        binding.editorBrowserKind.setOnItemClickListener { _, _, _, _ ->
            profile.browserKind = selectedBrowserKind()
            profile.browserVersion = FingerprintFactory.buildFor(profile.browserKind)
            profile.fp.browserVersion = profile.browserVersion
            updateFingerprintSummary()
        }
        binding.editorWebrtcMode.setOnItemClickListener { _, _, _, _ -> }
        binding.editorGeoMode.setOnItemClickListener { _, _, _, _ -> }
        binding.editorProxyScheme.setOnItemClickListener { _, _, _, _ -> }
    }

    private fun <T> adapter(items: List<T>): ArrayAdapter<T> =
        ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)

    private fun selectedBrowserKind(): String {
        val label = binding.editorBrowserKind.text?.toString().orEmpty()
        return browserKinds.firstOrNull { it.second == label }?.first ?: "chrome"
    }

    private fun populateFields() {
        binding.editorName.setText(profile.name)
        binding.editorNotes.setText(profile.notes)
        binding.editorGroup.setText(profile.group)
        binding.editorTags.setText(profile.tags)
        binding.editorProxyEnabled.isChecked = profile.proxy.enabled
        if (profile.proxy.enabled) {
            binding.editorProxyScheme.setText(profile.proxy.type.uppercase(), false)
            binding.editorProxyHost.setText(profile.proxy.host)
            binding.editorProxyPort.setText(if (profile.proxy.port > 0) profile.proxy.port.toString() else "")
            binding.editorProxyUser.setText(profile.proxy.user)
            binding.editorProxyPass.setText(profile.proxy.password)
        }
        binding.editorCanvasNoise.isChecked = profile.fp.canvasNoise
        binding.editorAudioNoise.isChecked = profile.fp.audioNoise
        binding.editorHardwareNoise.isChecked = profile.fp.hardwareNoise
        binding.editorDoNotTrack.isChecked = profile.fp.doNotTrack
        binding.editorFreshSession.isChecked = profile.freshSession
        binding.editorWebrtcMode.setText(profile.fp.webrtcMode, false)
        binding.editorGeoMode.setText(profile.fp.geoMode, false)
    }

    private fun bindActions() {
        binding.btnEditorBack.setOnClickListener { finish() }
        binding.btnRegenerate.setOnClickListener {
            val os = binding.editorOs.text?.toString() ?: profile.fp.os
            FingerprintFactory.regenerate(profile.fp, os, proxyCountryCodeHint(), selectedBrowserKind())
            profile.browserKind = selectedBrowserKind()
            profile.browserVersion = profile.fp.browserVersion
            updateFingerprintSummary()
        }
        binding.editorProxyEnabled.setOnCheckedChangeListener { _, _ -> updateProxyFieldsVisibility() }
        binding.btnPickFromLibrary.setOnClickListener { pickFromLibrary() }
        binding.btnEditorCheckProxy.setOnClickListener { checkProxy() }
        binding.btnEditorSave.setOnClickListener { save() }
        binding.btnEditorDelete.setOnClickListener { confirmDelete() }
        binding.btnEditorLaunch.setOnClickListener {
            if (save()) BrowserActivity.launch(this, profile.id)
        }
    }

    private fun updateProxyFieldsVisibility() {
        binding.proxyFieldsContainer.visibility =
            if (binding.editorProxyEnabled.isChecked) View.VISIBLE else View.GONE
    }

    private fun proxyCountryCodeHint(): String? =
        profile.proxy.lastCountryCode.ifBlank { null }

    // ---------------- fingerprint summary ----------------

    private fun updateFingerprintSummary() {
        val fp = profile.fp
        val gpu = fp.webglRenderer.removePrefix("ANGLE (").substringBefore(", Direct3D").substringBefore(", OpenGL").take(44)
        binding.fpSummary.text = buildString {
            appendLine("OS ${fp.os} · ${fp.deviceType} · ${fp.browserVersion}")
            appendLine("Screen ${fp.screenW}×${fp.screenH} @${fp.pixelRatio}x · ${fp.colorDepth}-bit")
            appendLine("GPU $gpu")
            appendLine("Cores ${fp.cores} · ${fp.memory} GB · touch ${fp.touchPoints}")
            append("${fp.timezone} · ${fp.language}")
        }
        val warnings = FingerprintFactory.warnings(fp, profile.browserKind)
        if (warnings.isEmpty()) {
            binding.fpWarnings.visibility = View.GONE
            binding.fpOk.visibility = View.VISIBLE
        } else {
            binding.fpWarnings.text = warnings.joinToString("\n") { "• ${it.message}" }
            binding.fpWarnings.visibility = View.VISIBLE
            binding.fpOk.visibility = View.GONE
        }
    }

    // ---------------- proxy ----------------

    private fun currentProxyConfig(): ProxyConfig {
        val scheme = binding.editorProxyScheme.text?.toString()?.trim()?.lowercase().orEmpty()
            .ifEmpty { "http" }.replace("socks5", "socks5")
        return ProxyConfig(
            type = if (binding.editorProxyEnabled.isChecked) scheme else "none",
            host = binding.editorProxyHost.text?.toString()?.trim().orEmpty(),
            port = binding.editorProxyPort.text?.toString()?.trim()?.toIntOrNull() ?: 0,
            user = binding.editorProxyUser.text?.toString()?.trim().orEmpty(),
            password = binding.editorProxyPass.text?.toString().orEmpty()
        ).apply {
            // Preserve last-known-good test metadata for this exact host:port.
            if (profile.proxy.host == host && profile.proxy.port == port && profile.proxy.type == this.type) {
                lastIp = profile.proxy.lastIp
                lastCountry = profile.proxy.lastCountry
                lastCountryCode = profile.proxy.lastCountryCode
                lastCity = profile.proxy.lastCity
                lastTimezone = profile.proxy.lastTimezone
                lastCheckedAt = profile.proxy.lastCheckedAt
                latencyMs = profile.proxy.latencyMs
                watch = profile.proxy.watch
            }
        }
    }

    private fun pickFromLibrary() {
        val saved = Database.get(this).getAllProxies()
        if (saved.isEmpty()) {
            android.widget.Toast.makeText(this, "Library is empty — add proxies in the Proxies tab", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val labels = saved.map { s ->
            val c = s.config
            val state = if (c.latencyMs >= 0) "${c.latencyMs}ms" else "unchecked"
            "${s.name.ifBlank { c.host }}\n${c.type.uppercase()} ${c.host}:${c.port} · $state"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Pick from proxy library")
            .setItems(labels) { _, which ->
                val picked = saved[which].config
                binding.editorProxyEnabled.isChecked = true
                binding.editorProxyScheme.setText(picked.type.uppercase(), false)
                binding.editorProxyHost.setText(picked.host)
                binding.editorProxyPort.setText(picked.port.toString())
                binding.editorProxyUser.setText(picked.user)
                binding.editorProxyPass.setText(picked.password)
                updateProxyFieldsVisibility()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkProxy() {
        val cfg = currentProxyConfig()
        if (cfg.host.isBlank() || cfg.port !in 1..65535) {
            binding.editorProxyResult.text = "Enter host and port first"
            return
        }
        binding.editorProxyResult.text = "Checking…"
        binding.btnEditorCheckProxy.isEnabled = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { ProxyTester.test(cfg, 10_000) }
            binding.editorProxyResult.text = when {
                !result.ok -> "Unreachable — ${result.error.ifBlank { "refused" }}"
                else -> "OK — ${result.ip} · ${result.latencyMs} ms · ${result.country.ifEmpty { "?" }}"
            }
            binding.btnEditorCheckProxy.isEnabled = true
            if (result.ok) {
                // Keep the exit-IP metadata so the fingerprint can match locale to egress.
                profile.proxy.lastIp = result.ip
                profile.proxy.lastCountry = result.country
                profile.proxy.lastCountryCode = result.countryCode
                profile.proxy.lastCity = result.city
                profile.proxy.lastCheckedAt = System.currentTimeMillis()
                profile.proxy.latencyMs = result.latencyMs
            }
        }
    }

    // ---------------- save / delete ----------------

    /** Returns true when the profile was persisted. */
    private fun save(): Boolean {
        val name = binding.editorName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.editorName.error = "Name is required"
            return false
        }
        val proxy = currentProxyConfig()
        profile.name = name
        profile.notes = binding.editorNotes.text?.toString()?.trim().orEmpty()
        profile.group = binding.editorGroup.text?.toString()?.trim().orEmpty()
        profile.tags = binding.editorTags.text?.toString()?.trim().orEmpty()
        profile.browserKind = selectedBrowserKind()
        profile.browserVersion = profile.fp.browserVersion
        profile.freshSession = binding.editorFreshSession.isChecked
        profile.fp.canvasNoise = binding.editorCanvasNoise.isChecked
        profile.fp.audioNoise = binding.editorAudioNoise.isChecked
        profile.fp.hardwareNoise = binding.editorHardwareNoise.isChecked
        profile.fp.doNotTrack = binding.editorDoNotTrack.isChecked
        profile.fp.webrtcMode = binding.editorWebrtcMode.text?.toString()?.ifEmpty { "proxy" } ?: "proxy"
        profile.fp.geoMode = binding.editorGeoMode.text?.toString()?.ifEmpty { "proxy" } ?: "proxy"
        profile.proxy = proxy

        val db = Database.get(this)
        if (editingId != null) profile.id = editingId!!
        // Keep identities distinct — regenerate the seed if this exact signature already exists.
        val existing = db.getAllProfiles().filter { it.id != profile.id }.map { FingerprintFactory.signature(it.fp) }
        FingerprintFactory.ensureUnique(profile.fp, existing)

        db.saveProfile(profile)
        AppEvents.emitProfilesChanged(this)
        android.widget.Toast.makeText(this, if (editingId == null) "Profile created" else "Profile saved", android.widget.Toast.LENGTH_SHORT).show()
        finish()
        return true
    }

    private fun confirmDelete() {
        val keep = Prefs.trashEnabled(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete “${profile.name}”?")
            .setMessage(if (keep) "It will be kept in the trash. Its history is removed immediately." else "This permanently deletes the profile and its history.")
            .setPositiveButton("Delete") { _, _ ->
                Database.get(this).deleteProfile(profile, keep)
                AppEvents.emitProfilesChanged(this)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_BROWSER_KIND = "browser_kind"
    }
}
