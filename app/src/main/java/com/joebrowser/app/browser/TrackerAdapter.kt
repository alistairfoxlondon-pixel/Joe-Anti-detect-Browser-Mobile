package com.joebrowser.app.browser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.joebrowser.app.databinding.ItemTrackerBinding

/** Renders the per-session request histogram in the tracker bottom sheet. */
class TrackerAdapter : RecyclerView.Adapter<TrackerAdapter.Holder>() {

    private var items: List<TrackerEntry> = emptyList()

    fun submit(list: List<TrackerEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTrackerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    class Holder(private val binding: ItemTrackerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: TrackerEntry) {
            binding.trackerHost.text = entry.host
            binding.trackerCount.text = "×" + entry.count
            binding.trackerDot.setBackgroundResource(
                if (entry.tracker) com.joebrowser.app.R.drawable.bg_dot_bad
                else com.joebrowser.app.R.drawable.bg_dot_good
            )
        }
    }
}
