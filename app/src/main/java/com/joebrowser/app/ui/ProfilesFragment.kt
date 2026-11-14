package com.joebrowser.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.joebrowser.app.AppEvents
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.Profile
import com.joebrowser.app.databinding.FragmentProfilesBinding
import com.joebrowser.app.databinding.ItemProfileCardBinding

class ProfilesFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentProfilesBinding? = null
    private val binding get() = _binding!!
    private val adapter = ProfileAdapter(
        onLaunch = { p -> BrowserActivity.launch(requireContext(), p.id) },
        onMenu = { p, anchor -> showCardMenu(p, anchor) }
    )
    private var selectedGroup: String? = null
    private var query: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val span = resources.getInteger(R.integer.profiles_grid_span)
        binding.profileList.layoutManager = GridLayoutManager(requireContext(), span)
        binding.profileList.adapter = adapter

        binding.fabNewProfile.setOnClickListener { startActivity(Intent(requireContext(), ProfileEditorActivity::class.java)) }
        binding.emptyCreate.setOnClickListener { startActivity(Intent(requireContext(), ProfileEditorActivity::class.java)) }

        binding.profileSearch.doAfterTextChanged { s ->
            query = s?.toString()?.trim().orEmpty()
            binding.clearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            applyFilters()
        }
        binding.clearSearch.setOnClickListener { binding.profileSearch.setText("") }

        binding.sortProfiles.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add(0, 0, 0, "Recently used").isCheckable = true
            popup.menu.add(0, 1, 0, "Name A–Z").isCheckable = true
            popup.menu.add(0, 2, 0, "Newest first").isCheckable = true
            popup.menu.add(0, 3, 0, "Operating system").isCheckable = true
            popup.menu.add(0, 4, 0, "Group").isCheckable = true
            popup.menu.setGroupCheckable(0, true, true)
            popup.menu.findItem(Prefs.profileSort(requireContext()))?.isChecked = true
            popup.setOnMenuItemClickListener { item ->
                Prefs.setProfileSort(requireContext(), item.itemId)
                applyFilters()
                true
            }
            popup.show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showCardMenu(profile: Profile, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Launch")
        popup.menu.add(0, 2, 0, "Edit profile")
        popup.menu.add(0, 3, 0, "Duplicate")
        popup.menu.add(0, 4, 0, "Clear browsing data")
        popup.menu.add(0, 5, 0, "Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> BrowserActivity.launch(requireContext(), profile.id)
                2 -> startActivity(Intent(requireContext(), ProfileEditorActivity::class.java).putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, profile.id))
                3 -> duplicateProfile(profile)
                4 -> confirmClearData(profile)
                5 -> confirmDelete(profile)
            }
            true
        }
        popup.show()
    }

    private fun duplicateProfile(profile: Profile) {
        val copy = profile.copy(freshId = true).apply { name = "${profile.name} copy" }
        Database.get(requireContext()).saveProfile(copy)
        AppEvents.emitProfilesChanged(requireContext())
        refresh()
    }

    private fun confirmClearData(profile: Profile) {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear browsing data?")
            .setMessage("Cookies, site data, cache and history for “${profile.name}” will be erased. Identity is kept.")
            .setPositiveButton("Clear") { _, _ ->
                try {
                    Database.get(requireContext()).clearHistory(profile.id)
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                        androidx.webkit.ProfileStore.getInstance().deleteProfile("jb-${profile.id}")
                    }
                } catch (_: Exception) {}
                android.widget.Toast.makeText(requireContext(), "Browsing data cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(profile: Profile) {
        val keep = Prefs.trashEnabled(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("Delete “${profile.name}”?")
            .setMessage(if (keep) "The profile will be kept in the trash (Settings → Empty trash to discard). History is removed." else "This permanently deletes the profile and its history.")
            .setPositiveButton("Delete") { _, _ ->
                Database.get(requireContext()).deleteProfile(profile, keep)
                AppEvents.emitProfilesChanged(requireContext())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun refresh() {
        if (_binding == null) return
        val profiles = Database.get(requireContext()).getAllProfiles()
        rebuildGroupChips(profiles)
        applyFilters()
    }

    private fun rebuildGroupChips(profiles: List<Profile>) {
        val groupChips = binding.groupChips
        val previous = selectedGroup
        groupChips.removeAllViews()

        fun chip(label: String, value: String?) {
            groupChips.addView(Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isChecked = (value == previous) || (value == null && previous == null)
                setOnClickListener { selectedGroup = value; applyFilters() }
            })
        }
        chip("All", null)
        val groups = profiles.map { it.groupOrUngrouped() }.distinct().sorted()
        groups.forEach { g -> chip(g, g) }
        // Ensure at least one chip is checked visually.
        if (groupChips.checkedChipId == View.NO_ID && groupChips.childCount > 0) {
            (groupChips.getChildAt(0) as? Chip)?.isChecked = true
        }
    }

    private fun applyFilters() {
        val db = Database.get(requireContext())
        var profiles = db.getAllProfiles()
        val q = query.lowercase()
        if (q.isNotEmpty()) {
            profiles = profiles.filter { p ->
                listOf(p.name, p.tags, p.group, p.status, p.fp.os, p.browserKind, p.notes)
                    .any { it.lowercase().contains(q) }
            }
        }
        selectedGroup?.let { g -> profiles = profiles.filter { it.groupOrUngrouped() == g } }

        profiles = when (Prefs.profileSort(requireContext())) {
            1 -> profiles.sortedBy { it.name.lowercase() }
            2 -> profiles.sortedByDescending { it.created }
            3 -> profiles.sortedBy { it.fp.os }
            4 -> profiles.sortedBy { it.groupOrUngrouped() }
            else -> profiles.sortedByDescending { maxOf(it.lastOpened, it.created) }
        }

        adapter.submit(profiles)
        val total = Database.get(requireContext()).countProfiles()
        binding.profilesCount.text = if (selectedGroup != null || q.isNotEmpty())
            "${profiles.size} of $total profiles" else "$total profiles"
        val empty = profiles.isEmpty()
        binding.emptyState.visibility = if (empty && q.isEmpty() && selectedGroup == null) View.VISIBLE else View.GONE
        binding.profileList.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        binding.fabNewProfile.visibility = if (empty && q.isEmpty() && selectedGroup == null) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class ProfileAdapter(
        private val onLaunch: (Profile) -> Unit,
        private val onMenu: (Profile, View) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

        private var items: List<Profile> = emptyList()

        fun submit(list: List<Profile>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemProfileCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val binding: ItemProfileCardBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(p: Profile) {
                val ctx = binding.root.context
                binding.profileAvatar.text = p.initials()
                binding.profileAvatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(UiKit.avatarColor(p.id))
                }
                binding.profileName.text = p.name.ifBlank { "Untitled profile" }
                if (p.status.isNotBlank()) {
                    binding.profileStatus.visibility = View.VISIBLE
                    binding.profileStatus.text = p.status
                } else {
                    binding.profileStatus.visibility = View.GONE
                }
                binding.profileOsIcon.setImageResource(UiKit.osIconRes(p.fp.os))
                val engine = p.browserKind.replaceFirstChar { it.uppercase() }
                binding.profileMeta.text = "${p.fp.os} · $engine ${p.fp.browserVersion.substringBefore('.')}"
                binding.proxyText.text = when {
                    p.proxy.enabled -> "${p.proxy.label()} · ${p.proxy.host}:${p.proxy.port}"
                    else -> "Direct connection"
                }
                binding.proxyDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when {
                            !p.proxy.enabled -> ContextCompat.getColor(ctx, R.color.text_faint)
                            p.proxy.lastIp.isNotBlank() -> ContextCompat.getColor(ctx, R.color.success)
                            else -> ContextCompat.getColor(ctx, R.color.warning)
                        }
                    )
                }
                binding.lastOpened.text = p.lastOpenedText()
                binding.root.setOnClickListener { onLaunch(p) }
                binding.profileMore.setOnClickListener { onMenu(p, it) }
            }
        }
    }
}
