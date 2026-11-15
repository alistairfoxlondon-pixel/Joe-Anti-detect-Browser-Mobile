package com.joebrowser.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.databinding.ActivityMainBinding
import com.joebrowser.app.proxy.ProxyMonitorService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val fragments = HashMap<Int, Fragment>()

    private val profilesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppEvents.ACTION_PROFILE_CHANGED) refreshVisibleFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap the splash theme for the app theme before any view is created.
        setTheme(R.style.Theme_JoeBrowser)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        if (savedInstanceState == null) {
            val openTab = intent?.getStringExtra(EXTRA_OPEN_TAB)
            val tab = when (openTab) {
                "proxies" -> R.id.nav_proxies
                "profiles" -> R.id.nav_profiles
                "browsers" -> R.id.nav_browsers
                "extensions" -> R.id.nav_extensions
                else -> R.id.nav_home
            }
            binding.bottomNav.selectedItemId = tab
            showTab(tab)
        }

        requestNotificationPermissionOnce()
        // Restart the proxy monitor if proxies are watched.
        ProxyMonitorService.maybeStart(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val openTab = intent.getStringExtra(EXTRA_OPEN_TAB) ?: return
        val tab = when (openTab) {
            "proxies" -> R.id.nav_proxies
            "profiles" -> R.id.nav_profiles
            "browsers" -> R.id.nav_browsers
            "extensions" -> R.id.nav_extensions
            else -> R.id.nav_home
        }
        binding.bottomNav.selectedItemId = tab
        showTab(tab)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AppEvents.ACTION_PROFILE_CHANGED)
        ContextCompat.registerReceiver(this, profilesChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        try { unregisterReceiver(profilesChangedReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    private fun refreshVisibleFragment() {
        val visible = supportFragmentManager.fragments.firstOrNull { it.isAdded && !it.isHidden }
        (visible as? Refreshable)?.refresh()
    }

    fun setSelectedTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
        showTab(itemId)
    }

    private fun showTab(itemId: Int) {
        val tag = "tab_$itemId"
        val fm = supportFragmentManager
        var target = fm.findFragmentByTag(tag)
        if (target == null) {
            target = when (itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_profiles -> ProfilesFragment()
                R.id.nav_proxies -> ProxiesFragment()
                R.id.nav_browsers -> BrowsersFragment()
                R.id.nav_extensions -> ExtensionsFragment()
                else -> HomeFragment()
            }
        }
        val transaction = fm.beginTransaction()
        fm.fragments.forEach { transaction.hide(it) }
        if (target.isAdded) {
            transaction.show(target)
        } else {
            transaction.add(R.id.fragmentContainer, target, tag)
        }
        transaction.commitAllowingStateLoss()
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (Prefs.notificationAsked(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        Prefs.setNotificationAsked(this, true)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    /** Fragments that can refresh their content when profiles/proxies change. */
    interface Refreshable {
        fun refresh()
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        private const val REQ_NOTIFICATIONS = 7001
    }
}
