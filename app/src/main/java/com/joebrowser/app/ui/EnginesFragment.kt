package com.joebrowser.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.data.BrowserEngine
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.databinding.FragmentEnginesBinding
import com.joebrowser.app.databinding.ItemEngineCardBinding
import com.joebrowser.app.fingerprint.FingerprintFactory

/**
 * Engine catalogue.
 *
 * Five cards, one per engine family, all inflated from [ItemEngineCardBinding] so they cannot
 * drift apart. The card carries the official brand mark, the usage count and a single create
 * action — the build list lives in the profile editor, where it is actually a choice.
 */
class EnginesFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentEnginesBinding? = null
    private val binding get() = _binding!!

    private var usage: Map<BrowserEngine, Int> = emptyMap()
    private var totalProfiles: Int = 0

    /** Usage counts change when a profile is created, edited or deleted in another tab. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppEvents.ACTION_DATA_CHANGED) refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEnginesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        render()
    }

    override fun onStart() {
        super.onStart()
        AppEvents.register(requireContext().applicationContext, receiver, listOf(AppEvents.ACTION_DATA_CHANGED))
    }

    override fun onStop() {
        runCatching { context?.applicationContext?.let { AppEvents.unregister(it, receiver) } }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (_binding == null) return
        ProfileRepository.profiles(requireContext().applicationContext) { profiles ->
            if (_binding == null) return@profiles
            totalProfiles = profiles.size
            usage = BrowserEngine.entries.associateWith { engine -> profiles.count { it.engine == engine } }
            render()
        }
    }

    private fun render() {
        val container = binding.engineContainer
        container.removeAllViews()
        binding.enginesSubtitle.text = getString(R.string.engines_subtitle_count, totalProfiles)
        BrowserEngine.entries.forEach { engine ->
            val card = ItemEngineCardBinding.inflate(layoutInflater, container, false)
            val usedBy = usage[engine] ?: 0

            card.engineLogo.setImageResource(brandLogoRes(engine))
            card.engineName.text = engine.title
            // No count for an engine nobody uses: "Unused" on four of five cards was the
            // loudest text on the screen and said nothing.
            card.engineCount.visibility = if (usedBy > 0) View.VISIBLE else View.GONE
            card.engineCount.text = resources.getQuantityString(
                R.plurals.engines_profile_count, usedBy, usedBy
            )
            // Major versions only: a full four-part build string is noise at a glance, and the
            // exact build is on the profile card and in the editor.
            card.engineBuild.text = FingerprintFactory.buildsFor(engine)
                .take(2)
                .map { it.substringBefore('.') }
                .joinToString(" · ")
            card.engineCreate.setOnClickListener {
                ProfileEditorActivity.launchForEngine(requireContext(), engine)
            }
            card.engineRoot.setOnClickListener {
                ProfileEditorActivity.launchForEngine(requireContext(), engine)
            }
            container.addView(card.root)
        }
    }

    private fun brandLogoRes(engine: BrowserEngine): Int = when (engine) {
        BrowserEngine.CHROME -> R.drawable.logo_chrome
        BrowserEngine.CHROMIUM -> R.drawable.logo_chromium
        BrowserEngine.BRAVE -> R.drawable.logo_brave
        BrowserEngine.EDGE -> R.drawable.logo_edge
        BrowserEngine.FIREFOX -> R.drawable.logo_firefox
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
