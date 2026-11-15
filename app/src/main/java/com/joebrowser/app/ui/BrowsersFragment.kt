package com.joebrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.joebrowser.app.R
import com.joebrowser.app.databinding.FragmentBrowsersBinding
import com.joebrowser.app.fingerprint.FingerprintFactory
import com.joebrowser.app.data.Database

/**
 * Browser identities the engine can report. Rendering is always the system WebView;
 * what changes is everything a fingerprint script can observe (brand, build, UA, GPU, touch).
 */
class BrowsersFragment : Fragment() {

    private var _binding: FragmentBrowsersBinding? = null
    private val binding get() = _binding!!

    private data class Engine(
        val kind: String,
        val title: String,
        val engine: String,
        val notes: String,
        val builds: List<String>
    )

    private val engines = listOf(
        Engine("chrome", "Google Chrome", "Chromium",
            "The default. Pretends to be stable Chrome on the chosen OS; every profile editor choice (OS, screen, GPU) stays coherent with it.",
            FingerprintFactory.chromiumBuilds),
        Engine("chromium", "Chromium", "Chromium",
            "Bare Chromium reporting, no proprietary brand tokens. Same engine surface, slightly different brand string.",
            FingerprintFactory.chromiumBuilds),
        Engine("brave", "Brave", "Chromium",
            "Reports as Brave (aggressive fingerprint blocking heuristics included). Useful against sites that white-list Brave.",
            FingerprintFactory.chromiumBuilds),
        Engine("edge", "Microsoft Edge", "Chromium",
            "Reports the Edg/ brand token. Pairs naturally with Windows identities.",
            FingerprintFactory.chromiumBuilds),
        Engine("firefox", "Mozilla Firefox", "Gecko",
            "Reports Firefox with rv: tokens. Note: rendering is still WebView, so deep Gecko-specific surface (SpiderMonkey quirks) is not replicated — treat Firefox identities as UA-level.",
            FingerprintFactory.firefoxBuilds),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowsersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val container = binding.browserEngineContainer
        container.removeAllViews()
        engines.forEach { container.addView(buildEngineCard(it)) }
    }

    private fun buildEngineCard(engine: Engine): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            setBackgroundResource(R.drawable.bg_card_surface)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            isClickable = true
            isFocusable = true
            foreground = ContextCompat.getDrawable(ctx, android.R.drawable.list_selector_background)
        }
        val usedBy = Database.get(ctx).getAllProfiles().count { it.browserKind == engine.kind }

        card.addView(TextView(ctx).apply {
            text = engine.title
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(ctx).apply {
            text = engine.engine + if (usedBy > 0) " · used by $usedBy profile${if (usedBy == 1) "" else "s"}" else " · not used yet"
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(TextView(ctx).apply {
            text = engine.notes
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(ctx).apply {
            text = "Latest builds: " + engine.builds.take(4).joinToString(" · ")
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(ctx, R.color.text_faint))
            setPadding(0, dp(6), 0, 0)
        })
        val create = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Create profile with ${engine.title}"
            textSize = 12f
            isAllCaps = false
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                startActivity(Intent(ctx, ProfileEditorActivity::class.java)
                    .putExtra(ProfileEditorActivity.EXTRA_BROWSER_KIND, engine.kind))
            }
        }
        card.addView(create, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
        ).apply { topMargin = dp(10) })
        return card
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
