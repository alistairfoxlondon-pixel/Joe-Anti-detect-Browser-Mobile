package com.joebrowser.app.proxy

import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.ProxyConfig
import com.joebrowser.app.data.SavedProxy
import com.joebrowser.app.databinding.DialogProxyAddBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Paste-a-list or manual proxy entry, with a live check before saving.
 * After saving, background checks are kicked off so the library shows fresh exit IPs.
 */
class ProxyAddDialog(
    private val context: Context,
    private val onSaved: () -> Unit = {}
) {

    fun show() {
        val binding = DialogProxyAddBinding.inflate(LayoutInflater.from(context))

        val schemes = listOf("HTTP", "HTTPS", "SOCKS5")
        binding.proxyScheme.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, schemes))
        binding.proxyScheme.setText("HTTP", false)

        // Pre-fill from clipboard when it looks like a proxy list.
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
            if (clip.isNotEmpty() && looksLikeProxyList(clip)) {
                binding.pasteInput.setText(clip)
                binding.parsePreview.text = "${countCandidateLines(clip)} line(s) detected in clipboard"
            }
        }

        binding.btnParse.setOnClickListener {
            val result = ProxyParser.parseAll(binding.pasteInput.text?.toString().orEmpty())
            binding.parsePreview.text = when {
                result.proxies.isEmpty() -> "Nothing recognized — use host:port or scheme://user:pass@host:port per line"
                else -> "Recognized ${result.proxies.size} prox${if (result.proxies.size == 1) "y" else "ies"}" +
                    if (result.rejected.isNotEmpty()) " (${result.rejected.size} rejected)" else ""
            }
        }

        binding.btnCancelAdd.setOnClickListener { dialogRef.get()?.dismiss() }

        binding.btnCheckSave.setOnClickListener {
            val pasted = binding.pasteInput.text?.toString().orEmpty().trim()
            if (pasted.isNotEmpty()) saveParsedList(pasted, binding) else saveManual(binding)
        }

        val dialog = MaterialAlertDialogBuilder(context).setView(binding.root).create()
        dialogRef.set(dialog)
        dialog.show()
    }

    private val dialogRef = java.lang.ref.WeakReference<AlertDialog>(null)

    private fun saveParsedList(pasted: String, binding: DialogProxyAddBinding) {
        val watch = binding.proxyWatchSwitch.isChecked
        val parsed = ProxyParser.parseAll(pasted)
        if (parsed.proxies.isEmpty()) {
            binding.parsePreview.text = "Nothing recognized in the pasted text."
            return
        }
        val db = Database.get(context)
        val ids = mutableListOf<String>()
        parsed.proxies.forEach { cfg ->
            val config = ProxyConfig.fromJson(cfg.toJson()).apply { watch = watch }
            val item = SavedProxy(name = cfg.label().ifBlank { "${cfg.host}:${cfg.port}" }, config = config)
            db.saveProxy(item)
            ids.add(item.id)
        }
        android.widget.Toast.makeText(context, "Saved ${ids.size} prox${if (ids.size == 1) "y" else "ies"}", android.widget.Toast.LENGTH_SHORT).show()
        dialogRef.get()?.dismiss()
        onSaved()
        CoroutineScope(Dispatchers.IO).launch { ids.forEach { ProxyTester.testAndPersist(context, it) } }
    }

    private fun saveManual(binding: DialogProxyAddBinding) {
        val host = binding.proxyHost.text?.toString()?.trim().orEmpty()
        val port = binding.proxyPort.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val scheme = binding.proxyScheme.text?.toString()?.trim()?.lowercase().orEmpty().ifEmpty { "http" }
        val user = binding.proxyUser.text?.toString()?.trim().orEmpty()
        val pass = binding.proxyPass.text?.toString().orEmpty()
        val name = binding.proxyName.text?.toString()?.trim().orEmpty()
        val watch = binding.proxyWatchSwitch.isChecked

        if (host.isEmpty() || port !in 1..65535) {
            binding.checkResultText.text = "Enter a valid host and port (1–65535)."
            return
        }
        val config = ProxyConfig(type = scheme, host = host, port = port, user = user, password = pass, watch = watch)
        val item = SavedProxy(name = name.ifEmpty { "${scheme.uppercase()} · $host:$port" }, config = config)
        Database.get(context).saveProxy(item)
        binding.checkResultText.text = "Saved. Checking…"
        binding.btnCheckSave.isEnabled = false

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { ProxyTester.testAndPersist(context, item.id) }
            binding.checkResultText.text = when {
                result == null -> "Check failed: proxy unreachable"
                !result.ok -> "Unreachable — ${result.error.ifBlank { "proxy refused the connection" }}"
                else -> "OK — ${result.ip} · ${result.latencyMs} ms · ${result.country.ifEmpty { "unknown country" }}"
            }
            dialogRef.get()?.dismiss()
            onSaved()
        }
    }

    private fun looksLikeProxyList(text: String): Boolean =
        Regex("""(?im)^\s*(socks5h?|socks5|socks4|https?)://|^\s*[\w.\-]+:\d{2,5}(:[^\s:]*){0,2}\s*$""").containsMatchIn(text)

    private fun countCandidateLines(text: String): Int =
        text.lineSequence().count { it.isNotBlank() && !it.trim().startsWith('#') }
}
