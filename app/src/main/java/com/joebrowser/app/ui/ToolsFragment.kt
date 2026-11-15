package com.joebrowser.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.auth.LocalAccount
import com.joebrowser.app.browser.HumanPaste
import com.joebrowser.app.core.Async
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
 * Every row here changes something real and takes effect immediately:
 *  - monitor cadence → restarts the foreground service,
 *  - typing speed / rhythm → read by every "Paste as human" run,
 *  - history / tracker / selection → read by the browser at request time,
 *  - theme → applied process-wide through AppCompatDelegate,
 *  - backup / restore / trash / reset → real filesystem and database operations.
 *
 * The screen is assembled from three row layouts ([item_switch_row], [item_value_row] and the
 * speed/theme chip groups), so adding a setting is one `<include>` and four lines of binding.
 */
class ToolsFragment : Fragment(), MainActivity.Refreshable {

    private val backupPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val context = requireContext()
        ProfileRepository.readBackup(context, uri) { restore ->
            if (!isAdded) return@readBackup
            if (restore == null) {
                Async.toast(context, getString(R.string.tools_import_invalid))
                return@readBackup
            }
            if (restore.total == 0) {
                Async.toast(context, getString(R.string.tools_import_empty))
                return@readBackup
            }
            val body = buildString {
                append(getString(R.string.tools_import_confirm, restore.profiles.size, restore.proxies.size))
                if (restore.hadCredentials) append("\n\n").append(getString(R.string.tools_import_credentials))
                val skipped = restore.skippedProfiles + restore.skippedProxies
                if (skipped > 0) append("\n\n").append(getString(R.string.tools_import_skipped, skipped))
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.tools_import)
                .setMessage(body)
                .setPositiveButton(R.string.tools_import) { _, _ ->
                    ProfileRepository.restoreBackup(context, restore) { outcome ->
                        Async.toast(context, getString(R.string.tools_import_done, outcome.profiles, outcome.proxies))
                        if (outcome.skipped > 0) {
                            Async.toast(context, getString(R.string.tools_import_skipped, outcome.skipped))
                        }
                        ProxyMonitorService.startIfNeeded(context)
                        refresh()
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!

    /** Live values (monitor status, storage, trash count) must not go stale behind a tab switch. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action in TRACKED_ACTIONS) refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindAccount()
        bindAppearance()
        bindMonitor()
        bindTyping()
        bindBrowsing()
        bindData()
        bindAbout()
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

    // ------------------------------------------------------------------ account

    private fun bindAccount() {
        binding.accountEmail.text = LocalAccount.email(requireContext()) ?: getString(R.string.tools_account)

        binding.btnLockApp.setOnClickListener { lockApp() }
        binding.btnSignOut.setOnClickListener { confirmSignOut() }
    }

    /**
     * Lock = forget the in-memory unlock and return to the gate. Sign out does the same thing
     * but also drops the activity stack, so the next launch starts at the gate as well.
     */
    private fun lockApp() {
        LocalAccount.lock()
        val gate = Intent(requireContext(), AccountActivity::class.java)
        gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(gate)
        requireActivity().finishAffinity()
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tools_sign_out_title)
            .setMessage(R.string.tools_sign_out_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.tools_sign_out) { _, _ -> lockApp() }
            .show()
    }

    // --------------------------------------------------------------- appearance

    private fun bindAppearance() {
        val group = binding.themeChips
        group.removeAllViews()
        val current = Prefs.themeMode(requireContext())
        (0..2).forEach { mode ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = Prefs.themeLabel(mode)
                isCheckable = true
                isChecked = mode == current
                setOnClickListener {
                    Prefs.setThemeMode(requireContext(), mode)
                    group.check(id)
                    (activity as? MainActivity)?.recreate()
                }
            }
            group.addView(chip)
        }
    }

    // ------------------------------------------------------------------ monitor

    private fun bindMonitor() {
        binding.rowMonitorEnabled.rowIcon.setImageResource(R.drawable.ic_monitor)
        binding.rowMonitorEnabled.rowTitle.setText(R.string.tools_monitor_enabled)
        binding.rowMonitorEnabled.rowSwitch.isChecked = Prefs.monitorEnabled(requireContext())
        binding.rowMonitorEnabled.rowSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setMonitorEnabled(requireContext(), checked)
            if (checked) ProxyMonitorService.startIfNeeded(requireContext())
            else ProxyMonitorService.stop(requireContext())
            refreshMonitorStatus()
        }
        binding.rowMonitorEnabled.rowRoot.setOnClickListener {
            binding.rowMonitorEnabled.rowSwitch.isChecked = !binding.rowMonitorEnabled.rowSwitch.isChecked
        }

        binding.rowAutoRotate.rowIcon.setImageResource(R.drawable.ic_refresh)
        binding.rowAutoRotate.rowTitle.setText(R.string.tools_auto_rotate)
        binding.rowAutoRotate.rowSwitch.isChecked = Prefs.autoRotateOnFailure(requireContext())
        binding.rowAutoRotate.rowSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoRotateOnFailure(requireContext(), checked)
        }
        binding.rowAutoRotate.rowRoot.setOnClickListener {
            binding.rowAutoRotate.rowSwitch.isChecked = !binding.rowAutoRotate.rowSwitch.isChecked
        }

        binding.rowMonitorInterval.rowIcon.setImageResource(R.drawable.ic_clock)
        binding.rowMonitorInterval.rowTitle.setText(R.string.tools_monitor_interval)
        binding.rowMonitorInterval.rowChevron.visibility = View.VISIBLE
        binding.rowMonitorInterval.rowValue.text = intervalLabel(Prefs.monitorIntervalSec(requireContext()))
        binding.rowMonitorInterval.rowRoot.setOnClickListener { showIntervalDialog() }
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
                binding.rowMonitorInterval.rowValue.text = labels[which]
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
            target.monitorStatusLine.text = line
            target.monitorStatusLine.setTextColor(
                ContextCompat.getColor(
                    target.root.context, if (watching > 0) R.color.success else R.color.text_muted
                )
            )
        }
    }

    // ------------------------------------------------------------------- typing

    private fun bindTyping() {
        binding.rowHumanPaste.rowIcon.setImageResource(R.drawable.ic_keyboard)
        binding.rowHumanPaste.rowTitle.setText(R.string.tools_human_enabled)
        binding.rowHumanPaste.rowSwitch.isChecked = Prefs.humanPasteEnabled(requireContext())
        binding.rowHumanPaste.rowSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setHumanPasteEnabled(requireContext(), checked)
        }
        binding.rowHumanPaste.rowRoot.setOnClickListener {
            binding.rowHumanPaste.rowSwitch.isChecked = !binding.rowHumanPaste.rowSwitch.isChecked
        }

        binding.rowJitter.rowIcon.setImageResource(R.drawable.ic_paste)
        binding.rowJitter.rowTitle.setText(R.string.editor_human_jitter)
        binding.rowJitter.rowSwitch.isChecked = Prefs.humanPasteJitter(requireContext())
        binding.rowJitter.rowSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setHumanPasteJitter(requireContext(), checked)
        }
        binding.rowJitter.rowRoot.setOnClickListener {
            binding.rowJitter.rowSwitch.isChecked = !binding.rowJitter.rowSwitch.isChecked
        }

        val group = binding.speedChips
        group.removeAllViews()
        val current = HumanPaste.speedById(Prefs.humanPasteSpeed(requireContext()))
        HumanPaste.speeds.forEach { speed ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = speed.label
                isCheckable = true
                isChecked = speed.id == current.id
                setOnClickListener {
                    Prefs.setHumanPasteSpeed(requireContext(), speed.id)
                    group.check(id)
                }
            }
            group.addView(chip)
        }
    }

    // ----------------------------------------------------------------- browsing

    private fun bindBrowsing() {
        binding.rowStartUrl.rowIcon.setImageResource(R.drawable.ic_globe)
        binding.rowStartUrl.rowTitle.setText(R.string.tools_start_url)
        binding.rowStartUrl.rowChevron.visibility = View.VISIBLE
        binding.rowStartUrl.rowValue.text = Prefs.defaultStartUrl(requireContext())
        binding.rowStartUrl.rowRoot.setOnClickListener { promptStartUrl() }

        bindSwitch(binding.rowHistory, R.drawable.ic_history, R.string.tools_history, Prefs.historyEnabled(requireContext())) {
            Prefs.setHistoryEnabled(requireContext(), it)
        }
        bindSwitch(binding.rowTracker, R.drawable.ic_chart, R.string.tools_tracker, Prefs.trackerEnabled(requireContext())) {
            Prefs.setTrackerEnabled(requireContext(), it)
        }
        bindSwitch(binding.rowUnlockSelection, R.drawable.ic_eye, R.string.tools_unlock, Prefs.unlockSelection(requireContext())) {
            Prefs.setUnlockSelection(requireContext(), it)
        }
        bindSwitch(binding.rowTrash, R.drawable.ic_delete, R.string.tools_trash, Prefs.trashEnabled(requireContext())) {
            Prefs.setTrashEnabled(requireContext(), it)
        }
    }

    private fun bindSwitch(
        row: com.joebrowser.app.databinding.ItemSwitchRowBinding,
        iconRes: Int,
        titleRes: Int,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        row.rowIcon.setImageResource(iconRes)
        row.rowTitle.setText(titleRes)
        row.rowSwitch.isChecked = checked
        row.rowSwitch.setOnCheckedChangeListener { _, value -> onChange(value) }
        row.rowRoot.setOnClickListener { row.rowSwitch.isChecked = !row.rowSwitch.isChecked }
    }

    private fun promptStartUrl() {
        val input = android.widget.EditText(requireContext()).apply {
            setText(Prefs.defaultStartUrl(requireContext()))
            setSelection(text.length)
            setPadding(48, 32, 48, 16)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tools_start_url)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    Prefs.setDefaultStartUrl(requireContext(), value)
                    binding.rowStartUrl.rowValue.text = Prefs.defaultStartUrl(requireContext())
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // --------------------------------------------------------------------- data

    private fun bindData() {
        binding.rowExportSafe.rowIcon.setImageResource(R.drawable.ic_download)
        binding.rowExportSafe.rowTitle.setText(R.string.tools_export_safe)
        binding.rowExportSafe.rowValue.setText(R.string.tools_export_safe_hint)
        binding.rowExportSafe.rowRoot.setOnClickListener { exportBackup(includeSecrets = false) }

        binding.rowExportFull.rowIcon.setImageResource(R.drawable.ic_download)
        binding.rowExportFull.rowTitle.setText(R.string.tools_export_full)
        binding.rowExportFull.rowValue.setText(R.string.tools_export_note)
        binding.rowExportFull.rowRoot.setOnClickListener { exportBackup(includeSecrets = true) }

        binding.rowRestore.rowIcon.setImageResource(R.drawable.ic_import)
        binding.rowRestore.rowTitle.setText(R.string.tools_import)
        binding.rowRestore.rowChevron.visibility = View.VISIBLE
        binding.rowRestore.rowRoot.setOnClickListener { pickBackup() }

        binding.rowEmptyTrash.rowIcon.setImageResource(R.drawable.ic_delete)
        binding.rowEmptyTrash.rowTitle.setText(R.string.tools_empty_trash)
        binding.rowEmptyTrash.rowRoot.setOnClickListener { emptyTrash() }

        binding.rowStorageUsed.rowIcon.setImageResource(R.drawable.ic_hard_drive)
        binding.rowStorageUsed.rowTitle.setText(R.string.tools_storage_used)

        binding.rowReset.rowIcon.setImageResource(R.drawable.ic_warning)
        binding.rowReset.rowTitle.setText(R.string.tools_reset)
        binding.rowReset.rowTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
        binding.rowReset.rowRoot.setOnClickListener { confirmReset() }
    }

    private fun emptyTrash() {
        val context = requireContext()
        ProfileRepository.trashedProfiles(context) { items ->
            if (!isAdded) return@trashedProfiles
            if (items.isEmpty()) {
                Async.toast(context, getString(R.string.tools_trash_empty_toast))
                return@trashedProfiles
            }
            val labels = items.map { (p, deletedAt) ->
                val ago = android.text.format.DateUtils.getRelativeTimeSpanString(
                    deletedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                )
                "${p.displayName()} (${p.engine.title} · $ago)"
            }.toTypedArray()

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.tools_trash_manage_title)
                .setItems(labels) { _, which ->
                    val (selected, _) = items[which]
                    MaterialAlertDialogBuilder(context)
                        .setTitle(selected.displayName())
                        .setMessage(getString(R.string.tools_trash_item_prompt, selected.displayName()))
                        .setPositiveButton(R.string.action_restore) { _, _ ->
                            ProfileRepository.restoreFromTrash(context, selected.id) {
                                Async.toast(context, getString(R.string.tools_trash_restored, selected.displayName()))
                                refresh()
                            }
                        }
                        .setNeutralButton(R.string.action_delete_forever) { _, _ ->
                            ProfileRepository.deleteTrashedForever(context, selected.id) {
                                Async.toast(context, getString(R.string.status_deleted))
                                refresh()
                            }
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
                .setNeutralButton(R.string.tools_empty_trash) { _, _ ->
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.tools_empty_trash)
                        .setMessage(getString(R.string.tools_trash_confirm_all, items.size))
                        .setPositiveButton(R.string.action_delete) { _, _ ->
                            ProfileRepository.emptyTrash(context) {
                                Async.toast(context, getString(R.string.status_deleted))
                                refresh()
                            }
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
                .setNegativeButton(R.string.action_close, null)
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

    /** Opens the system picker; the SAF grant is read once and never persisted. */
    private fun pickBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        runCatching { backupPicker.launch(intent) }
            .onFailure { Async.toast(requireContext(), getString(R.string.error_generic)) }
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

    // -------------------------------------------------------------------- about

    private fun bindAbout() {
        binding.rowVersion.rowIcon.setImageResource(R.drawable.ic_info)
        // The row label is the app's name, not a section header repeated as a row title.
        binding.rowVersion.rowTitle.setText(R.string.app_name)
        binding.rowVersion.rowValue.text = getString(R.string.tools_version, versionName(), versionCode().toString())

        binding.rowLicenses.rowIcon.setImageResource(R.drawable.ic_folder)
        binding.rowLicenses.rowTitle.setText(R.string.tools_licenses)
        binding.rowLicenses.rowChevron.visibility = View.VISIBLE
        binding.rowLicenses.rowRoot.setOnClickListener {
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

    // ------------------------------------------------------------------ refresh

    override fun refresh() {
        if (_binding == null || !isAdded) return
        val context = requireContext().applicationContext
        refreshMonitorStatus()

        ProfileRepository.trashCount(context) { count ->
            _binding?.rowEmptyTrash?.rowValue?.text = getString(R.string.tools_trash_count, count)
        }
        Async.compute({ ProfileStorage.totalSizeBytes(context) }) { bytes ->
            _binding?.rowStorageUsed?.rowValue?.text = ProfileStorage.humanSize(bytes ?: 0L)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private val TRACKED_ACTIONS = listOf(
            AppEvents.ACTION_DATA_CHANGED,
            AppEvents.ACTION_PROXY_STATE,
            AppEvents.ACTION_MONITOR_ALERT
        )
    }
}
