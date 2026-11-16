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
import com.joebrowser.app.databinding.ItemProfileRowBinding
import com.joebrowser.app.proxy.ProxyMonitorEngine

/**
 * Dashboard.
 *
 * The screen answers three questions and nothing else: what can I open, is anything wrong,
 * what do I do next. Anything that would be a paragraph lives in the screen it describes.
 *
 * Every value comes from [ProfileRepository] (background query, main-thread callback) or from
 * [Async], so nothing blocks the first frame. Refreshes are driven by the app-wide data-change
 * broadcast, which is what keeps the metric row honest when another tab edits something.
 */
class HomeFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var cachedProfiles: List<Profile> = emptyList()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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

        binding.statProfiles.statRoot.setOnClickListener { main?.setSelectedTab(R.id.nav_profiles) }
        binding.statProxies.statRoot.setOnClickListener { main?.setSelectedTab(R.id.nav_proxies) }
        binding.statAlerts.statRoot.setOnClickListener { main?.setSelectedTab(R.id.nav_proxies) }

        binding.btnNewProfile.setOnClickListener { main?.openProfileEditor() }
        binding.btnAddProxy.setOnClickListener { main?.setSelectedTab(R.id.nav_proxies) }
        binding.recentEmpty.setOnClickListener { main?.openProfileEditor() }

        binding.recentHeader.sectionTitle.setText(R.string.home_section_recent)
        binding.recentHeader.sectionAction.setText(R.string.action_see_all)
        binding.recentHeader.sectionAction.setOnClickListener { main?.setSelectedTab(R.id.nav_profiles) }

        binding.alertsHeader.sectionTitle.setText(R.string.home_section_alerts)
        binding.alertsHeader.sectionAction.setText(R.string.action_see_all)
        binding.alertsHeader.sectionAction.setOnClickListener { main?.setSelectedTab(R.id.nav_proxies) }

        // Verify chips live inside the hero card; no separate section header to keep in sync.

        // Metric captions never change; setting them once keeps refresh() cheap.
        binding.statProfiles.statCaption.setText(R.string.nav_profiles)
        binding.statProfiles.statIcon.setImageResource(R.drawable.ic_nav_profiles)
        binding.statProxies.statCaption.setText(R.string.home_metric_proxies)
        binding.statProxies.statIcon.setImageResource(R.drawable.ic_nav_proxies)
        binding.statAlerts.statCaption.setText(R.string.home_metric_alerts)
        binding.statAlerts.statIcon.setImageResource(R.drawable.ic_bell)

        buildKernelFacts()
        buildVerifyChips()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        AppEvents.register(requireContext().applicationContext, receiver, TRACKED_ACTIONS)
    }

    override fun onStop() {
        runCatching { context?.applicationContext?.let { AppEvents.unregister(it, receiver) } }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------- hero facts

    /**
     * Two facts about this device's WebView, as a dot and a word. The dot carries the state,
     * so the label can be short enough to read at a glance.
     */
    private fun buildKernelFacts() {
        val isolated = BrowserKernel.supportsIsolation()
        val early = BrowserKernel.supportsDocumentStart()

        binding.heroIsolationDot.setBackgroundResource(
            if (isolated) R.drawable.bg_dot_good else R.drawable.bg_dot_warn
        )
        binding.heroIsolationLabel.setText(
            if (isolated) R.string.home_kernel_isolated else R.string.home_kernel_shared
        )
        binding.heroInjectionDot.setBackgroundResource(
            if (early) R.drawable.bg_dot_good else R.drawable.bg_dot_warn
        )
        binding.heroInjectionLabel.setText(
            if (early) R.string.home_kernel_docstart else R.string.home_kernel_late
        )
    }

    // ----------------------------------------------------------- verify chips

    private fun buildVerifyChips() {
        val group = binding.verifyChips
        group.removeAllViews()
        val context = requireContext()
        SiteBrand.primary.forEach { site ->
            group.addView(
                UiKit.actionChip(context, site.label) { openVerifySite(site.url) }
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

    private fun selectVerifyTarget(): Profile? =
        cachedProfiles.firstOrNull { it.id == Prefs.lastProfileId(requireContext()) }
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
            bindHero(target, profiles)
        }
        ProfileRepository.proxies(app) { proxies ->
            val target = _binding ?: return@proxies
            bindProxyMetric(target, proxies)
        }
        ProfileRepository.alerts(app, 3) { alerts ->
            val target = _binding ?: return@alerts
            bindAlerts(target, alerts)
        }
        ProfileRepository.alertCount(app) { count ->
            val target = _binding ?: return@alertCount
            target.statAlerts.statValue.text = count.toString()
            target.statAlerts.statValue.setTextColor(
                ContextCompat.getColor(
                    target.root.context,
                    if (count > 0) R.color.warning else R.color.text
                )
            )
        }
    }

    /** The hero says what the device is doing in one sentence — no feature list. */
    private fun bindHero(target: FragmentHomeBinding, profiles: List<Profile>) {
        val context = target.root.context
        val text = when {
            profiles.isEmpty() -> context.getString(R.string.home_hero_first_run)
            else -> {
                val target1 = selectVerifyTarget()
                target1?.displayName() ?: context.getString(R.string.home_hero_idle)
            }
        }
        // The hero names the profile that a verify tap would use and offers the primary action
        // itself, so the most common task (open my profile) is one tap from launch rather than
        // a trip through the profile list.
        target.heroHeadline.text = text
        val hasProfile = profiles.isNotEmpty()
        target.heroOpen.visibility = if (hasProfile) View.VISIBLE else View.GONE
        target.heroOpen.setOnClickListener {
            val selected = selectVerifyTarget() ?: return@setOnClickListener
            Prefs.setLastProfileId(requireContext(), selected.id)
            BrowserActivity.launch(requireContext(), selected.id)
        }
    }

    private fun bindProxyMetric(target: FragmentHomeBinding, proxies: List<SavedProxy>) {
        val online = proxies.count { it.config.online }
        target.statProxies.statValue.text = "$online/${proxies.size}"
        val colorRes = when {
            proxies.isEmpty() -> R.color.text
            online == proxies.size -> R.color.success
            online == 0 -> R.color.danger
            else -> R.color.warning
        }
        target.statProxies.statValue.setTextColor(ContextCompat.getColor(target.root.context, colorRes))
    }

    private fun bindRecent(target: FragmentHomeBinding, profiles: List<Profile>) {
        val container = target.recentContainer
        container.removeAllViews()
        target.statProfiles.statValue.text = profiles.size.toString()

        val recent = profiles.sortedByDescending { maxOf(it.lastOpened, it.created) }.take(4)
        target.recentEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        target.recentSection.visibility = View.VISIBLE

        recent.forEachIndexed { index, profile ->
            val row = ItemProfileRowBinding.inflate(layoutInflater, container, false)
            row.rowAvatar.text = profile.initials()
            row.rowAvatar.background = UiKit.avatarBackground(profile.id)
            row.rowName.text = profile.displayName()
            row.rowMeta.text = listOf(
                profile.engine.title,
                profile.fp.os,
                if (profile.proxy.enabled) profile.proxy.label() else getString(R.string.direct)
            ).joinToString(" · ")
            row.rowDot.background = UiKit.dotBackground(metaDotColor(profile), container.context)
            val launch = { BrowserActivity.launch(requireContext(), profile.id) }
            row.root.setOnClickListener { launch() }
            row.rowAction.setOnClickListener { launch() }
            container.addView(row.root)
            if (index < recent.lastIndex) UiKit.divider(container, 64)
        }
    }

    private fun bindAlerts(target: FragmentHomeBinding, alerts: List<MonitorAlert>) {
        val container = target.alertContainer
        container.removeAllViews()
        // No alerts: the whole section collapses. Showing an empty card with "No alerts" made
        // the calm state the loudest thing on the dashboard.
        target.alertsSection.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE

        alerts.forEachIndexed { index, alert ->
            val row = ItemAlertBinding.inflate(layoutInflater, container, false)
            bindAlertRow(row, alert)
            container.addView(row.root)
            if (index < alerts.lastIndex) UiKit.divider(container, 0)
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

    }
}
