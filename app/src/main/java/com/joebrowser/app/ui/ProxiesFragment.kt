package com.joebrowser.app.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.data.ProxyConfig
import com.joebrowser.app.data.SavedProxy
import com.joebrowser.app.databinding.FragmentProxiesBinding
import com.joebrowser.app.databinding.ItemProxyBinding
import com.joebrowser.app.proxy.ProxyAddSheet
import com.joebrowser.app.proxy.ProxyMonitorEngine
import com.joebrowser.app.proxy.ProxyMonitorService
import com.joebrowser.app.proxy.ProxyTestResult
import com.joebrowser.app.proxy.ProxyTester

/**
 * Proxies tab — the user-facing half of the monitoring engine.
 *
 * What is wired here:
 *  - "This device" card → real public-IP lookup against a no-proxy connection.
 *  - Monitor strip → live engine status text plus a tap-through to the monitor controls.
 *  - Library rows → tap to re-check now, switch to add/remove from the watch list.
 *  - Check-all → parallel checks across the whole library with a live progress label.
 *  - Alerts → the three transition kinds surfaced by [ProxyMonitorEngine].
 */
class ProxiesFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentProxiesBinding? = null
    private val binding get() = _binding!!

    private val adapter = ProxyAdapter(
        onWatchToggle = { item, watch -> setWatch(item, watch) },
        onMenu = { item, anchor -> showMenu(item, anchor) },
        onCheck = { item -> checkOne(item) }
    )

    private var checkingAll = false
    private var deviceResult: ProxyTestResult? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AppEvents.ACTION_DATA_CHANGED,
                AppEvents.ACTION_PROXY_STATE,
                AppEvents.ACTION_MONITOR_ALERT -> refresh()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProxiesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.proxyList.layoutManager = LinearLayoutManager(requireContext())
        binding.proxyList.adapter = adapter
        binding.proxyList.itemAnimator?.changeDuration = 0

        binding.fabAddProxy.setOnClickListener { addProxy() }
        binding.emptyAdd.setOnClickListener { addProxy() }
        binding.emptyPaste.setOnClickListener { addProxy() }
        binding.btnCheckAll.setOnClickListener { checkAll() }
        binding.btnCheckDevice.setOnClickListener { checkDevice() }
        binding.monitorStrip.setOnClickListener { showMonitorControls() }

        refresh()
        checkDevice()
    }

    override fun onStart() {
        super.onStart()
        AppEvents.register(
            requireContext().applicationContext, receiver,
            listOf(AppEvents.ACTION_DATA_CHANGED, AppEvents.ACTION_PROXY_STATE, AppEvents.ACTION_MONITOR_ALERT)
        )
    }

    override fun onStop() {
        runCatching { context?.applicationContext?.let { AppEvents.unregister(it, receiver) } }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ device

    private fun checkDevice() {
        val current = _binding ?: return
        current.deviceIpText.text = getString(R.string.status_checking)
        ProxyMonitorEngine.refreshDevice { result ->
            val target = _binding ?: return@refreshDevice
            deviceResult = result
            target.deviceIpText.text = when {
                result.ok && result.ip.isNotBlank() -> buildString {
                    append(result.ip)
                    if (result.countryCode.isNotBlank()) append(" · ").append(result.country)
                    if (result.latencyMs >= 0) append(" · ").append(result.latencyMs).append(" ms")
                }
                else -> result.error.ifBlank { getString(R.string.not_checked) }
            }
        }
    }

    // --------------------------------------------------------------- monitor UI

    private fun showMonitorControls() {
        val context = requireContext()
        val intervals = listOf(30, 60, 120, 300, 600)
        val labels = intervals.map { seconds ->
            val minutes = seconds / 60
            when {
                seconds < 60 -> "$seconds s"
                minutes == 1 -> "1 min"
                else -> "$minutes min"
            }
        }.toTypedArray()
        val currentInterval = Prefs.monitorIntervalSec(context)
        val currentChoice = intervals.indexOfFirst { it == currentInterval }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.proxies_monitor_label)
            .setSingleChoiceItems(labels, currentChoice) { dialog, which ->
                Prefs.setMonitorIntervalSec(context, intervals[which])
                ProxyMonitorService.startIfNeeded(context)
                Async.compute({ ProxyMonitorEngine.statusLine(context) }) { line ->
                    _binding?.monitorStatusText?.text = line ?: ""
                }
                dialog.dismiss()
            }
            .setNeutralButton(R.string.action_check_now) { _, _ ->
                ProxyMonitorEngine.runCycle(context, force = true) { refresh() }
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    // ----------------------------------------------------------------- actions

    private fun addProxy() {
        ProxyAddSheet(requireContext()) {
            refresh()
            ProxyMonitorService.startIfNeeded(requireContext())
        }.show()
    }

    private fun showMenu(item: SavedProxy, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ACTION_CHECK, 0, getString(R.string.action_check_now))
        popup.menu.add(0, ACTION_RENAME, 1, getString(R.string.proxies_rename))
        popup.menu.add(0, ACTION_COPY, 2, getString(R.string.action_copy))
        popup.menu.add(0, ACTION_DELETE, 3, getString(R.string.action_delete))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                ACTION_CHECK -> checkOne(item)
                ACTION_RENAME -> rename(item)
                ACTION_COPY -> copyToClipboard(item.config.authority())
                ACTION_DELETE -> confirmDelete(item)
            }
            true
        }
        popup.show()
    }

    private fun rename(item: SavedProxy) {
        val input = EditText(requireContext()).apply {
            setText(item.name)
            hint = getString(R.string.proxies_import_name)
            setSingleLine(true)
            setPadding(UiKit.dp(requireContext(), 18), UiKit.dp(requireContext(), 10), UiKit.dp(requireContext(), 18), 0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.proxies_rename)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                ProfileRepository.saveProxy(
                    requireContext(),
                    SavedProxy(id = item.id, name = name, note = item.note, config = item.config.duplicate())
                ) {
                    AppEvents.emitDataChanged(requireContext().applicationContext, "proxy-saved")
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(item: SavedProxy) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.proxies_delete_title)
            .setMessage(getString(R.string.proxies_delete_body, item.displayName()))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ProfileRepository.deleteProxy(requireContext(), item.id) {
                    refresh()
                    ProxyMonitorService.startIfNeeded(requireContext())
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("proxy", text))
            Async.toast(requireContext(), getString(R.string.proxies_copied))
        }.onFailure { Async.toast(requireContext(), getString(R.string.error_generic)) }
    }

    /**
     * Toggling the watch switch updates the row and immediately re-arms the monitor service,
     * so a newly watched proxy starts being health-checked without leaving the screen.
     */
    private fun setWatch(item: SavedProxy, watch: Boolean) {
        val updated = SavedProxy(
            id = item.id,
            name = item.name,
            note = item.note,
            config = item.config.duplicate().apply { this.watch = watch }
        )
        ProfileRepository.saveProxy(requireContext(), updated) {
            // Ask for notification permission at the only moment it makes sense: the user has
            // just asked to be told when this proxy goes down.
            if (watch) (activity as? MainActivity)?.ensureNotificationPermission()
            ProxyMonitorService.startIfNeeded(requireContext())
            AppEvents.emitDataChanged(requireContext().applicationContext, "proxy-saved")
            refresh()
        }
    }

    private fun checkOne(item: SavedProxy) {
        val context = requireContext().applicationContext
        adapter.markChecking(item.id)
        Async.bg {
            val result = runCatching { ProxyTester.testAndPersist(context, item.id) }.getOrNull()
            Async.onMain {
                adapter.clearChecking(item.id)
                when {
                    result == null -> Async.toast(context, getString(R.string.proxies_failed))
                    result.ok -> Async.toast(
                        context,
                        getString(R.string.browser_proxy_ok, result.ip, result.latencyMs)
                    )
                    else -> Async.toast(context, result.error.ifBlank { getString(R.string.proxies_failed) })
                }
                refresh()
            }
        }
    }

    private fun checkAll() {
        if (checkingAll) return
        checkingAll = true
        binding.btnCheckAll.isEnabled = false
        binding.btnCheckAll.text = getString(R.string.status_checking)

        ProxyTester.checkAll(
            context = requireContext(),
            onProgress = { done, total ->
                val target = _binding ?: return@checkAll
                target.btnCheckAll.text = "$done/$total"
            },
            onDone = { checked, online, offline ->
                checkingAll = false
                val target = _binding ?: return@checkAll
                target.btnCheckAll.isEnabled = true
                target.btnCheckAll.setText(R.string.action_check_all)
                if (checked > 0) {
                    Async.toast(
                        target.root.context,
                        "$online online · $offline offline"
                    )
                }
                refresh()
            }
        )
    }

    // ----------------------------------------------------------------- refresh

    override fun refresh() {
        val current = _binding ?: return
        if (!isAdded) return
        val context = current.root.context

        ProfileRepository.proxies(context) { proxies ->
            val target = _binding ?: return@proxies
            adapter.submit(proxies)
            val empty = proxies.isEmpty()
            target.proxyEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
            target.proxyList.visibility = if (empty) View.INVISIBLE else View.VISIBLE
            target.fabAddProxy.visibility = if (empty) View.GONE else View.VISIBLE
        }

        Async.compute({
            val watching = ProxyMonitorEngine.watchedCount(context)
            watching to ProxyMonitorEngine.statusLine(context)
        }) { result ->
            val target = _binding ?: return@compute
            val (watching, line) = result ?: (0 to "")
            target.monitorStatusText.text = line
            target.monitorDot.setBackgroundResource(
                if (watching > 0) R.drawable.bg_dot_good else R.drawable.bg_dot
            )
        }

        ProxyMonitorService.startIfNeeded(context)
    }

    override fun onDestroyView() {
        binding.proxyList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    // ----------------------------------------------------------------- adapter

    private class ProxyAdapter(
        private val onWatchToggle: (SavedProxy, Boolean) -> Unit,
        private val onMenu: (SavedProxy, View) -> Unit,
        private val onCheck: (SavedProxy) -> Unit
    ) : RecyclerView.Adapter<ProxyAdapter.Holder>() {

        private var items: List<SavedProxy> = emptyList()
        private val checking = mutableSetOf<String>()

        fun submit(list: List<SavedProxy>) {
            items = list
            notifyDataSetChanged()
        }

        fun markChecking(id: String) {
            checking.add(id)
            items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

        fun clearChecking(id: String) {
            checking.remove(id)
            items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemProxyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.bind(item, checking.contains(item.id))
        }

        inner class Holder(private val view: ItemProxyBinding) : RecyclerView.ViewHolder(view.root) {

            fun bind(item: SavedProxy, isChecking: Boolean) {
                val context = view.root.context
                val config: ProxyConfig = item.config

                view.proxyTitle.text = item.displayName()
                view.proxyType.text = config.type.uppercase()

                val stateDot = when {
                    !config.enabled -> R.drawable.bg_dot
                    isChecking -> R.drawable.bg_dot_warn
                    config.online -> R.drawable.bg_dot_good
                    config.checked -> R.drawable.bg_dot_bad
                    else -> R.drawable.bg_dot
                }
                view.proxyStatusDot.setBackgroundResource(stateDot)

                view.proxyDetail.text = when {
                    isChecking -> context.getString(R.string.status_checking)
                    config.online -> buildString {
                        append(config.lastIp)
                        if (config.lastCountryCode.isNotBlank()) {
                            val flag = UiKit.flagEmoji(config.lastCountryCode)
                            if (flag.isNotEmpty()) append(" ").append(flag)
                        }
                        append(" · ").append(config.latencyMs).append(" ms")
                    }
                    config.checked -> config.lastError.ifBlank { context.getString(R.string.proxies_failed) }
                    else -> context.getString(R.string.not_checked)
                }
                view.proxyDetail.setTextColor(
                    ContextCompat.getColor(
                        context,
                        when {
                            isChecking -> R.color.text_muted
                            config.online -> R.color.success
                            config.checked -> R.color.danger
                            else -> R.color.text_muted
                        }
                    )
                )

                // Secondary line only when there is something real to say.
                val meta = buildList {
                    if (config.user.isNotBlank()) add(config.user + ":••••")
                    if (config.rotations > 0) add(config.rotations.toString() + " rotation(s)")
                    if (config.lastCheckedAt > 0L) add(UiKit.formatRelative(config.lastCheckedAt))
                }.joinToString(" · ")
                view.proxyMeta.text = meta
                view.proxyMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

                view.proxyWatch.setOnCheckedChangeListener(null)
                view.proxyWatch.isChecked = config.watch
                view.proxyWatch.setOnCheckedChangeListener { _, checked -> onWatchToggle(item, checked) }

                view.proxyMore.setOnClickListener { onMenu(item, it) }
                view.root.setOnClickListener { onCheck(item) }
            }
        }
    }

    companion object {
        private const val ACTION_CHECK = 201
        private const val ACTION_RENAME = 202
        private const val ACTION_COPY = 203
        private const val ACTION_DELETE = 204
    }
}
