package com.joebrowser.app.ui

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.joebrowser.app.R
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.SavedProxy
import com.joebrowser.app.databinding.FragmentProxiesBinding
import com.joebrowser.app.databinding.ItemProxyBinding
import com.joebrowser.app.proxy.ProxyAddDialog
import com.joebrowser.app.proxy.ProxyMonitorService
import com.joebrowser.app.proxy.ProxyTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxiesFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentProxiesBinding? = null
    private val binding get() = _binding!!
    private val adapter = ProxyAdapter(
        onWatchToggle = { item, watch ->
            val db = Database.get(requireContext())
            val updated = SavedProxy.fromJson(item.toJson()).apply { config.watch = watch }
            db.saveProxy(updated)
            ProxyMonitorService.maybeStart(requireContext())
        },
        onMenu = { item, anchor -> showMenu(item, anchor) },
        onCheck = { item -> checkOne(item) }
    )
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProxiesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.proxyList.layoutManager = LinearLayoutManager(requireContext())
        binding.proxyList.adapter = adapter
        binding.fabAddProxy.setOnClickListener { addProxy() }
        binding.emptyAdd.setOnClickListener { addProxy() }
        binding.emptyPaste.setOnClickListener { addProxy() }
        binding.btnCheckAll.setOnClickListener { checkAll() }
        binding.btnCheckDevice.setOnClickListener { checkDevice() }
        refresh()
        checkDevice()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun addProxy() {
        ProxyAddDialog(requireContext()) {
            refresh()
            ProxyMonitorService.maybeStart(requireContext())
        }.show()
    }

    private fun showMenu(item: SavedProxy, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Check now")
        popup.menu.add(0, 2, 0, "Rename")
        popup.menu.add(0, 3, 0, "Copy host:port")
        popup.menu.add(0, 4, 0, "Delete")
        popup.setOnMenuItemClickListener { mi: MenuItem ->
            when (mi.itemId) {
                1 -> checkOne(item)
                2 -> rename(item)
                3 -> copyToClipboard("${item.config.host}:${item.config.port}")
                4 -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete proxy?")
                        .setMessage("“${item.name.ifBlank { item.config.host }}” will be removed from the library.")
                        .setPositiveButton("Delete") { _, _ ->
                            Database.get(requireContext()).deleteProxy(item.id)
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    private fun rename(item: SavedProxy) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(item.name)
            setSingleLine(true)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename proxy")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    Database.get(requireContext())
                        .saveProxy(SavedProxy.fromJson(item.toJson()).apply { this.name = name })
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("proxy", text))
        android.widget.Toast.makeText(requireContext(), "Copied $text", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun checkOne(item: SavedProxy) {
        scope.launch {
            withContext(Dispatchers.IO) { ProxyTester.testAndPersist(requireContext(), item.id) }
            refresh()
        }
    }

    private fun checkAll() {
        val proxies = Database.get(requireContext()).getAllProxies()
        binding.btnCheckAll.text = "Checking…"
        binding.btnCheckAll.isEnabled = false
        scope.launch {
            withContext(Dispatchers.IO) {
                proxies.forEach { ProxyTester.testAndPersist(requireContext(), it.id) }
            }
            binding.btnCheckAll.text = "Check all"
            binding.btnCheckAll.isEnabled = true
            refresh()
        }
    }

    private fun checkDevice() {
        binding.deviceIpText.text = "Checking…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = (java.net.URL("https://api.ipify.org?format=json").openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 8_000; readTimeout = 8_000
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    org.json.JSONObject(body).optString("ip").ifBlank { null }
                } catch (e: Exception) { null }
            }
            if (_binding != null) {
                binding.deviceIpText.text = result?.let { "Public IP $it (direct)" } ?: "Couldn’t reach the IP echo service"
            }
        }
    }

    override fun refresh() {
        if (_binding == null) return
        val proxies = Database.get(requireContext()).getAllProxies()
        adapter.submit(proxies)
        val empty = proxies.isEmpty()
        binding.proxyEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.proxyList.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        binding.fabAddProxy.visibility = if (empty) View.GONE else View.VISIBLE
        ProxyMonitorService.maybeStart(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ProxyAdapter(
        private val onWatchToggle: (SavedProxy, Boolean) -> Unit,
        private val onMenu: (SavedProxy, View) -> Unit,
        private val onCheck: (SavedProxy) -> Unit
    ) : RecyclerView.Adapter<ProxyAdapter.Holder>() {

        private var items: List<SavedProxy> = emptyList()

        fun submit(list: List<SavedProxy>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemProxyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val binding: ItemProxyBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: SavedProxy) {
                val ctx = binding.root.context
                val cfg = item.config
                binding.proxyTitle.text = item.name.ifBlank { "${cfg.host}:${cfg.port}" }
                val auth = if (cfg.user.isNotBlank()) " · ${cfg.user}:••••" else ""
                binding.proxySubtitle.text = "${cfg.type.uppercase()}$auth"
                binding.proxyDetail.text = when {
                    cfg.latencyMs >= 0 && cfg.lastIp.isNotBlank() ->
                        "${cfg.lastIp} · ${cfg.latencyMs} ms" +
                            (cfg.lastCountryCode.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    cfg.lastCheckedAt > 0 -> "Unreachable at last check"
                    else -> "Not checked yet"
                }
                binding.proxyStatusDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when {
                            cfg.latencyMs >= 0 && cfg.lastIp.isNotBlank() -> ContextCompat.getColor(ctx, R.color.success)
                            cfg.lastCheckedAt > 0 -> ContextCompat.getColor(ctx, R.color.danger)
                            else -> ContextCompat.getColor(ctx, R.color.text_faint)
                        }
                    )
                }
                binding.proxyWatch.setOnCheckedChangeListener(null)
                binding.proxyWatch.isChecked = cfg.watch
                binding.proxyWatch.setOnCheckedChangeListener { _, checked -> onWatchToggle(item, checked) }
                binding.proxyMore.setOnClickListener { onMenu(item, it) }
                binding.root.setOnClickListener { onCheck(item) }
            }
        }
    }
}
