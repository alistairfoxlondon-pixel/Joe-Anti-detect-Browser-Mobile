package com.joebrowser.app.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.joebrowser.app.R
import com.joebrowser.app.data.Profile
import com.joebrowser.app.data.ProfileRepository
import com.joebrowser.app.databinding.ActivityFingerprintBatchBinding
import com.joebrowser.app.databinding.ItemFingerprintRowBinding
import com.joebrowser.app.fingerprint.FingerprintFactory

/**
 * Batch fingerprint audit.
 *
 * AdsPower and GoLogin both ship a "check profiles" view because the real question is never
 * "is this one profile fine?" — it is "is anything in my fleet broken?". This screen answers
 * that without a network round trip: every profile is scored by the same
 * [FingerprintFactory.coherence] checker the editor uses, sorted worst-first, and each row opens
 * that profile's real session so a suspicious score can be confirmed against a live detector.
 *
 * The score is on-device and therefore instant; the detector sites stay a deliberate second step,
 * because a service's verdict also depends on the proxy's exit IP, which the app cannot know.
 */
class FingerprintBatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFingerprintBatchBinding
    private var adapter: RowAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFingerprintBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBatchBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.batchList.layoutManager = LinearLayoutManager(this)
        adapter = RowAdapter().also { binding.batchList.adapter = it }
        binding.batchEmpty.setText(R.string.profiles_empty_title)

        ProfileRepository.profiles(this) { load(it) }
    }

    override fun onResume() {
        super.onResume()
        ProfileRepository.profiles(this) { load(it) }
    }

    private fun load(profiles: List<Profile>) {
        val rows = profiles
            .map { profile ->
                val report = FingerprintFactory.coherence(profile.fp, profile.engine)
                Row(profile, report.score, report.issues)
            }
            // Worst first: the point of the screen is finding what needs attention.
            .sortedBy { it.score }
        adapter?.submit(rows)

        val total = rows.size
        val clean = rows.count { it.score >= 90 }
        val issueCount = rows.sumOf { it.issues.size }
        binding.batchSummary.text = resources.getQuantityString(
            R.plurals.batch_summary, total, clean, total, issueCount
        )
        binding.batchEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.batchList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE

        // A duplicate fingerprint across two profiles is the worst failure mode there is:
        // sites link the two accounts instantly. Surface the count, not a vague warning.
        val duplicates = rows.groupBy { FingerprintFactory.signature(it.profile.fp) }
            .count { it.value.size > 1 }
        binding.batchDuplicates.visibility = if (duplicates > 0) View.VISIBLE else View.GONE
        binding.batchDuplicates.text = resources.getQuantityString(
            R.plurals.batch_duplicates, duplicates, duplicates
        )
    }

    private data class Row(val profile: Profile, val score: Int, val issues: List<String>)

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {

        private val rows = mutableListOf<Row>()

        fun submit(next: List<Row>) {
            rows.clear()
            rows.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemFingerprintRowBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

        inner class Holder(val item: ItemFingerprintRowBinding) : RecyclerView.ViewHolder(item.root) {

            fun bind(row: Row) {
                val context = item.root.context
                item.rowAvatar.text = row.profile.initials()
                item.rowAvatar.background = UiKit.avatarBackground(row.profile.id)
                item.rowName.text = row.profile.displayName()
                item.rowMeta.text = listOf(
                    row.profile.engine.title,
                    row.profile.fp.os
                ).joinToString(" · ")

                item.rowScore.text = row.score.toString()
                item.rowScore.setTextColor(
                    ContextCompat.getColor(
                        context,
                        when {
                            row.score >= 90 -> R.color.success
                            row.score >= 70 -> R.color.warning
                            else -> R.color.danger
                        }
                    )
                )
                item.rowIssues.text = if (row.issues.isEmpty()) {
                    context.getString(R.string.batch_no_issues)
                } else {
                    row.issues.first()
                }

                item.root.setOnClickListener {
                    FingerprintLabActivity.launch(context, row.profile.id)
                }
            }
        }
    }
}
