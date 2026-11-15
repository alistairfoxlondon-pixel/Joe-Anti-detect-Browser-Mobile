package com.joebrowser.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.databinding.ActivityMainBinding
import com.joebrowser.app.proxy.ProxyMonitorService

/**
 * Single-activity shell.
 *
 * Tab switching uses `show`/`hide` on retained fragments rather than `replace`, so scroll
 * position and in-progress work survive navigation — this is what makes screen-to-screen
 * transitions feel instant instead of re-querying the database on every tap.
 *
 * The activity also owns the process-wide data-change receiver: one receiver fans out to the
 * visible fragment, so five tabs do not each register their own.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tagHome = "tab_home"
    private val tagProfiles = "tab_profiles"
    private val tagProxies = "tab_proxies"
    private val tagEngines = "tab_engines"
    private val tagTools = "tab_tools"

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AppEvents.ACTION_DATA_CHANGED,
                AppEvents.ACTION_PROXY_STATE,
                AppEvents.ACTION_MONITOR_ALERT -> refreshVisibleFragment()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_JoeBrowser)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHistory.setOnClickListener { openSafely(HistoryActivity::class.java) }
        binding.btnSettings.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_tools
            showTab(R.id.nav_tools)
        }
        binding.versionLabel.text = getString(R.string.app_name)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        if (savedInstanceState == null) {
            val initial = tabFromExtra(intent?.getStringExtra(EXTRA_OPEN_TAB))
            binding.bottomNav.selectedItemId = initial
            showTab(initial)
        }

        requestNotificationPermissionOnce()
        ProxyMonitorService.startIfNeeded(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = tabFromExtra(intent.getStringExtra(EXTRA_OPEN_TAB))
        binding.bottomNav.selectedItemId = tab
        showTab(tab)
    }

    override fun onStart() {
        super.onStart()
        AppEvents.register(
            this, dataReceiver,
            listOf(AppEvents.ACTION_DATA_CHANGED, AppEvents.ACTION_PROXY_STATE, AppEvents.ACTION_MONITOR_ALERT)
        )
    }

    override fun onStop() {
        AppEvents.unregister(this, dataReceiver)
        super.onStop()
    }

    private fun tabFromExtra(extra: String?): Int = when (extra) {
        "proxies" -> R.id.nav_proxies
        "profiles" -> R.id.nav_profiles
        "engines", "browsers" -> R.id.nav_browsers
        "tools", "settings", "extensions" -> R.id.nav_tools
        else -> R.id.nav_home
    }

    /** Programmatic tab selection used by the dashboard's shortcuts. */
    fun setSelectedTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
        showTab(itemId)
    }

    fun openProfileEditor(profileId: String? = null) {
        val intent = Intent(this, ProfileEditorActivity::class.java)
        if (profileId != null) intent.putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, profileId)
        openSafely(intent)
    }

    private fun showTab(itemId: Int) {
        Async.safe {
            val manager = supportFragmentManager
            val tag = when (itemId) {
                R.id.nav_profiles -> tagProfiles
                R.id.nav_proxies -> tagProxies
                R.id.nav_browsers -> tagEngines
                R.id.nav_tools -> tagTools
                else -> tagHome
            }
            val target = manager.findFragmentByTag(tag) ?: when (itemId) {
                R.id.nav_profiles -> ProfilesFragment()
                R.id.nav_proxies -> ProxiesFragment()
                R.id.nav_browsers -> EnginesFragment()
                R.id.nav_tools -> ToolsFragment()
                else -> HomeFragment()
            }

            val transaction = manager.beginTransaction()
            // Smooth, cheap transitions: fragments are retained, only visibility changes.
            transaction.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            manager.fragments.forEach { existing -> if (existing.tag != tag && !existing.isHidden) transaction.hide(existing) }
            if (target.isAdded) transaction.show(target) else transaction.add(R.id.fragmentContainer, target, tag)
            transaction.commitAllowingStateLoss()

            binding.tabScrim.visibility = View.GONE
        }
    }

    private fun refreshVisibleFragment() {
        val manager = supportFragmentManager
        val visible = manager.fragments.firstOrNull { it.isAdded && !it.isHidden }
        (visible as? Refreshable)?.refresh()
    }

    private fun openSafely(clazz: Class<*>) {
        openSafely(Intent(this, clazz))
    }

    private fun openSafely(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure {
                Log.w(Async.TAG, "Could not open screen", it)
                Async.toast(this, getString(R.string.error_generic))
            }
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (Prefs.notificationAsked(this)) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        Prefs.setNotificationAsked(this, true)
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    /** Fragments implement this to re-read their data when the repository changes. */
    interface Refreshable {
        fun refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            runCatching { ProxyMonitorService.startIfNeeded(this) }
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        private const val REQ_NOTIFICATIONS = 7001
    }
}
