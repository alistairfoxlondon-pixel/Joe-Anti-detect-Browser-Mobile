package com.joebrowser.app.proxy

import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.joebrowser.app.R
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.ProxyConfig
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.data.SavedProxy
import com.joebrowser.app.databinding.SheetProxyAddBinding

/**
 * Add-proxy bottom sheet.
 *
 * Modes:
 *  - **Paste** — parses every line with [ProxyParser], shows a live "N ready" count, and can
 *    import a whole list in one tap. A clipboard list is pre-filled when it looks like one.
 *  - **Manual** — protocol/host/port/credentials with inline validation.
 *
 * Both paths persist through [ProfileRepository] (background write) and then run real health
 * checks in parallel, so freshly imported proxies already show their exit IP and latency.
 */
class ProxyAddSheet(
    private val context: Context,
    private val onSaved: () -> Unit = {}
) {

    private var dialog: BottomSheetDialog? = null
    private lateinit var binding: SheetProxyAddBinding
    private var mode: Mode = Mode.PASTE

    private enum class Mode { PASTE, MANUAL }

    fun show() {
        binding = SheetProxyAddBinding.inflate(LayoutInflater.from(context))
        dialog = BottomSheetDialog(context, R.style.ThemeOverlay_Jb_BottomSheet).apply {
            setContentView(binding.root)
            setCancelable(true)
        }

        binding.proxyScheme.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, listOf("HTTP", "HTTPS", "SOCKS5"))
        )
        binding.proxyScheme.setText("HTTP", false)
        binding.proxyPort.filters = arrayOf(com.joebrowser.app.ui.UiKit.rangeFilter(1, 65535))

        prefillFromClipboard()
        wireTabs()
        wireActions()

        dialog?.show()
    }

    // --------------------------------------------------------------------- tabs

    private fun wireTabs() {
        binding.modeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mode = if (tab.position == 0) Mode.PASTE else Mode.MANUAL
                applyMode()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        applyMode()
    }

    private fun applyMode() {
        binding.pasteGroup.visibility = if (mode == Mode.PASTE) View.VISIBLE else View.GONE
        binding.manualGroup.visibility = if (mode == Mode.MANUAL) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- clipboard

    private fun prefillFromClipboard() {
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
            if (clip.isEmpty()) return
            val preview = ProxyParser.parseAll(clip)
            if (preview.accepted > 0) {
                binding.pasteInput.setText(clip)
                binding.parsePreview.text = ProxyParser.describe(preview)
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private fun wireActions() {
        binding.btnCancelAdd.setOnClickListener { dismiss() }

        binding.btnParse.setOnClickListener {
            val result = ProxyParser.parseAll(binding.pasteInput.text?.toString().orEmpty())
            binding.parsePreview.text = ProxyParser.describe(result)
        }

        binding.btnCheckSave.setOnClickListener {
            when (mode) {
                Mode.PASTE -> savePasted()
                Mode.MANUAL -> saveManual()
            }
        }
    }

    private fun savePasted() {
        val text = binding.pasteInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            binding.checkResultText.setText(R.string.proxies_import_nothing)
            return
        }
        val parsed = ProxyParser.parseAll(text)
        if (parsed.proxies.isEmpty()) {
            binding.parsePreview.setText(R.string.proxies_import_nothing)
            return
        }
        val watch = binding.proxyWatchSwitch.isChecked
        val items = parsed.proxies.map { config ->
            SavedProxy(
                name = config.label(),
                config = config.duplicate().apply { this.watch = watch }
            )
        }

        binding.btnCheckSave.isEnabled = false
        binding.checkResultText.text = context.getString(
            if (parsed.rejected.isEmpty()) R.string.proxies_import_count
            else R.string.proxies_import_count_skipped,
            parsed.accepted,
            parsed.rejected.size
        )

        ProfileRepository.saveProxies(context, items) { saved ->
            dismiss()
            onSaved()
            Async.toast(
                context,
                if (saved == 1) "1 proxy saved" else "$saved proxies saved"
            )
            // Real health checks in parallel so the library shows live exit IPs immediately.
            items.forEach { item -> Async.bg { ProxyTester.testAndPersist(context, item.id) } }
        }
    }

    private fun saveManual() {
        val host = binding.proxyHost.text?.toString()?.trim().orEmpty()
        val port = binding.proxyPort.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val scheme = ProxyConfig.normalizeType(
            binding.proxyScheme.text?.toString()?.trim()?.lowercase().orEmpty().ifEmpty { "http" }
        )
        val user = binding.proxyUser.text?.toString()?.trim().orEmpty()
        val password = binding.proxyPass.text?.toString().orEmpty()
        val name = binding.proxyName.text?.toString()?.trim().orEmpty()
        val watch = binding.proxyWatchSwitch.isChecked

        if (host.isBlank() || port !in 1..65535) {
            binding.checkResultText.setText(R.string.proxies_import_invalid)
            return
        }

        val item = SavedProxy(
            name = name.ifBlank { scheme.uppercase() + " · " + host + ":" + port },
            config = ProxyConfig(
                type = scheme, host = host, port = port,
                user = user, password = password, watch = watch
            )
        )

        binding.btnCheckSave.isEnabled = false
        binding.checkResultText.setText(R.string.status_checking)

        ProfileRepository.saveProxy(context, item) {
            Async.bg {
                val result = runCatching { ProxyTester.testAndPersist(context, item.id) }.getOrNull()
                Async.onMain {
                    binding.checkResultText.text = when {
                        result == null -> context.getString(R.string.proxies_failed)
                        result.ok -> context.getString(R.string.browser_proxy_ok, result.ip, result.latencyMs)
                        else -> result.error.ifBlank { context.getString(R.string.proxies_failed) }
                    }
                    binding.btnCheckSave.isEnabled = true
                    if (result?.ok == true) {
                        dismiss()
                        onSaved()
                    }
                }
            }
        }
    }

    private fun dismiss() {
        runCatching { dialog?.dismiss() }
        dialog = null
    }
}
