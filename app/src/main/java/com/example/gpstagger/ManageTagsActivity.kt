package com.example.gpstagger

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.gpstagger.databinding.ActivityManageTagsBinding
import com.google.android.material.button.MaterialButton

class ManageTagsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManageTagsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageTagsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Manage Buttons"
        }

        binding.btnAddTag.setOnClickListener { showAddTagDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── Rebuild both sections ─────────────────────────────────────────────────

    private fun refresh() {
        buildActiveSlots()
        buildTagLibrary()
    }

    // ── Active Buttons section ────────────────────────────────────────────────

    private fun buildActiveSlots() {
        val container = binding.activeButtonsContainer
        container.removeAllViews()
        val slots = TagLibrary.getSlotAssignments(this)
        for (slot in 0 until 6) {
            container.addView(buildSlotRow(slot, slots[slot]))
        }
    }

    private fun buildSlotRow(slot: Int, tagId: String?): LinearLayout {
        val dp = resources.displayMetrics.density
        val tag = tagId?.let { id -> TagLibrary.getAllTags(this).find { it.id == id } }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
            setPadding(
                (8 * dp).toInt(), (10 * dp).toInt(),
                (8 * dp).toInt(), (10 * dp).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * dp
                setColor(Color.parseColor("#2A2A2A"))
            }
            isClickable = true
            isFocusable = true
        }

        // Colored oval badge
        val badgeSize = (32 * dp).toInt()
        val badge = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).also {
                it.marginEnd = (10 * dp).toInt()
            }
            gravity = Gravity.CENTER
            text = (slot + 1).toString()
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TagLibrary.slotColor(slot))
            }
        }

        // Label
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = tag?.name ?: "(empty)"
            textSize = 17f
            setTextColor(if (tag != null) Color.WHITE else Color.parseColor("#666666"))
        }

        // Arrow
        val arrow = TextView(this).apply {
            text = "▾"
            textSize = 16f
            setTextColor(Color.parseColor("#888888"))
        }

        row.addView(badge)
        row.addView(label)
        row.addView(arrow)

        row.setOnClickListener { showSlotPickerDialog(slot) }
        return row
    }

    private fun showSlotPickerDialog(slot: Int) {
        val allTags = TagLibrary.getAllTags(this)
        val currentId = TagLibrary.getSlotAssignments(this)[slot]

        val items = mutableListOf<String>()
        val ids = mutableListOf<String?>()

        allTags.forEach { tag ->
            items.add(tag.name)
            ids.add(tag.id)
        }
        items.add("— Clear slot —")
        ids.add(null)

        val currentIndex = ids.indexOf(currentId).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Slot ${slot + 1} — assign tag")
            .setSingleChoiceItems(items.toTypedArray(), currentIndex) { dialog, which ->
                TagLibrary.setSlot(this, slot, ids[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Tag Library section ───────────────────────────────────────────────────

    private fun buildTagLibrary() {
        val container = binding.tagLibraryContainer
        container.removeAllViews()
        val tags = TagLibrary.getAllTags(this)
        if (tags.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No tags yet — tap + Add to create one."
                textSize = 14f
                setTextColor(Color.parseColor("#666666"))
                setPadding(8, 12, 8, 12)
            })
            return
        }
        tags.forEach { tag -> container.addView(buildTagRow(tag)) }
    }

    private fun buildTagRow(tag: Tag): LinearLayout {
        val dp = resources.displayMetrics.density
        val activeSlotsForTag = TagLibrary.getSlotsForTag(this, tag.id)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
            setPadding(
                (8 * dp).toInt(), (10 * dp).toInt(),
                (8 * dp).toInt(), (10 * dp).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * dp
                setColor(Color.parseColor("#2A2A2A"))
            }
        }

        // Slot color badges (which slots this tag is in)
        val badgeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * dp).toInt() }
        }
        val dotSize = (14 * dp).toInt()
        if (activeSlotsForTag.isEmpty()) {
            // Gray dot indicating tag is unassigned
            val dot = dotView(dotSize, Color.parseColor("#444444"), dp)
            badgeContainer.addView(dot)
        } else {
            activeSlotsForTag.forEach { slot ->
                val dot = dotView(dotSize, TagLibrary.slotColor(slot), dp)
                badgeContainer.addView(dot)
            }
        }

        // Tag name (tappable to rename)
        val nameView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = tag.name
            textSize = 17f
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
        }
        nameView.setOnClickListener { showRenameDialog(tag) }

        // Delete button
        val deleteBtn = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#CC4444"))
            minWidth = 0
            minimumWidth = 0
        }
        deleteBtn.setOnClickListener { showDeleteDialog(tag) }

        row.addView(badgeContainer)
        row.addView(nameView)
        row.addView(deleteBtn)
        return row
    }

    private fun dotView(size: Int, color: Int, dp: Float): android.view.View {
        return android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.marginEnd = (3 * dp).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showAddTagDialog() {
        val input = buildEditText()
        AlertDialog.Builder(this)
            .setTitle("New Tag")
            .setView(wrapInPadding(input))
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    TagLibrary.addTag(this, name)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun showRenameDialog(tag: Tag) {
        val input = buildEditText(tag.name)
        AlertDialog.Builder(this)
            .setTitle("Rename Tag")
            .setView(wrapInPadding(input))
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    TagLibrary.renameTag(this, tag.id, name)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun showDeleteDialog(tag: Tag) {
        val slots = TagLibrary.getSlotsForTag(this, tag.id)
        val slotNote = if (slots.isNotEmpty()) {
            "\n\nNote: this tag is currently assigned to slot${if (slots.size > 1) "s" else ""} " +
                slots.map { it + 1 }.joinToString(", ") + " and will be removed from those slots."
        } else ""

        AlertDialog.Builder(this)
            .setTitle("Delete \"${tag.name}\"?")
            .setMessage("This will permanently remove the tag from the library.$slotNote")
            .setPositiveButton("Delete") { _, _ ->
                TagLibrary.deleteTag(this, tag.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildEditText(prefill: String = ""): EditText {
        return EditText(this).apply {
            setText(prefill)
            setSelection(prefill.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            hint = "Tag name"
        }
    }

    private fun wrapInPadding(view: android.view.View): FrameLayout {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, 0, pad, 0)
            addView(view)
        }
    }
}
