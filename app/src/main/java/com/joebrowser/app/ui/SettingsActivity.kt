package com.joebrowser.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.R
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.ExportFormat
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.databinding.ActivitySettingsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_JoeBrowser)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettingsBack.setOnClickListener { finish() }
        binding.settingsVersion.text = "Version ${appVersionName()} (build ${appVersionCode()})"

        // --- Appearance chips ---
        val themeOptions = listOf(0 to "System", 1 to "Dark", 2 to "Light")
        val currentTheme = Prefs.themeMode(this)
        val chipGroup = binding.themeChips
        themeOptions.forEach { (mode, label) ->
            chipGroup.addView(Chip(this).apply {
                text = label
                id = View.generateViewId()
                isCheckable = true
                isChecked = mode == currentTheme
                tag = mode
                setOnClickListener {
                    Prefs.setThemeMode(this@SettingsActivity, mode)
                }
            })
        }

        // --- Browsing ---
        binding.settingsStartUrl.setText(Prefs.defaultStartUrl(this))
        binding.settingsStartUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistStartUrl()
        }
        binding.settingsHistoryEnabled.isChecked = Prefs.historyEnabled(this)
        binding.settingsHistoryEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setHistoryEnabled(this, checked)
        }

        // --- Data & trash ---
        binding.settingsTrashEnabled.isChecked = Prefs.trashEnabled(this)
        binding.settingsTrashEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setTrashEnabled(this, checked)
        }
        binding.rowEmptyTrash.setOnClickListener {
            val count = Database.get(this).trashCount()
            if (count == 0) {
                android.widget.Toast.makeText(this, "Trash is already empty", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Empty trash?")
                .setMessage("$count deleted profile${if (count == 1) "" else "s"} will be permanently erased. This cannot be undone.")
                .setPositiveButton("Erase") { _, _ ->
                    Database.get(this).emptyTrash()
                    refreshTrashCount()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // --- Export ---
        binding.rowLicenses.setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        binding.btnExportWithSecrets.setOnClickListener { shareExport(includeSecrets = true) }
        binding.btnExportNoSecrets.setOnClickListener { shareExport(includeSecrets = false) }

        refreshTrashCount()
    }

    override fun onPause() {
        super.onPause()
        persistStartUrl()
    }

    private fun persistStartUrl() {
        val url = binding.settingsStartUrl.text?.toString()?.trim().orEmpty()
        if (url.isNotEmpty() && url != Prefs.defaultStartUrl(this)) Prefs.setDefaultStartUrl(this, url)
    }

    private fun refreshTrashCount() {
        binding.trashCount.text = "${Database.get(this).trashCount()} items"
    }

    private fun shareExport(includeSecrets: Boolean) {
        val db = Database.get(this)
        val profiles = db.getAllProfiles()
        val proxies = db.getAllProxies()
        val extensions = db.getAllExtensions()
        val json = ExportFormat.build(profiles, proxies, extensions, includeSecrets).toString(2)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val name = "joebrowser-backup-$stamp.json"

        try {
            val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, name)
            file.writeText(json)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Joe Browser backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export backup"))
        } catch (e: Exception) {
            // Fall back to sharing the raw JSON text if file sharing is unavailable.
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, name)
                putExtra(Intent.EXTRA_TEXT, json)
            }
            startActivity(Intent.createChooser(intent, "Export backup"))
        }
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun appVersionCode(): Long = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) packageManager.getPackageInfo(packageName, 0).longVersionCode
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
    } catch (e: Exception) { 0L }
}
