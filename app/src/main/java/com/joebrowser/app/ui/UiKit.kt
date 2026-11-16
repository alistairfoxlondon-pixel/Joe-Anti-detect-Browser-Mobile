package com.joebrowser.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.chip.Chip
import com.joebrowser.app.R
import com.joebrowser.app.databinding.ItemSectionHeaderBinding
import com.joebrowser.app.databinding.ItemSettingRowBinding

/**
 * Shared UI helpers.
 *
 * Two rules keep every screen consistent without a per-screen stylesheet:
 *  1. Nothing composes raw dp arithmetic by hand — use [dp]/[dpFloat] or a dimen resource.
 *  2. Nothing invents a colour — everything comes from the theme tokens in colors.xml.
 *
 * The row builders ([settingRow], [sectionHeader], [switchRow]) exist because settings and
 * tool screens repeat the same three shapes dozens of times; building them in one place is
 * what stops each screen from inventing its own spacing.
 */
object UiKit {

    /**
     * Avatar palette: eight fixed, evenly-weighted hues that all read against white text.
     * Deliberately not brand green — an avatar is an identifier, not an action.
     */
    /**
     * Five tones of the one brand green, not a rainbow.
     *
     * Profile avatars need to be distinguishable at a glance; they do not need to introduce
     * blue, purple, orange and pink into a single-colour identity. Every tone here is dark
     * enough for white initials (≥4.5:1), so the hue can vary without the contrast varying.
     */
    private val avatarPalette = intArrayOf(
        0xFF0B3D2E.toInt(), 0xFF0C7A52.toInt(), 0xFF14624B.toInt(),
        0xFF1F5C46.toInt(), 0xFF178A5E.toInt()
    )

    fun avatarColor(key: String): Int {
        var hash = 7
        key.forEach { hash = (hash * 31 + it.code) and 0x7FFFFFFF }
        return avatarPalette[hash % avatarPalette.size]
    }

    fun avatarBackground(key: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(avatarColor(key))
    }

    fun dotBackground(colorRes: Int, context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ContextCompat.getColor(context, colorRes))
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun dpFloat(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    fun hideKeyboard(view: View?) {
        if (view == null) return
        runCatching {
            val manager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    fun showKeyboard(view: View?) {
        if (view == null) return
        runCatching {
            view.requestFocus()
            val manager = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun rangeFilter(min: Int, max: Int): InputFilter = InputFilter { source, _, _, dest, dstart, dend ->
        val candidate = source.toString()
        if (candidate.isEmpty()) return@InputFilter null
        if (!candidate.all { it.isDigit() }) return@InputFilter ""
        val text = (dest.subSequence(0, dstart).toString() + candidate + dest.subSequence(dend, dest.length))
        val value = text.toIntOrNull() ?: return@InputFilter ""
        if (value in min..max) null else ""
    }

    fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color)
    )

    fun flagEmoji(countryCode: String?): String {
        val code = countryCode?.uppercase()?.takeIf { it.length == 2 } ?: return ""
        val first = 0x1F1E6 - 'A'.code + code[0].code
        val second = 0x1F1E6 - 'A'.code + code[1].code
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun osIconRes(os: String?): Int = when (os) {
        "Windows 11", "Windows 10" -> R.drawable.ic_os_windows
        "macOS" -> R.drawable.ic_os_apple
        "iPhone" -> R.drawable.ic_os_phone
        "iPad" -> R.drawable.ic_os_tablet
        "Android" -> R.drawable.ic_os_android
        "Linux" -> R.drawable.ic_os_linux
        else -> R.drawable.ic_os_android
    }

    fun formatRelative(ts: Long): String {
        if (ts <= 0L) return "Never"
        val delta = System.currentTimeMillis() - ts
        return when {
            delta < 60_000L -> "Just now"
            delta < 3_600_000L -> "${delta / 60_000L}m ago"
            delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
            delta < 7L * 86_400_000L -> "${delta / 86_400_000L}d ago"
            else -> java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
                .format(java.util.Date(ts))
        }
    }

    fun formatLatency(ms: Int): String = if (ms < 0) "—" else "$ms ms"

    // ------------------------------------------------------------------ rows

    /**
     * A settings row: icon, title, optional caption and a caller-supplied accessory.
     * Returns the binding so the caller can wire the click and keep a reference to the switch.
     */
    fun settingRow(
        parent: ViewGroup,
        iconRes: Int,
        title: String,
        caption: String? = null,
        accessory: View? = null,
        onClick: (() -> Unit)? = null
    ): ItemSettingRowBinding {
        val row = ItemSettingRowBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        row.rowIcon.setImageResource(iconRes)
        row.rowTitle.text = title
        if (caption.isNullOrBlank()) {
            row.rowCaption.visibility = View.GONE
        } else {
            row.rowCaption.text = caption
            row.rowCaption.visibility = View.VISIBLE
        }
        if (accessory != null) row.rowAccessory.addView(accessory)
        if (onClick != null) row.rowRoot.setOnClickListener { onClick() }
        else row.rowRoot.isClickable = false
        parent.addView(row.root)
        return row
    }

    /** A switch row. The switch itself is returned so callers can set/track the state. */
    fun switchRow(
        parent: ViewGroup,
        iconRes: Int,
        title: String,
        caption: String?,
        checked: Boolean,
        onChecked: (Boolean) -> Unit
    ): Pair<ItemSettingRowBinding, MaterialSwitch> {
        val toggle = MaterialSwitch(parent.context).apply {
            isChecked = checked
            contentDescription = title
        }
        val row = settingRow(parent, iconRes, title, caption, toggle)
        // The whole row toggles: a 56dp target beats a 32dp switch.
        row.rowRoot.setOnClickListener {
            toggle.isChecked = !toggle.isChecked
        }
        toggle.setOnCheckedChangeListener { _, value -> onChecked(value) }
        return row to toggle
    }

    /** Section title with an optional trailing action. */
    fun sectionHeader(
        parent: ViewGroup,
        title: String,
        action: String? = null,
        onAction: (() -> Unit)? = null
    ): ItemSectionHeaderBinding {
        val binding = ItemSectionHeaderBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        binding.sectionTitle.text = title
        if (action.isNullOrBlank()) {
            binding.sectionAction.visibility = View.GONE
        } else {
            binding.sectionAction.text = action
            binding.sectionAction.visibility = View.VISIBLE
            if (onAction != null) binding.sectionAction.setOnClickListener { onAction() }
        }
        parent.addView(binding.root)
        return binding
    }

    /** A card wrapper (18dp surface, hairline border) used to group rows on one screen. */
    fun cardContainer(parent: ViewGroup): LinearLayout = LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_card)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(context, 14) }
        parent.addView(this)
    }

    /** Hairline divider between rows inside a [cardContainer]. */
    fun divider(parent: LinearLayout, insetStartDp: Int = 48) {
        val line = View(parent.context)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(parent.context, 1)
        )
        params.marginStart = dp(parent.context, insetStartDp)
        line.layoutParams = params
        line.setBackgroundColor(ContextCompat.getColor(parent.context, R.color.line_soft))
        parent.addView(line)
    }

    /** A small rounded label (status, protocol, count). */
    fun badge(context: Context, text: String, colorRes: Int = R.color.accent): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10f
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
            )
            setTextColor(ContextCompat.getColor(context, colorRes))
            setBackgroundResource(R.drawable.bg_chip_neutral)
            setPadding(dp(context, 7), dp(context, 3), dp(context, 7), dp(context, 3))
        }

    /** Icon-only button with a real 48dp touch target. */
    fun iconButton(context: Context, iconRes: Int, description: String, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(context, R.color.text_dim))
            contentDescription = description
            val target = dp(context, 48)
            layoutParams = LinearLayout.LayoutParams(target, target)
            val pad = dp(context, 13)
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

    /** Compact action chip used for detector sites and engine filters. */
    fun actionChip(
        context: Context,
        label: String,
        iconRes: Int? = null,
        onClick: () -> Unit
    ): Chip = Chip(context).apply {
        text = label
        isCheckable = false
        chipMinHeight = dpFloat(context, 36f)
        setEnsureMinTouchTargetSize(false)
        if (iconRes != null) {
            setChipIconResource(iconRes)
            chipIconSize = dpFloat(context, 15f)
            chipIconTint = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.text_dim)
            )
        }
        setTextColor(ContextCompat.getColor(context, R.color.text))
        chipBackgroundColor = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.surface)
        )
        chipStrokeColor = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.line)
        )
        chipStrokeWidth = dpFloat(context, 1f)
        setOnClickListener { onClick() }
    }

    /**
     * Small text button used inside cards ("Check", "Edit", "See all").
     *
     * Styled by hand rather than by pointing at a Material attribute: the attribute name for
     * the text-button style is not part of the public R surface, so referencing it does not
     * compile. Setting the colours directly is both stable and explicit.
     */
    fun textButton(context: Context, label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            text = label
            isAllCaps = false
            textSize = 12.5f
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, android.R.color.transparent)
            )
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 40)
            )
            setOnClickListener { onClick() }
        }

    /**
     * Dropdown helper for [android.widget.AutoCompleteTextView].
     *
     * `setSimpleItems` exists on MaterialAutoCompleteTextView only; this takes the plain
     * widget and wires an ArrayAdapter, which is what the profile editor already does — one
     * implementation instead of two subtly different ones.
     */
    fun bindDropdown(
        view: android.widget.AutoCompleteTextView,
        items: List<String>,
        onPick: ((Int) -> Unit)? = null
    ) {
        view.setAdapter(
            android.widget.ArrayAdapter(
                view.context, android.R.layout.simple_dropdown_item_1line, items
            )
        )
        view.threshold = 0
        if (onPick != null) {
            view.setOnItemClickListener { _, _, position, _ -> onPick(position) }
        }
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) runCatching { view.showDropDown() }
            else runCatching { view.dismissDropDown() }
        }
        view.setOnClickListener { runCatching { view.showDropDown() } }
    }

    /** Grow a view to fill remaining horizontal space in a LinearLayout row. */
    fun View.fillWidth(weight: Float = 1f) {
        val params = layoutParams as? LinearLayout.LayoutParams ?: return
        params.width = 0
        params.weight = weight
        layoutParams = params
    }

    /** Centre a view vertically inside a horizontal LinearLayout row. */
    fun View.centerVertical() {
        (layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.CENTER_VERTICAL
    }
}
