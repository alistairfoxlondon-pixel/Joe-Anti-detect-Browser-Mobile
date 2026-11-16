package com.joebrowser.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.joebrowser.app.R
import com.joebrowser.app.databinding.ActivityLicensesBinding

/**
 * Third-party licences and platform notes.
 *
 * The list is intentionally accurate: only components that ship in the APK are named, and the
 * security note states plainly what the app can and cannot do. Nothing here is aspirational.
 */
class LicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    private data class Entry(val name: String, val license: String, val url: String)

    private val entries = listOf(
        Entry("AndroidX Core / AppCompat / Fragment / RecyclerView", "Apache License 2.0", "developer.android.com/jetpack/androidx"),
        Entry("Material Components for Android", "Apache License 2.0", "github.com/material-components/material-components-android"),
        Entry("AndroidX WebKit (ProfileStore, document-start injection)", "Apache License 2.0", "developer.android.com/reference/androidx/webkit"),
        Entry("Kotlin standard library", "Apache License 2.0", "kotlinlang.org"),
        Entry("Inter typeface (every UI string)", "SIL Open Font License 1.1", "github.com/rsms/inter"),
        Entry("Lucide icon set (every UI icon)", "ISC License", "lucide.dev"),
        Entry("ip-api.com geolocation endpoint", "Free tier, rate limited", "ip-api.com"),
        Entry("api.ipify.org IP echo", "Free service", "www.ipify.org")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLicensesBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.licensesVersion.text = getString(
            R.string.licenses_version,
            runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "?"
        )

        entries.forEach { entry -> binding.licensesContainer.addView(buildCard(entry)) }
        binding.licensesContainer.addView(buildCard(Entry(getString(R.string.licenses_privacy_title), getString(R.string.licenses_privacy_body), "")))
    }

    private fun buildCard(entry: Entry): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = buildString {
                append(entry.name)
                append("\n")
                append(entry.license)
                if (entry.url.isNotBlank()) {
                    append("\n")
                    append(entry.url)
                }
            }
            setTextAppearance(R.style.Text_Jb_Body)
            setLineSpacing(0f, 1.25f)
            setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            setTextIsSelectable(true)
            background = ContextCompat.getDrawable(context, R.drawable.bg_card)
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        }
    }
}
