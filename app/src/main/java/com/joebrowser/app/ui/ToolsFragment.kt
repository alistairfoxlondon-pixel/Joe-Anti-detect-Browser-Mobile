package com.joebrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.R
import com.joebrowser.app.browser.HumanPaste
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.data.ProfileStorage
import com.joebrowser.app.databinding.FragmentToolsBinding
import com.joebrowser.app.proxy.ProxyMonitorEngine
import com.joebrowser.app.proxy.ProxyMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tools / settings tab.
 *
 * Everything here is a real setting with an immediate effect:
 *  - monitor enable/disable and cadence → restarts (or stops) the foreground service,
 *  - human-typing default speed → used by every new "Paste as human" action,
 *  - history/tracker/selection switches → read by the browser activity at request time,
 *  - theme → applied process-wide through AppCompatDelegate,
 *  - backup export → real JSON through the share sheet,
 *  - trash / storage / reset → real filesystem and database operations.
 */
class ToolsFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindAppearance()
        bindMonitor()
        bindTyping()
        bindBrowsing()
        bindData()
        bindAbout()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // -------------------------------------------------------------- appearance

    private fun bindAppearance() {
        val current = Prefs.themeMode(requireContext())
        val group = binding.themeChips
        group.removeAllViews()
        (0..2).forEach { mode ->
            group.addView(Chip(requireContext()).apply {
                id = View.generateViewId()
                text = Prefs.themeLabel(mode)
                isCheckable = true
                isChecked = mode == current
                setOnClickListener {
                    Prefs.setThemeMode(requireContext(), mode)
                    group.check(id)
                    (activity as? MainActivity)?.recreate()
                }
            })
        }
    }

    // ----------------------------------------------------------------- monitor

    private fun bindMonitor() {
        binding.switchMonitor.isChecked = Prefs.monitorEnabled(requireContext())
        binding.switchMonitor.setOnCheckedChangeListener { _, checked ->
            Prefs.setMonitorEnabled(requireContext(), checked)
            if (checked) ProxyMonitorService.startIfNeeded(requireContext())
            else ProxyMonitorService.stop(requireContext())
            refreshMonitorStatus()
        }
        binding.valueMonitorInterval.text = intervalLabel(Prefs.monitorIntervalSec(requireContext()))
        binding.rowMonitorInterval.setOnClickListener { showIntervalDialog() }
    }

    private fun intervalLabel(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds / 60 == 1 -> "1 min"
        else -> "${seconds / 60} min"
    }

    private fun showIntervalDialog() {
        val options = listOf(30, 60, 120, 300, 600)
        val labels = options.map { intervalLabel(it) }.toTypedArray()
        val current = options.indexOf(Prefs.monitorIntervalSec(requireContext())).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tools_monitor_interval)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setMonitorIntervalSec(requireContext(), options[which])
                binding.valueMonitorInterval.text = labels[which]
                ProxyMonitorService.startIfNeeded(requireContext())
                refreshMonitorStatus()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun refreshMonitorStatus() {
        val context = requireContext().applicationContext
        Async.compute({
            val watching = ProxyMonitorEngine.watchedCount(context)
            watching to ProxyMonitorEngine.statusLine(context)
        }) { result ->
            val target = _binding ?: return@compute
            val (watching, line) = result ?: (0 to "")
            target.valueMonitorStatus.text = line
            target.valueMonitorStatus.setTextColor(
                ContextCompat.getColor(
                    target.root.context, if (watching > 0) R.color.success else R.color.text_muted
                )
            )
        }
    }

    // ------------------------------------------------------------------ typing

    private fun bindTyping() {
        binding.switchHumanPaste.isChecked = Prefs.humanPasteEnabled(requireContext())
        binding.switchHumanPaste.setOnCheckedChangeListener { _, checked ->
            Prefs.setHumanPasteEnabled(requireContext(), checked)
        }

        val group = binding.speedChips
        group.removeAllViews()
        val current = HumanPaste.speedById(Prefs.humanPasteSpeed(requireContext()))
        HumanPaste.speeds.forEach { speed ->
            group.addView(Chip(requireContext()).apply {
                id = View.generateViewId()
                text = speed.label
                isCheckable = true
                isChecked = speed.id == current.id
                setOnClickListener {
                    Prefs.setHumanPasteSpeed(requireContext(), speed.id)
                    binding.humanSpeedHint.text = speed.description
                    group.check(id)
                }
            })
        }
        binding.humanSpeedHint.text = current.description
    }

    // ---------------------------------------------------------------- browsing

    private fun bindBrowsing() {
        binding.inputStartUrl.setText(Prefs.defaultStartUrl(requireContext()))
        binding.inputStartUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistStartUrl()
        }

        binding.switchHistory.isChecked = Prefs.historyEnabled(requireContext())
        binding.switchHistory.setOnCheckedChangeListener { _, checked ->
            Prefs.setHistoryEnabled(requireContext(), checked)
        }

        binding.switchTracker.isChecked = Prefs.trackerEnabled(requireContext())
        binding.switchTracker.setOnCheckedChangeListener { _, checked ->
            Prefs.setTrackerEnabled(requireContext(), checked)
        }

        binding.switchUnlockSelection.isChecked = Prefs.unlockSelection(requireContext())
        binding.switchUnlockSelection.setOnCheckedChangeListener { _, checked ->
            Prefs.setUnlockSelection(requireContext(), checked)
        }
    }

    private fun persistStartUrl() {
        val value = binding.inputStartUrl.text?.toString()?.trim().orEmpty()
        if (value.isNotEmpty()) Prefs.setDefaultStartUrl(requireContext(), value)
    }

    // -------------------------------------------------------------------- data

    private fun bindData() {
        binding.switchTrash.isChecked = Prefs.trashEnabled(requireContext())
        binding.switchTrash.setOnCheckedChangeListener { _, checked ->
            Prefs.setTrashEnabled(requireContext(), checked)
        }

        binding.rowEmptyTrash.setOnClickListener { emptyTrash() }

        binding.btnExportFull.setOnClickListener { exportBackup(includeSecrets = true) }
        binding.btnExportSafe.setOnClickListener { exportBackup(includeSecrets = false) }

        binding.btnReset.setOnClickListener { confirmReset() }
        binding.switchJitter.isChecked = Prefs.humanPasteJitter(requireContext())
        binding.switchJitter.setOnCheckedChangeListener { _, checked ->
            Prefs.setHumanPasteJitter(requireContext(), checked)
        }
    }

    private fun emptyTrash() {
        val context = requireContext()
        Async.compute({ Database.get(context).trashCount() }) { count ->
            val trash = count ?: 0
            if (trash == 0) {
                Async.toast(context, "Trash is empty")
                return@compute
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.tools_empty_trash)
                .setMessage("$trash profile(s) will be permanently erased.")
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    ProfileRepository.emptyTrash(context) { refresh() }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun exportBackup(includeSecrets: Boolean) {
        val context = requireContext()
        ProfileRepository.exportBackup(context, includeSecrets) { json ->
            if (json == null) {
                Async.toast(context, getString(R.string.error_generic))
                return@exportBackup
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val fileName = "joebrowser-backup-$stamp.json"
            Async.safe {
                val directory = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                val file = java.io.File(directory, fileName)
                file.writeText(json)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Joe Browser backup")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.tools_export_chooser)))
            }
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tools_reset_title)
            .setMessage(R.string.tools_reset_body)
            .setPositiveButton(R.string.tools_reset) { _, _ ->
                val context = requireContext()
                ProfileRepository.resetEverything(context) {
                    ProxyMonitorService.stop(context)
                    Async.toast(context, getString(R.string.status_deleted))
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------- about

    private fun bindAbout() {
        binding.valueVersion.text = getString(
            R.string.tools_version,
            versionName(),
            versionCode().toString()
        )
        binding.rowLicenses.setOnClickListener {
            Async.safe { startActivity(Intent(requireContext(), LicensesActivity::class.java)) }
        }
    }

    private fun versionName(): String = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    private fun versionCode(): Long = try {
        val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (t: Throwable) {
        0L
    }

    // ----------------------------------------------------------------- refresh

    override fun refresh() {
        if (_binding == null || !isAdded) return
        val context = requireContext().applicationContext
        refreshMonitorStatus()

        ProfileRepository.trashCount(context) { count ->
            _binding?.valueTrashCount?.text = getString(R.string.tools_trash_count, count)
        }
        Async.compute { ProfileStorage.totalSizeBytes(context) } { bytes ->
            _binding?.valueStorageUsed?.text = ProfileStorage.humanSize(bytes ?: 0L)
        }
    }

    override fun onDestroyView() {
        persistStartUrl()
        _binding = null
        super.onDestroyView()
    }
}
