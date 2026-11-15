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

    private val adapter = ProfileAdapter(
        onLaunch = { profile -> BrowserActivity.launch(requireContext(), profile.id) },
        onEdit = { profile -> (activity as? MainActivity)?.openProfileEditor(profile.id) },
        onMenu = { profile, anchor -> showMenu(profile, anchor) },
        onDelete = { profile -> confirmDelete(profile) }
    )

    private var allProfiles: List<Profile> = emptyList()
    private var query: String = ""
    private var selectedGroup: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AppEvents.ACTION_DATA_CHANGED) refresh()
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
        AppEvents.register(this, receiver, listOf(AppEvents.ACTION_DATA_CHANGED))
    }

    override fun onStop() {
        AppEvents.unregister(this, receiver)
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
        popup.menu.add(0, ACTION_DUPLICATE, 1, getString(R.string.action_duplicate))
        popup.menu.add(0, ACTION_CLEAR, 2, getString(R.string.profiles_clear_data))
        popup.menu.add(0, ACTION_DELETE, 3, getString(R.string.action_delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ACTION_EDIT -> (activity as? MainActivity)?.openProfileEditor(profile.id)
                ACTION_DUPLICATE -> duplicate(profile)
                ACTION_CLEAR -> confirmClearData(profile)
                ACTION_DELETE -> confirmDelete(profile)
            }
            true
        }
        popup.show()
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
        binding.profilesCount.text = if (filtering) {
            getString(R.string.profiles_count_filtered, filtered.size, allProfiles.size)
        } else {
            getString(R.string.profiles_count, filtered.size)
        }

        val empty = filtered.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.profileList.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        binding.fabNewProfile.visibility = if (empty) View.GONE else View.VISIBLE

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
        private val onLaunch: (Profile) -> Unit,
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

                view.profileAvatar.text = profile.initials()
                view.profileAvatar.background = UiKit.avatarBackground(profile.id)
                view.profileName.text = profile.displayName()

                if (profile.status.isBlank()) {
                    view.profileStatus.visibility = View.GONE
                } else {
                    view.profileStatus.visibility = View.VISIBLE
                    view.profileStatus.text = profile.status
                }

                view.profileOsIcon.setImageResource(UiKit.osIconRes(profile.fp.os))

                val engine = profile.engine
                view.profileMeta.text = listOf(
                    profile.fp.os,
                    engine.title,
                    "v" + profile.browserVersion.substringBefore('.')
                ).joinToString(" · ")

                view.profileOpened.text = if (profile.openCount > 0) profile.lastOpenedText() else ""

                // Proxy state line: the dot is the semantic signal, the text names the endpoint.
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

                // Fresh-session marker doubles as the "cleared on close" badge.
                view.profileBadge.visibility = if (profile.freshSession) View.VISIBLE else View.GONE
                view.profileBadge.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.accent)
                )

                view.profileLaunch.setOnClickListener { onLaunch(profile) }
                view.profileEdit.setOnClickListener { onEdit(profile) }
                view.profileMore.setOnClickListener { onMenu(profile, it) }
                view.profileDelete.setOnClickListener { onDelete(profile) }
                view.root.setOnClickListener { onLaunch(profile) }
            }
        }
    }

    companion object {
        private const val ACTION_EDIT = 101
        private const val ACTION_DUPLICATE = 102
        private const val ACTION_CLEAR = 103
        private const val ACTION_DELETE = 104
    }
}
