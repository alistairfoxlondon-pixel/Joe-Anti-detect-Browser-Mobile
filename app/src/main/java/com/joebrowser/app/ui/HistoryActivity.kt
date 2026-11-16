package com.joebrowser.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.HistoryEntry
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.databinding.ActivityHistoryBinding
import com.joebrowser.app.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browsing history across every profile.
 *
 * Filtering is applied in memory: the list is capped at 500 recent entries by
 * [ProfileRepository.history], so re-querying SQLite on every keystroke would be wasteful.
 * Both filters (profile chip + text) compose, and the empty state distinguishes "nothing
 * recorded" from "nothing matched" so the user never wonders whether the search broke.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = HistoryAdapter()
    private var allEntries: List<HistoryEntry> = emptyList()
    private var profiles: List<Profile> = emptyList()
    private var profileFilter: String? = null
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.historyList.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        profileFilter = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }

        binding.btnHistoryBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnHistoryClear.setOnClickListener { confirmClear() }
        binding.historySearch.addTextChangedListener(SimpleWatcher { text ->
            query = text.trim()
            applyFilter()
        })

        buildProfileChips()
        load()
    }

    private fun load() {
        val app = applicationContext
        ProfileRepository.profiles(app) { list ->
            profiles = list
            buildProfileChips()
        }
        ProfileRepository.history(app, null, 500) { entries ->
            allEntries = entries
            // Chips are built from the entry list (each profile's count), so they must be
            // rebuilt here as well — the first build ran before any entry was known.
            buildProfileChips()
            applyFilter()
        }
    }

    private fun buildProfileChips() {
        val group = binding.historyFilters ?: return
        val previous = profileFilter
        group.removeAllViews()

        val chips = mutableListOf<Triple<String?, String, Int>>()
        chips += Triple(null, getString(R.string.history_all), allEntries.size)
        profiles.forEach { profile ->
            val count = allEntries.count { it.profileId == profile.id }
            if (count > 0) chips += Triple(profile.id, profile.displayName(), count)
        }

        chips.forEach { (id, label, count) ->
            group.addView(Chip(this).apply {
                this.id = View.generateViewId()
                text = label + " · " + count
                isCheckable = true
                isChecked = id == previous
                setOnClickListener {
                    profileFilter = id
                    group.check(this.id)
                    applyFilter()
                }
            })
        }
        if (chips.none { it.first == previous }) profileFilter = null
    }

    /** Applies both filters and keeps the empty state truthful about which one emptied it. */
    private fun applyFilter() {
        val filtered = allEntries.filter { entry ->
            (profileFilter == null || entry.profileId == profileFilter) &&
                (query.isEmpty() ||
                    entry.title.contains(query, true) ||
                    entry.url.contains(query, true) ||
                    entry.profileName.contains(query, true))
        }
        adapter.submit(filtered)
        binding.historyCount.text = filtered.size.toString()
        binding.historyEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        // Cached rather than looked up per keystroke, and it always exists in the layout, so a
        // null here would silently mislabel the empty state.
        binding.historyEmptyText.setText(
            if (allEntries.isEmpty()) R.string.history_empty_title else R.string.history_empty
        )
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear_title)
            .setMessage(
                getString(
                    if (profileFilter == null) R.string.history_clear_body
                    else R.string.browser_clear_data_body,
                    profiles.firstOrNull { it.id == profileFilter }?.displayName().orEmpty()
                )
            )
            .setPositiveButton(R.string.history_clear_all) { _, _ ->
                val app = applicationContext
                val clearedProfile = profileFilter
                ProfileRepository.clearHistory(app, clearedProfile) {
                    // A scoped clear removes only that profile's rows. Clearing everything in
                    // memory and dropping the filter made the screen claim the whole log was
                    // gone when the other profiles' entries were still in the database.
                    allEntries = if (clearedProfile == null) emptyList()
                    else allEntries.filterNot { it.profileId == clearedProfile }
                    if (clearedProfile != null && allEntries.isEmpty()) profileFilter = null
                    buildProfileChips()
                    applyFilter()
                    Async.toast(this, getString(R.string.status_deleted))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun open(entry: HistoryEntry) {
        val app = applicationContext
        ProfileRepository.profile(app, entry.profileId) { profile ->
            if (profile == null) {
                Async.toast(this, getString(R.string.error_profile_not_found))
                return@profile
            }
            BrowserActivity.launch(this, profile.id, entry.url)
            finish()
        }
    }

    private fun deleteEntry(entry: HistoryEntry) {
        Async.bg {
            runCatching { Database.get(applicationContext).deleteHistory(entry.id) }
            Async.onMain {
                allEntries = allEntries.filterNot { it.id == entry.id }
                buildProfileChips()
                applyFilter()
            }
        }
    }

    // ------------------------------------------------------------------ adapter

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private var items: List<HistoryEntry> = emptyList()
        private val dayFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun submit(list: List<HistoryEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val item = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(item)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val item: ItemHistoryBinding) : RecyclerView.ViewHolder(item.root) {
            fun bind(entry: HistoryEntry) {
                item.historyTitle.text = entry.title.ifBlank { entry.url }
                item.historyMeta.text = hostOf(entry.url) + " · " + entry.profileName
                item.historyTime.text = if (isToday(entry.ts)) timeFormat.format(Date(entry.ts))
                else dayFormat.format(Date(entry.ts))
                item.historyIcon.setImageResource(
                    if (entry.url.startsWith("https://")) R.drawable.ic_lock else R.drawable.ic_globe
                )
                item.root.setOnClickListener { open(entry) }
                item.root.setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@HistoryActivity)
                        .setItems(
                            arrayOf(getString(R.string.action_open), getString(R.string.action_copy), getString(R.string.action_delete))
                        ) { _, which ->
                            when (which) {
                                0 -> open(entry)
                                1 -> Async.copyToClipboard(this@HistoryActivity, entry.url)
                                else -> deleteEntry(entry)
                            }
                        }
                        .show()
                    true
                }
            }

            private fun hostOf(url: String): String = runCatching {
                java.net.URI(url).host ?: url
            }.getOrDefault(url).removePrefix("www.")
        }
    }

    private fun isToday(ts: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = ts
        return today == (cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR))
    }

    /** Minimal TextWatcher so the filter reacts to every keystroke without boilerplate. */
    private class SimpleWatcher(private val onText: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = onText(s?.toString().orEmpty())
    }

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"

        fun launch(context: Context, profileId: String? = null) {
            val intent = Intent(context, HistoryActivity::class.java)
            if (profileId != null) intent.putExtra(EXTRA_PROFILE_ID, profileId)
            runCatching { context.startActivity(intent) }
        }
    }
}
