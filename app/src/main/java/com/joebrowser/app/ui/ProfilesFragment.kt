package com.joebrowser.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.data.ProfileStorage
import com.joebrowser.app.databinding.FragmentProfilesBinding
import com.joebrowser.app.databinding.ItemProfileCardBinding

/**
 * Profiles tab.
 *
 * Behaviour:
 *  - Search filters on name, group, tags, status, OS and engine; group chips filter on group.
 *  - Sort order is persisted in [Prefs] and applied to the already-loaded list, so changing
 *    it never re-queries the database.
 *  - Every card action is wired: launch, edit, duplicate, clear data, delete. Nothing on the
 *    card is decorative.
 *  - Deletion honours the trash preference and erases the profile's isolated directory.
 */
class ProfilesFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentProfilesBinding? = null
    private val binding get() = _binding!!

    private val selectedIds = linkedSetOf<String>()

    private val adapter = ProfileAdapter(
        isSelected = { id -> id in selectedIds },
        selectionActive = { selectedIds.isNotEmpty() },
        onLaunch = { profile ->
            if (selectedIds.isNotEmpty()) toggleSelection(profile.id)
            else BrowserActivity.launch(requireContext(), profile.id)
        },
        onLongPress = { profile -> toggleSelection(profile.id) },
        onEdit = { profile -> (activity as? MainActivity)?.openProfileEditor(profile.id) },
        onMenu = { profile, anchor -> showMenu(profile, anchor) },
        onDelete = { profile -> confirmDelete(profile) }
    )

    private var allProfiles: List<Profile> = emptyList()
    private var query: String = ""
    private var selectedGroup: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Each row carries a live proxy dot, so proxy-state changes belong here too.
            val action = intent?.action ?: return
            if (action in TRACKED_ACTIONS) refresh()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val spanCount = resources.getInteger(R.integer.profiles_grid_span).coerceAtLeast(1)
        binding.profileList.layoutManager = GridLayoutManager(requireContext(), spanCount)
        binding.profileList.adapter = adapter
        binding.profileList.setHasFixedSize(false)
        binding.profileList.itemAnimator?.changeDuration = 0

        binding.fabNewProfile.setOnClickListener { (activity as? MainActivity)?.openProfileEditor() }
        binding.emptyCreate.setOnClickListener { (activity as? MainActivity)?.openProfileEditor() }

        binding.profileSearch.doAfterTextChanged { editable ->
            query = editable?.toString()?.trim().orEmpty()
            binding.clearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            applyFilters()
        }
        binding.clearSearch.setOnClickListener { binding.profileSearch.setText("") }
        binding.sortProfiles.setOnClickListener { anchor -> showSortMenu(anchor) }
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

    // ------------------------------------------------------------------ sorting

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val options = listOf(
            "Recently used", "Name A–Z", "Newest first", "Operating system", "Group"
        )
        options.forEachIndexed { index, label ->
            popup.menu.add(0, index, index, label).isCheckable = true
        }
        popup.menu.setGroupCheckable(0, true, true)
        runCatching { popup.menu.findItem(Prefs.profileSort(requireContext()))?.isChecked = true }
        popup.setOnMenuItemClickListener { item ->
            Prefs.setProfileSort(requireContext(), item.itemId)
            applyFilters()
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------------- menus

    private fun showMenu(profile: Profile, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, ACTION_EDIT, 0, getString(R.string.action_edit))
        popup.menu.add(0, ACTION_SWITCH_PROXY, 1, getString(R.string.profiles_quick_proxy))
        popup.menu.add(0, ACTION_NEW_FINGERPRINT, 2, getString(R.string.profiles_new_fingerprint))
        popup.menu.add(0, ACTION_DUPLICATE, 3, getString(R.string.action_duplicate))
        popup.menu.add(0, ACTION_SELECT, 4, getString(R.string.profiles_batch_select))
        popup.menu.add(0, ACTION_CLEAR, 5, getString(R.string.profiles_clear_data))
        popup.menu.add(0, ACTION_DELETE, 6, getString(R.string.action_delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ACTION_EDIT -> (activity as? MainActivity)?.openProfileEditor(profile.id)
                ACTION_SWITCH_PROXY -> showQuickProxyPicker(listOf(profile))
                ACTION_NEW_FINGERPRINT -> rerollFingerprint(profile)
                ACTION_DUPLICATE -> duplicate(profile)
                ACTION_SELECT -> toggleSelection(profile.id)
                ACTION_CLEAR -> confirmClearData(profile)
                ACTION_DELETE -> confirmDelete(profile)
            }
            true
        }
        popup.show()
    }

    private fun toggleSelection(id: String) {
        if (!selectedIds.add(id)) selectedIds.remove(id)
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUi() {
        val b = _binding ?: return
        if (selectedIds.isEmpty()) {
            applyFilters()
            b.profilesCount.setOnClickListener(null)
            b.profilesCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
            b.fabNewProfile.setText(R.string.profiles_new)
            b.fabNewProfile.setIconResource(R.drawable.ic_plus)
            b.fabNewProfile.setOnClickListener { (activity as? MainActivity)?.openProfileEditor() }
        } else {
            b.profilesCount.text = getString(R.string.profiles_selected_count, selectedIds.size)
            b.profilesCount.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
            b.profilesCount.setOnClickListener {
                selectedIds.clear()
                updateSelectionUi()
                adapter.notifyDataSetChanged()
            }
            b.fabNewProfile.visibility = View.VISIBLE
            b.fabNewProfile.text = getString(R.string.profiles_batch_actions, selectedIds.size)
            b.fabNewProfile.setIconResource(R.drawable.ic_layers)
            b.fabNewProfile.setOnClickListener { showBatchActionsDialog() }
        }
    }

    private fun showBatchActionsDialog() {
        val targets = allProfiles.filter { it.id in selectedIds }
        if (targets.isEmpty()) {
            selectedIds.clear()
            updateSelectionUi()
            return
        }
        val options = arrayOf(
            getString(R.string.profiles_quick_proxy),
            getString(R.string.profiles_batch_group),
            getString(R.string.profiles_new_fingerprint),
            getString(R.string.action_delete),
            getString(R.string.profiles_batch_clear_selection)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.profiles_batch_actions, targets.size))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showQuickProxyPicker(targets)
                    1 -> promptBatchGroup(targets)
                    2 -> batchRerollFingerprint(targets)
                    3 -> confirmBatchDelete(targets)
                    4 -> {
                        selectedIds.clear()
                        updateSelectionUi()
                        adapter.notifyDataSetChanged()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showQuickProxyPicker(targets: List<Profile>) {
        val ctx = requireContext()
        ProfileRepository.proxies(ctx) { proxies ->
            if (!isAdded) return@proxies
            val labels = mutableListOf(getString(R.string.direct))
            proxies.forEach { saved ->
                val state = if (saved.config.online) " · ${saved.config.latencyMs} ms" else ""
                labels.add("${saved.displayName()} (${saved.config.type.uppercase()}$state)")
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.profiles_quick_proxy)
                .setItems(labels.toTypedArray()) { _, which ->
                    val chosen = if (which == 0) com.joebrowser.app.data.ProxyConfig.direct()
                    else proxies[which - 1].config.duplicate()
                    targets.forEach { target ->
                        ProfileRepository.updateProfile(ctx, target.id) {
                            it.proxy = chosen.duplicate()
                        }
                    }
                    selectedIds.clear()
                    Async.toast(ctx, getString(R.string.status_saved))
                    updateSelectionUi()
                    refresh()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun promptBatchGroup(targets: List<Profile>) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.editor_group)
            setSingleLine(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.profiles_batch_group)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val groupName = input.text?.toString()?.trim().orEmpty()
                targets.forEach { target ->
                    ProfileRepository.updateProfile(ctx, target.id) { it.group = groupName }
                }
                selectedIds.clear()
                Async.toast(ctx, getString(R.string.status_saved))
                updateSelectionUi()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun rerollFingerprint(profile: Profile) {
        val ctx = requireContext()
        val existingSigs = allProfiles.map { com.joebrowser.app.fingerprint.FingerprintFactory.signature(it.fp) }
        ProfileRepository.updateProfile(ctx, profile.id) { p ->
            com.joebrowser.app.fingerprint.FingerprintFactory.regenerate(
                p.fp, p.fp.os, p.proxy.lastCountryCode.takeIf { it.isNotBlank() }, p.engine
            )
            com.joebrowser.app.fingerprint.FingerprintFactory.ensureUnique(p.fp, existingSigs)
            p.browserVersion = p.fp.browserVersion
        }
        Async.toast(ctx, getString(R.string.profiles_fingerprint_regenerated))
    }

    private fun batchRerollFingerprint(targets: List<Profile>) {
        val ctx = requireContext()
        val sigs = allProfiles.map { com.joebrowser.app.fingerprint.FingerprintFactory.signature(it.fp) }.toMutableList()
        targets.forEach { target ->
            ProfileRepository.updateProfile(ctx, target.id) { p ->
                com.joebrowser.app.fingerprint.FingerprintFactory.regenerate(
                    p.fp, p.fp.os, p.proxy.lastCountryCode.takeIf { it.isNotBlank() }, p.engine
                )
                com.joebrowser.app.fingerprint.FingerprintFactory.ensureUnique(p.fp, sigs)
                sigs.add(com.joebrowser.app.fingerprint.FingerprintFactory.signature(p.fp))
                p.browserVersion = p.fp.browserVersion
            }
        }
        selectedIds.clear()
        Async.toast(ctx, getString(R.string.profiles_fingerprint_regenerated))
        updateSelectionUi()
    }

    private fun confirmBatchDelete(targets: List<Profile>) {
        val ctx = requireContext()
        val keepTrash = Prefs.trashEnabled(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.profiles_batch_delete_title, targets.size))
            .setMessage(
                getString(
                    if (keepTrash) R.string.profiles_delete_body_trash else R.string.profiles_delete_body_forever
                )
            )
            .setPositiveButton(R.string.action_delete) { _, _ ->
                targets.forEach { p ->
                    ProfileRepository.deleteProfile(ctx, p, keepTrash)
                }
                selectedIds.clear()
                updateSelectionUi()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun duplicate(profile: Profile) {
        ProfileRepository.duplicateProfile(requireContext(), profile) { copy ->
            if (copy != null) Async.toast(requireContext(), getString(R.string.editor_created))
            refresh()
        }
    }

    private fun confirmClearData(profile: Profile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profiles_clear_data)
            .setMessage(getString(R.string.profiles_clear_data_body, profile.displayName()))
            .setPositiveButton(R.string.action_clear) { _, _ -> clearData(profile) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clearData(profile: Profile) {
        val app = requireContext().applicationContext
        Async.bg {
            runCatching {
                ProfileStorage.erase(app, profile.id)
                ProfileStorage.ensure(app, profile.id)
            }
            ProfileRepository.clearHistory(app, profile.id)
            Async.toast(app, getString(R.string.browser_cleared))
            refresh()
        }
    }

    private fun confirmDelete(profile: Profile) {
        val keepTrash = Prefs.trashEnabled(requireContext())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.profiles_delete_title, profile.displayName()))
            .setMessage(
                getString(
                    if (keepTrash) R.string.profiles_delete_body_trash else R.string.profiles_delete_body_forever
                )
            )
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ProfileRepository.deleteProfile(requireContext(), profile, keepTrash) {
                    AppEvents.emitDataChanged(requireContext().applicationContext, "profile-deleted")
                    if (_binding != null) refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ----------------------------------------------------------------- refresh

    override fun refresh() {
        if (_binding == null || !isAdded) return
        val app = requireContext().applicationContext
        ProfileRepository.profiles(app) { profiles ->
            if (_binding == null) return@profiles
            allProfiles = profiles
            rebuildGroupChips()
            applyFilters()
        }
    }

    private fun rebuildGroupChips() {
        val group = binding.groupChips
        group.removeAllViews()

        val groups = allProfiles.map { it.groupOrUngrouped() }.distinct().sorted()
        if (selectedGroup != null && groups.none { it == selectedGroup }) selectedGroup = null

        group.addView(
            filterChip(getString(R.string.profiles_filter_all), selectedGroup == null) {
                selectedGroup = null
                rebuildGroupChips()
                applyFilters()
            }
        )
        groups.forEach { name ->
            group.addView(
                filterChip(name, selectedGroup == name) {
                    selectedGroup = name
                    rebuildGroupChips()
                    applyFilters()
                }
            )
        }
    }

    private fun filterChip(label: String, checked: Boolean, onSelect: () -> Unit): Chip =
        Chip(requireContext()).apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnClickListener { onSelect() }
        }

    private fun applyFilters() {
        if (_binding == null) return
        val needle = query.lowercase()
        var filtered = allProfiles

        if (needle.isNotEmpty()) {
            filtered = filtered.filter { profile ->
                listOf(
                    profile.name, profile.tags, profile.group, profile.status,
                    profile.fp.os, profile.engine.title, profile.notes
                ).any { it.lowercase().contains(needle) }
            }
        }
        selectedGroup?.let { groupName ->
            filtered = filtered.filter { it.groupOrUngrouped() == groupName }
        }

        filtered = when (Prefs.profileSort(requireContext())) {
            1 -> filtered.sortedBy { it.name.lowercase() }
            2 -> filtered.sortedByDescending { it.created }
            3 -> filtered.sortedBy { it.fp.os }
            4 -> filtered.sortedBy { it.groupOrUngrouped().lowercase() }
            else -> filtered.sortedByDescending { maxOf(it.lastOpened, it.created) }
        }

        adapter.submit(filtered)

        val filtering = needle.isNotEmpty() || selectedGroup != null
        if (selectedIds.isNotEmpty()) {
            binding.profilesCount.text = getString(R.string.profiles_selected_count, selectedIds.size)
        } else {
            binding.profilesCount.text = if (filtering) {
                getString(R.string.profiles_count_filtered, filtered.size, allProfiles.size)
            } else {
                getString(R.string.profiles_count, filtered.size)
            }
        }

        val empty = filtered.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.profileList.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        binding.fabNewProfile.visibility = if (empty && selectedIds.isEmpty()) View.GONE else View.VISIBLE

        // Distinguish "no profiles at all" from "nothing matched the filter".
        if (empty) {
            binding.emptyTitle.setText(
                if (filtering) R.string.profiles_no_match_title else R.string.profiles_empty_title
            )
            binding.emptyBody.setText(
                if (filtering) R.string.profiles_no_match_body else R.string.profiles_empty_body
            )
            binding.emptyCreate.visibility = if (filtering) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        binding.profileList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    // ----------------------------------------------------------------- adapter

    private class ProfileAdapter(
        private val isSelected: (String) -> Boolean,
        private val selectionActive: () -> Boolean,
        private val onLaunch: (Profile) -> Unit,
        private val onLongPress: (Profile) -> Unit,
        private val onEdit: (Profile) -> Unit,
        private val onMenu: (Profile, View) -> Unit,
        private val onDelete: (Profile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

        private var items: List<Profile> = emptyList()

        fun submit(list: List<Profile>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemProfileCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val view: ItemProfileCardBinding) :
            RecyclerView.ViewHolder(view.root) {

            fun bind(profile: Profile) {
                val context = view.root.context
                val selected = isSelected(profile.id)

                view.profileCard.setBackgroundResource(
                    if (selected) R.drawable.bg_accent_soft else R.drawable.bg_card
                )
                view.profileAvatar.text = if (selected) "✓" else profile.initials()
                view.profileAvatar.background = UiKit.avatarBackground(profile.id)
                view.profileName.text = profile.displayName()

                val statusChipText = profile.status.ifBlank {
                    profile.group.takeIf { it.isNotBlank() }.orEmpty()
                }
                if (statusChipText.isBlank()) {
                    view.profileStatus.visibility = View.GONE
                } else {
                    view.profileStatus.visibility = View.VISIBLE
                    view.profileStatus.text = statusChipText
                }

                view.profileOsIcon.setImageResource(UiKit.osIconRes(profile.fp.os))

                val engine = profile.engine
                view.profileMeta.text = listOf(
                    profile.fp.os,
                    engine.title,
                    "v" + profile.browserVersion.substringBefore('.')
                ).joinToString(" · ")

                view.profileOpened.text = if (profile.openCount > 0) profile.lastOpenedText() else ""

                val proxy = profile.proxy
                view.proxyDot.setBackgroundResource(
                    when {
                        !proxy.enabled -> R.drawable.bg_dot
                        proxy.online -> R.drawable.bg_dot_good
                        proxy.checked -> R.drawable.bg_dot_bad
                        else -> R.drawable.bg_dot_warn
                    }
                )
                view.proxyText.text = when {
                    !proxy.enabled -> context.getString(R.string.direct)
                    proxy.online -> proxy.label() + " · " + proxy.authority()
                    proxy.checked -> proxy.label() + " · " + context.getString(R.string.proxies_failed)
                    else -> proxy.label() + " · " + context.getString(R.string.proxies_never_checked)
                }
                view.proxyLatency.text = if (proxy.online) UiKit.formatLatency(proxy.latencyMs) else ""
                view.proxyLatency.setTextColor(
                    ContextCompat.getColor(context, if (proxy.online) R.color.success else R.color.text_muted)
                )

                view.profileBadge.visibility = if (profile.freshSession) View.VISIBLE else View.GONE
                view.profileBadge.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.accent)
                )

                view.profileLaunch.setText(
                    if (selectionActive()) R.string.profiles_select_toggle else R.string.action_open
                )
                view.profileLaunch.setOnClickListener {
                    if (selectionActive()) onLongPress(profile) else BrowserActivity.launch(context, profile.id)
                }
                view.profileEdit.setOnClickListener { onEdit(profile) }
                view.profileMore.setOnClickListener { onMenu(profile, it) }
                view.profileDelete.setOnClickListener { onDelete(profile) }
                view.root.setOnClickListener { onLaunch(profile) }
                view.root.setOnLongClickListener {
                    onLongPress(profile)
                    true
                }
            }
        }
    }

    companion object {
        private val TRACKED_ACTIONS = listOf(
            AppEvents.ACTION_DATA_CHANGED,
            AppEvents.ACTION_PROXY_STATE,
            AppEvents.ACTION_MONITOR_ALERT
        )

        private const val ACTION_EDIT = 101
        private const val ACTION_DUPLICATE = 102
        private const val ACTION_CLEAR = 103
        private const val ACTION_DELETE = 104
        private const val ACTION_SWITCH_PROXY = 105
        private const val ACTION_NEW_FINGERPRINT = 106
        private const val ACTION_SELECT = 107
    }
}
