package com.joebrowser.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.browser.BrowserKernel
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.MonitorAlert
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.data.SavedProxy
import com.joebrowser.app.databinding.FragmentHomeBinding
import com.joebrowser.app.databinding.ItemAlertBinding
import com.joebrowser.app.databinding.ItemRecentProfileBinding
import com.joebrowser.app.proxy.ProxyMonitorEngine

/**
 * Dashboard.
 *
 * Every number on this screen comes from [ProfileRepository] (a background query with a
 * main-thread callback) or from [Async.compute]. Nothing here touches SQLite directly, so the
 * screen scrolls at full frame rate even with a few thousand history rows behind it.
 *
 * Refresh is driven by the repository's data-changed broadcast: when a profile is created in
 * another tab, or the monitor raises an alert, this screen updates itself.
 */
class HomeFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var cachedProfiles: List<Profile> = emptyList()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // This screen shows the proxy metric, the monitor status line and the alert list,
            // so it has to listen to the proxy and alert broadcasts too — subscribing only to
            // data-changed left those three stale until the tab was switched.
            val action = intent?.action ?: return
            if (action in TRACKED_ACTIONS) refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val main = activity as? MainActivity
        binding.btnNewProfile.setOnClickListener { main?.openProfileEditor() }
        binding.btnAddProxy.setOnClickListener { main?.setSelectedTab(R.id.nav_proxies) }
        binding.metricProfiles.setOnClickListener { main?.setSelectedTab(R.id.nav_profiles) }
        binding.metricProxies.setOnClickListener { main?.setSelectedTab(R.id.nav_proxies) }
        binding.metricAlerts.setOnClickListener { main?.setSelectedTab(R.id.nav_proxies) }
        binding.metricRunning.setOnClickListener { main?.setSelectedTab(R.id.nav_profiles) }
        binding.seeAllProfiles.setOnClickListener { main?.setSelectedTab(R.id.nav_profiles) }
        binding.monitorStatus.setOnClickListener { main?.setSelectedTab(R.id.nav_proxies) }
        binding.recentEmpty.setOnClickListener { main?.openProfileEditor() }

        buildKernelChips()
        buildVerifyChips()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        AppEvents.register(requireContext().applicationContext, receiver, TRACKED_ACTIONS)
    }

    override fun onStop() {
        AppEvents.unregister(requireContext().applicationContext, receiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------ kernel chips

    /** Two factual chips describing what the device's WebView can actually do. */
    private fun buildKernelChips() {
        val group = binding.kernelChips
        group.removeAllViews()
        val isolated = BrowserKernel.supportsIsolation()
        val early = BrowserKernel.supportsDocumentStart()

        group.addView(
            heroChip(
                label = getString(if (isolated) R.string.home_kernel_isolated else R.string.home_kernel_shared),
                iconRes = if (isolated) R.drawable.ic_check_circle else R.drawable.ic_warning,
                accentRes = if (isolated) R.color.success else R.color.warning
            )
        )
        group.addView(
            heroChip(
                label = getString(if (early) R.string.home_kernel_docstart else R.string.home_kernel_late),
                iconRes = if (early) R.drawable.ic_bolt else R.drawable.ic_warning,
                accentRes = if (early) R.color.success else R.color.warning
            )
        )
    }

    private fun heroChip(label: String, iconRes: Int, accentRes: Int): Chip = Chip(requireContext()).apply {
        text = label
        isCheckable = false
        isClickable = false
        isFocusable = false
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = UiKit.dpFloat(requireContext(), 26f)
        textSize = 11f
        setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
        setChipIconResource(iconRes)
        chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.white))
        chipIconSize = UiKit.dpFloat(requireContext(), 13f)
        chipBackgroundColor = ColorStateList.valueOf(
            UiKit.withAlpha(ContextCompat.getColor(requireContext(), accentRes), 0.34f)
        )
        chipStrokeWidth = 0f
    }

    // ----------------------------------------------------------- verify chips

    private fun buildVerifyChips() {
        val group = binding.verifyChips
        group.removeAllViews()
        VERIFY_SITES.forEach { (url, label) ->
            group.addView(
                Chip(requireContext()).apply {
                    text = label
                    isCheckable = false
                    chipMinHeight = UiKit.dpFloat(requireContext(), 40f)
                    setOnClickListener { openVerifySite(url) }
                }
            )
        }
    }

    private fun openVerifySite(url: String) {
        val target = selectVerifyTarget()
        if (target == null) {
            (activity as? MainActivity)?.openProfileEditor()
            return
        }
        BrowserActivity.launch(requireContext(), target.id, url)
    }

    private fun selectVerifyTarget(): Profile? = cachedProfiles.firstOrNull { it.id == Prefs.lastProfileId(requireContext()) }
        ?: cachedProfiles.firstOrNull()

    // ---------------------------------------------------------------- refresh

    override fun refresh() {
        val current = _binding ?: return
        if (!isAdded) return
        val app = current.root.context.applicationContext

        ProfileRepository.profiles(app) { profiles ->
            val target = _binding ?: return@profiles
            cachedProfiles = profiles
            bindRecent(target, profiles)
            bindVerifyLabel(target)
        }
        ProfileRepository.proxies(app) { proxies ->
            val target = _binding ?: return@proxies
            bindProxyMetric(target, proxies)
        }
        ProfileRepository.alerts(app, 3) { alerts ->
            val target = _binding ?: return@alerts
            bindAlerts(target, alerts)
        }
        // The counters used to be swapped: the Open-windows card showed the alerts tile's frame
        // and the alerts tile was fed the window count, so "Monitor alerts" read 1 whenever one
        // browser was open and the window count never appeared anywhere.
        Async.compute({ BrowserActivity.openWindowCount() }) { count ->
            val target = _binding ?: return@compute
            target.metricRunningValue.text = (count ?: 0).toString()
        }
        ProfileRepository.alertCount(app) { count ->
            val target = _binding ?: return@alertCount
            target.metricAlertsValue.text = count.toString()
        }
        Async.compute({ ProxyMonitorEngine.statusLine(app) to ProxyMonitorEngine.watchedCount(app) }) { result ->
            val target = _binding ?: return@compute
            val (line, watching) = result ?: ("" to 0)
            target.monitorStatus.text = line
            target.monitorStatus.setTextColor(
                ContextCompat.getColor(
                    target.root.context, if (watching > 0) R.color.success else R.color.text_muted
                )
            )
        }
    }

    private fun bindProxyMetric(target: FragmentHomeBinding, proxies: List<SavedProxy>) {
        val online = proxies.count { it.config.online }
        target.metricProxiesValue.text = online.toString() + "/" + proxies.size
        val colorRes = when {
            proxies.isEmpty() -> R.color.text
            online == proxies.size -> R.color.success
            online == 0 -> R.color.danger
            else -> R.color.warning
        }
        target.metricProxiesValue.setTextColor(
            ContextCompat.getColor(target.root.context, colorRes)
        )
    }

    private fun bindRecent(target: FragmentHomeBinding, profiles: List<Profile>) {
        val container = target.recentContainer
        container.removeAllViews()
        target.metricProfilesValue.text = profiles.size.toString()

        val recent = profiles.sortedByDescending { maxOf(it.lastOpened, it.created) }.take(4)
        target.recentEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE

        recent.forEach { profile ->
            val row = ItemRecentProfileBinding.inflate(layoutInflater, container, false)
            row.recentAvatar.text = profile.initials()
            row.recentAvatar.background = UiKit.avatarBackground(profile.id)
            row.recentName.text = profile.displayName()
            row.recentMeta.text = listOf(
                profile.fp.os,
                profile.engine.title,
                if (profile.proxy.enabled) profile.proxy.label() else getString(R.string.direct)
            ).joinToString(" · ")
            row.recentDot.background = UiKit.dotBackground(metaDotColor(profile), container.context)
            val launch = { BrowserActivity.launch(requireContext(), profile.id) }
            row.root.setOnClickListener { launch() }
            row.recentOpen.setOnClickListener { launch() }
            container.addView(row.root)
        }
    }

    private fun bindVerifyLabel(target: FragmentHomeBinding) {
        target.verifyProfileLabel.text = selectVerifyTarget()?.displayName() ?: ""
    }

    private fun bindAlerts(target: FragmentHomeBinding, alerts: List<MonitorAlert>) {
        val container = target.alertContainer
        container.removeAllViews()

        if (alerts.isEmpty()) {
            val row = ItemAlertBinding.inflate(layoutInflater, container, false)
            row.alertRoot.setBackgroundResource(R.drawable.bg_alert_ok)
            row.alertIcon.setImageResource(R.drawable.ic_check_circle)
            row.alertIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(row.root.context, R.color.success)
            )
            row.alertTitle.setText(R.string.home_empty_alerts)
            row.alertDetail.visibility = View.GONE
            row.alertTime.visibility = View.GONE
            container.addView(row.root)
            return
        }

        alerts.forEach { alert ->
            val row = ItemAlertBinding.inflate(layoutInflater, container, false)
            bindAlertRow(row, alert)
            container.addView(row.root)
        }
    }

    /** Shared with ProxiesFragment so alert styling never diverges between screens. */
    internal fun bindAlertRow(row: ItemAlertBinding, alert: MonitorAlert) {
        val context = row.root.context
        val background: Int
        val iconRes: Int
        val tintRes: Int
        val labelRes: Int
        when (alert.kind) {
            MonitorAlert.KIND_ROTATED -> {
                background = R.drawable.bg_alert_rotated
                iconRes = R.drawable.ic_refresh
                tintRes = R.color.warning
                labelRes = R.string.proxies_alert_rotated
            }
            MonitorAlert.KIND_RECOVERED -> {
                background = R.drawable.bg_alert_ok
                iconRes = R.drawable.ic_check_done
                tintRes = R.color.success
                labelRes = R.string.proxies_alert_recovered
            }
            else -> {
                background = R.drawable.bg_alert_offline
                iconRes = R.drawable.ic_wifi_off
                tintRes = R.color.danger
                labelRes = R.string.proxies_alert_offline
            }
        }
        row.alertRoot.setBackgroundResource(background)
        row.alertIcon.setImageResource(iconRes)
        row.alertIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tintRes))
        row.alertTitle.text = context.getString(labelRes) + " · " + alert.target
        row.alertDetail.text = alert.detail
        row.alertDetail.visibility = if (alert.detail.isBlank()) View.GONE else View.VISIBLE
        row.alertTime.text = UiKit.formatRelative(alert.ts)
        row.alertRoot.visibility = View.VISIBLE
    }

    private fun metaDotColor(profile: Profile): Int = when {
        profile.proxy.enabled && profile.proxy.online -> R.color.success
        profile.proxy.enabled && profile.proxy.checked -> R.color.danger
        profile.proxy.enabled -> R.color.warning
        else -> R.color.text_muted
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val TRACKED_ACTIONS = listOf(
            AppEvents.ACTION_DATA_CHANGED,
            AppEvents.ACTION_PROXY_STATE,
            AppEvents.ACTION_MONITOR_ALERT
        )

        private val VERIFY_SITES = listOf(
            "https://www.browserscan.net/" to "BrowserScan",
            "https://iphey.com/" to "IPHey",
            "https://pixelscan.net/" to "PixelScan",
            "https://www.ipfighter.com/browser-fingerprint" to "IPFighter",
            "https://whoer.net/" to "Whoer"
        )
    }
}
