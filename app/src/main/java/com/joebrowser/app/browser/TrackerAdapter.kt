package com.joebrowser.app.browser

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.joebrowser.app.databinding.ItemTrackerBinding

class TrackerAdapter : RecyclerView.Adapter<TrackerAdapter.Holder>() {
    private var items: List<TrackerEntry> = emptyList()

    fun submit(list: List<TrackerEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTrackerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    class Holder(private val binding: ItemTrackerBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL }

        fun bind(entry: TrackerEntry) {
            binding.trackerHost.text = entry.host
            binding.trackerCount.text = "×${entry.count}"
            dot.setColor(if (entry.tracker) 0xFFF26D76.toInt() else 0xFF4EC38A.toInt())
            binding.trackerDot.background = dot
        }
    }
}
