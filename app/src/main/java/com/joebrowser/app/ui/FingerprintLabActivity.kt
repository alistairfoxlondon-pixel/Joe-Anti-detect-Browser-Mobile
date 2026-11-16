package com.joebrowser.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.fingerprint.FingerprintFactory
import com.joebrowser.app.databinding.ActivityFingerprintLabBinding

/**
 * Fingerprint lab.
 *
 * The point of this screen is to make "does my fingerprint hold up?" a two-tap question:
 * pick a profile, tap a detector, and the site opens *inside* that profile's isolated session
 * so what is measured is the real thing.
 *
 * The local consistency block is computed on-device from [FingerprintFactory.coherence] before
 * any network call, so a user can see whether the identity contradicts itself without waiting
 * for a third-party service to tell them.
 */
class FingerprintLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFingerprintLabBinding

    private var profiles: List<Profile> = emptyList()
    private var current: Profile? = null

    /** Detector services come from the shared brand model so every screen agrees. */
    private val sites: List<SiteBrand> = SiteBrand.all

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFingerprintLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLabBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.labProfileCard.setOnClickListener { pickProfile() }
        binding.labSwitch.setOnClickListener { pickProfile() }
        binding.labRunAll.setOnClickListener { openSite(SiteBrand.BROWSERSCAN) }
        binding.labAuditAll.setOnClickListener {
            startActivity(Intent(this, FingerprintBatchActivity::class.java))
        }

        val requestedId = intent.getStringExtra(EXTRA_PROFILE_ID)
        ProfileRepository.profiles(this) { list ->
            profiles = list
            current = list.firstOrNull { it.id == requestedId } ?: list.firstOrNull()
            bindProfile()
            buildSiteGrid()
            bindConsistency()
        }
    }

    // ---------------------------------------------------------------- profile

    private fun bindProfile() {
        val profile = current
        if (profile == null) {
            binding.labProfileName.setText(R.string.lab_no_profile)
            binding.labProfileMeta.text = ""
            binding.labAvatar.text = "–"
            binding.labAvatar.background = UiKit.avatarBackground("empty")
            binding.labRunAll.isEnabled = false
            return
        }
        binding.labRunAll.isEnabled = true
        binding.labAvatar.text = profile.initials()
        binding.labAvatar.background = UiKit.avatarBackground(profile.id)
        binding.labProfileName.text = profile.displayName()
        binding.labProfileMeta.text = listOf(
            profile.engine.title,
            profile.fp.os,
            profile.fp.timezone,
            profile.fp.language
        ).joinToString(" · ")
    }

    private fun pickProfile() {
        if (profiles.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lab_no_profile)
                .setMessage(R.string.lab_no_profile_body)
                .setPositiveButton(R.string.profiles_create_first) { _, _ ->
                    startActivity(Intent(this, ProfileEditorActivity::class.java))
                    finish()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        val labels = profiles.map { "${it.displayName()} · ${it.engine.title}" }.toTypedArray()
        val checked = profiles.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lab_switch)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                current = profiles[which]
                bindProfile()
                bindConsistency()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------ consistency

    private fun bindConsistency() {
        val container = binding.labFactContainer
        container.removeAllViews()
        val profile = current
        if (profile == null) {
            binding.labScore.text = "—"
            return
        }

        val report = FingerprintFactory.coherence(profile.fp, profile.engine)
        binding.labScore.text = getString(R.string.lab_score_value, report.score)
        binding.labScore.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    report.score >= 90 -> R.color.success
                    report.score >= 70 -> R.color.warning
                    else -> R.color.danger
                }
            )
        )

        val facts = listOf(
            getString(R.string.lab_fact_ua) to profile.browserVersion,
            getString(R.string.lab_fact_screen) to "${profile.fp.screenW}×${profile.fp.screenH} @${profile.fp.pixelRatio}x",
            getString(R.string.lab_fact_gpu) to profile.fp.webglRenderer.substringAfterLast(", ").take(28),
            getString(R.string.lab_fact_tz) to profile.fp.timezone,
            getString(R.string.lab_fact_lang) to profile.fp.language,
            getString(R.string.lab_fact_cores) to "${profile.fp.cores} / ${profile.fp.memory} GB"
        )
        facts.forEach { (label, value) -> container.addView(factRow(label, value)) }

        // Any contradiction the generator can detect is surfaced here, by name.
        report.issues.take(4).forEach { issue ->
            container.addView(issueRow(issue))
        }
    }

    private fun factRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@FingerprintLabActivity, 5) }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@FingerprintLabActivity, R.color.text_muted))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(this@FingerprintLabActivity, R.color.text))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.END
        })
        return row
    }

    private fun issueRow(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@FingerprintLabActivity, R.color.warning))
        setPadding(0, UiKit.dp(this@FingerprintLabActivity, 7), 0, 0)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    // ------------------------------------------------------------------ sites

    private fun buildSiteGrid() {
        val grid = binding.labSiteGrid
        grid.removeAllViews()
        // One full-width row per service: at 34dp the plate plus two lines of text do not fit in
        // a half-width column on a small phone, and a clipped service name is worse than a
        // longer list.
        sites.forEachIndexed { index, site ->
            grid.addView(siteTile(site).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = UiKit.dp(this@FingerprintLabActivity, 8) }
            })
        }
    }

    /**
     * One detector tile.
     *
     * The mark comes from [SiteBrand]: a real vector brand mark where the vendor publishes one,
     * otherwise the service's own wordmark initial on a plate in its brand colour. A generic
     * fingerprint glyph for all eight services made the grid unreadable at a glance.
     */
    private fun siteTile(site: SiteBrand): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = ContextCompat.getDrawable(this@FingerprintLabActivity, R.drawable.bg_card)
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(
            UiKit.dp(this@FingerprintLabActivity, 12),
            UiKit.dp(this@FingerprintLabActivity, 12),
            UiKit.dp(this@FingerprintLabActivity, 12),
            UiKit.dp(this@FingerprintLabActivity, 12)
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { openSite(site) }

        val plate = TextView(this@FingerprintLabActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@FingerprintLabActivity, 34),
                UiKit.dp(this@FingerprintLabActivity, 34)
            )
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val mark = android.widget.ImageView(this@FingerprintLabActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@FingerprintLabActivity, 34),
                UiKit.dp(this@FingerprintLabActivity, 34)
            )
            contentDescription = null
        }
        site.bindPlate(this@FingerprintLabActivity, plate, mark)
        addView(plate)
        addView(mark)

        val text = LinearLayout(this@FingerprintLabActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = UiKit.dp(this@FingerprintLabActivity, 10) }
        }
        text.addView(TextView(this@FingerprintLabActivity).apply {
            this.text = site.label
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@FingerprintLabActivity, R.color.text))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        text.addView(TextView(this@FingerprintLabActivity).apply {
            this.text = site.subtitle()
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@FingerprintLabActivity, R.color.text_muted))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(text)

        addView(android.widget.ImageView(this@FingerprintLabActivity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this@FingerprintLabActivity, R.color.text_muted)
            )
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@FingerprintLabActivity, 16),
                UiKit.dp(this@FingerprintLabActivity, 16)
            )
        })
    }

    private fun openSite(site: SiteBrand) {
        val profile = current
        if (profile == null) {
            pickProfile()
            return
        }
        BrowserActivity.launch(this, profile.id, site.url)
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(context: android.content.Context, profileId: String?) {
            val intent = Intent(context, FingerprintLabActivity::class.java)
            if (!profileId.isNullOrBlank()) intent.putExtra(EXTRA_PROFILE_ID, profileId)
            context.startActivity(intent)
        }
    }
}
