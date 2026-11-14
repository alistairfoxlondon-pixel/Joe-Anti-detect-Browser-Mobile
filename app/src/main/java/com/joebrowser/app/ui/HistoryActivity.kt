package com.joebrowser.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.joebrowser.app.R
import com.joebrowser.app.browser.BrowserActivity
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.HistoryEntry
import com.joebrowser.app.databinding.ActivityHistoryBinding
import com.joebrowser.app.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = HistoryAdapter(
        onOpen = { entry -> BrowserActivity.launch(this, entry.profileId, entry.url) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_JoeBrowser)
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        binding.btnHistoryBack.setOnClickListener { finish() }
        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear all history?")
                .setMessage("This erases the browsing history of every profile on this device. Bookmarks and profiles are unaffected.")
                .setPositiveButton("Clear all") { _, _ ->
                    Database.get(this).clearHistory(null)
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val entries = Database.get(this).getHistory(500)
        adapter.submit(entries)
        val empty = entries.isEmpty()
        binding.historyEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.historyList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private class HistoryAdapter(
        private val onOpen: (HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private var items: List<HistoryEntry> = emptyList()
        private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

        fun submit(list: List<HistoryEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(entry: HistoryEntry) {
                val initials = entry.profileName.trim().split(Regex("\\s+")).take(2)
                    .joinToString("") { it.take(1) }.uppercase(Locale.US).ifBlank { "JB" }
                binding.historyAvatar.text = initials
                binding.historyAvatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(UiKit.avatarColor(entry.profileId))
                }
                binding.historyTitle.text = entry.title.ifBlank { entry.url }
                binding.historyUrl.text = entry.url
                binding.historyMeta.text = entry.profileName.ifBlank { "Profile" }
                binding.historyTime.text = timeFmt.format(Date(entry.ts))
                binding.root.setOnClickListener { onOpen(entry) }
            }
        }
    }
}
