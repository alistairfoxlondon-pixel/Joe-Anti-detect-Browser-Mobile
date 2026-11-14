package com.joebrowser.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.joebrowser.app.R
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.ExtensionItem
import com.joebrowser.app.databinding.FragmentExtensionsBinding
import com.joebrowser.app.databinding.ItemExtensionBinding
import com.joebrowser.app.AppEvents

class ExtensionsFragment : Fragment(), MainActivity.Refreshable {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!
    private val adapter = ExtensionAdapter(
        onOpen = { item ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.storeUrl)))
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), "No handler for that link", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        onDelete = { item ->
            AlertDialog.Builder(requireContext())
                .setTitle("Remove extension?")
                .setMessage("“${item.name}” will be removed from the library.")
                .setPositiveButton("Remove") { _, _ ->
                    Database.get(requireContext()).deleteExtension(item.id)
                    AppEvents.emitProfilesChanged(requireContext())
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.extensionList.layoutManager = LinearLayoutManager(requireContext())
        binding.extensionList.adapter = adapter
        binding.fabAddExtension.setOnClickListener { showAddDialog() }
        binding.emptyAddExtension.setOnClickListener { showAddDialog() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showAddDialog() {
        val ctx = requireContext()
        val nameInput = android.widget.EditText(ctx).apply {
            hint = "Name (e.g. uBlock Origin)"
            setSingleLine(true)
        }
        val urlInput = android.widget.EditText(ctx).apply {
            hint = "https://chromewebstore.google.com/detail/…"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(nameInput)
            addView(urlInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Add extension link")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                var url = urlInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || url.isEmpty()) {
                    android.widget.Toast.makeText(ctx, "Name and URL are required", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "https://$url"
                Database.get(ctx).saveExtension(ExtensionItem(name = name, storeUrl = url))
                AppEvents.emitProfilesChanged(ctx)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun refresh() {
        if (_binding == null) return
        val items = Database.get(requireContext()).getAllExtensions().sortedByDescending { it.addedAt }
        adapter.submit(items)
        val empty = items.isEmpty()
        binding.extensionEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.extensionList.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        binding.fabAddExtension.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ExtensionAdapter(
        private val onOpen: (ExtensionItem) -> Unit,
        private val onDelete: (ExtensionItem) -> Unit
    ) : RecyclerView.Adapter<ExtensionAdapter.Holder>() {

        private var items: List<ExtensionItem> = emptyList()

        fun submit(list: List<ExtensionItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemExtensionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val binding: ItemExtensionBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: ExtensionItem) {
                binding.extensionName.text = item.name
                binding.extensionStore.text = runCatching { java.net.URI(item.storeUrl).host }.getOrNull() ?: item.storeUrl
                binding.extensionAdded.text = "Added " + java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
                    .format(java.util.Date(item.addedAt))
                binding.extensionOpen.setOnClickListener { onOpen(item) }
                binding.extensionDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}
