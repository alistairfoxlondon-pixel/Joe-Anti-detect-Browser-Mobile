package com.joebrowser.app.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.databinding.FragmentHomeBinding
import com.joebrowser.app.databinding.ItemRecentProfileBinding

class HomeFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnNewProfile.setOnClickListener {
            startActivity(Intent(requireContext(), ProfileEditorActivity::class.java))
        }
        binding.btnPasteProxy.setOnClickListener {
            (activity as? MainActivity)?.setSelectedTab(R.id.nav_proxies)
        }
        binding.statProfiles.setOnClickListener { (activity as? MainActivity)?.setSelectedTab(R.id.nav_profiles) }
        binding.statProxies.setOnClickListener { (activity as? MainActivity)?.setSelectedTab(R.id.nav_proxies) }
        binding.statExtensions.setOnClickListener { (activity as? MainActivity)?.setSelectedTab(R.id.nav_extensions) }
        binding.seeAllProfiles.setOnClickListener { (activity as? MainActivity)?.setSelectedTab(R.id.nav_profiles) }
        buildFingerprintChips()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildFingerprintChips() {
        val chips = binding.fingerprintChips
        chips.removeAllViews()
        val sites = listOf(
            "BrowserScan" to "https://www.browserscan.net/",
            "IPHey" to "https://iphey.com/",
            "PixelScan" to "https://pixelscan.net/",
            "IPFighter" to "https://www.ipfighter.com/browser-fingerprint",
            "Whoer" to "https://whoer.net/"
        )
        sites.forEach { (label, url) ->
            chips.addView(Chip(requireContext()).apply {
                text = label
                isCheckable = false
                setOnClickListener { launchFingerprintSite(url) }
            })
        }
    }

    private fun launchFingerprintSite(url: String) {
        val db = Database.get(requireContext())
        val profiles = db.getAllProfiles()
        val target = profiles.firstOrNull { it.id == Prefs.lastProfileId(requireContext()) }
            ?: profiles.firstOrNull()
        if (target == null) {
            android.widget.Toast.makeText(requireContext(), "Create a profile first", android.widget.Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), ProfileEditorActivity::class.java))
            return
        }
        BrowserActivity.launch(requireContext(), target.id, url)
    }

    override fun refresh() {
        if (_binding == null) return
        val db = Database.get(requireContext())
        val profiles = db.getAllProfiles()
        val savedProxies = db.getAllProxies()
        val online = savedProxies.count { it.config.lastIp.isNotBlank() && it.config.latencyMs >= 0 }
        binding.statProfilesNumber.text = profiles.size.toString()
        binding.statProxiesNumber.text = "$online/${savedProxies.size}"
        binding.statExtensionsNumber.text = db.getAllExtensions().size.toString()

        val container = binding.recentProfiles
        container.removeAllViews()
        val recent = profiles.sortedByDescending { maxOf(it.lastOpened, it.created) }.take(4)
        if (recent.isEmpty()) {
            val empty = android.widget.TextView(requireContext()).apply {
                text = "No profiles yet — create the first identity."
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_faint))
                setPadding(4, 18, 4, 8)
            }
            container.addView(empty)
        } else {
            recent.forEach { p ->
                val row = ItemRecentProfileBinding.inflate(layoutInflater, container, false)
                row.recentAvatar.text = p.initials()
                row.recentAvatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(UiKit.avatarColor(p.id))
                }
                row.recentName.text = p.name.ifBlank { "Untitled profile" }
                val proxy = if (p.proxy.enabled) p.proxy.label() else "Direct"
                row.recentMeta.text = "${p.fp.os} · $proxy · ${p.browserKind.replaceFirstChar { c -> c.uppercase() }}"
                row.root.setOnClickListener { BrowserActivity.launch(requireContext(), p.id) }
                container.addView(row.root)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
