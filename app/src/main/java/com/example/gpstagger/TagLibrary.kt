package com.example.gpstagger

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for the tag library and active slot assignments.
 *
 * Persists to SharedPreferences ("tag_library").
 * On first run it migrates any labels previously saved by the old
 * TagPreferences ("tag_labels") storage, so existing data is preserved.
 *
 * Slot colours are fixed per position (0-5) regardless of which tag is
 * assigned there, so historical map markers keep their colour even after
 * a tag is renamed or reassigned.
 */
object TagLibrary {

    private const val PREFS       = "tag_library"
    private const val KEY_TAGS    = "tags"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_SLOT    = "slot_"
    private const val KEY_INIT    = "initialized"

    private val DEFAULTS = listOf(
        "Gopher", "Blight", "Blackberry", "Broken Irrigation", "Prune", "End Tank"
    )

    // ── Slot colours (immutable, keyed by position 0-5) ───────────────────────

    fun slotColor(slot: Int): Int = when (slot) {
        0    -> Color.parseColor("#2E7D32")
        1    -> Color.parseColor("#C62828")
        2    -> Color.parseColor("#E65100")
        3    -> Color.parseColor("#1565C0")
        4    -> Color.parseColor("#6A1B9A")
        5    -> Color.parseColor("#F9A825")
        else -> Color.GRAY
    }

    fun slotColorRes(slot: Int): Int = when (slot) {
        0    -> R.color.tag_start
        1    -> R.color.tag_end
        2    -> R.color.tag_obstacle
        3    -> R.color.tag_waypoint
        4    -> R.color.tag_sample
        5    -> R.color.tag_mark
        else -> android.R.color.darker_gray
    }

    // ── Initialisation / migration ────────────────────────────────────────────

    /**
     * Call once in every Activity's onCreate (or Application).
     * Reads old TagPreferences labels if present so no data is lost.
     */
    fun ensureInitialized(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_INIT, false)) return

        // Attempt migration from the old "tag_labels" SharedPreferences
        val oldPrefs = ctx.getSharedPreferences("tag_labels", Context.MODE_PRIVATE)
        val names = DEFAULTS.mapIndexed { i, default ->
            oldPrefs.getString("tag_$i", default) ?: default
        }

        val tags = names.mapIndexed { i, name -> Tag(i.toString(), name) }
        saveTags(ctx, tags)
        p.edit().putInt(KEY_NEXT_ID, tags.size).apply()
        tags.forEachIndexed { slot, tag -> setSlot(ctx, slot, tag.id) }
        p.edit().putBoolean(KEY_INIT, true).apply()
    }

    // ── Tag CRUD ──────────────────────────────────────────────────────────────

    fun getAllTags(ctx: Context): List<Tag> = loadTags(ctx)

    fun addTag(ctx: Context, name: String): Tag {
        val p  = prefs(ctx)
        val id = p.getInt(KEY_NEXT_ID, 0).toString()
        p.edit().putInt(KEY_NEXT_ID, id.toInt() + 1).apply()
        val tag  = Tag(id, name.trim())
        val tags = loadTags(ctx).toMutableList().also { it.add(tag) }
        saveTags(ctx, tags)
        return tag
    }

    /** Adds a tag with a specific ID (used during sync from server). */
    fun addTagWithId(ctx: Context, id: String, name: String): Tag {
        val tag = Tag(id, name.trim())
        val tags = loadTags(ctx).toMutableList().also { it.add(tag) }
        saveTags(ctx, tags)
        // Ensure next_id stays ahead of any imported ID
        val p = prefs(ctx)
        val numId = id.toIntOrNull() ?: 0
        if (numId >= p.getInt(KEY_NEXT_ID, 0)) {
            p.edit().putInt(KEY_NEXT_ID, numId + 1).apply()
        }
        return tag
    }

    fun renameTag(ctx: Context, id: String, newName: String) {
        saveTags(ctx, loadTags(ctx).map { if (it.id == id) it.copy(name = newName.trim()) else it })
    }

    /** Removes the tag from the library and clears it from any active slots. */
    fun deleteTag(ctx: Context, id: String) {
        val edit = prefs(ctx).edit()
        getSlotAssignments(ctx).forEachIndexed { slot, tagId ->
            if (tagId == id) edit.remove(KEY_SLOT + slot)
        }
        edit.apply()
        saveTags(ctx, loadTags(ctx).filter { it.id != id })
    }

    // ── Slot assignments ──────────────────────────────────────────────────────

    /** Size-6 array of tag IDs; null means the slot is unassigned. */
    fun getSlotAssignments(ctx: Context): Array<String?> =
        Array(6) { slot -> prefs(ctx).getString(KEY_SLOT + slot, null) }

    fun setSlot(ctx: Context, slot: Int, tagId: String?) {
        prefs(ctx).edit().apply {
            if (tagId == null) remove(KEY_SLOT + slot) else putString(KEY_SLOT + slot, tagId)
        }.apply()
    }

    fun getTagForSlot(ctx: Context, slot: Int): Tag? {
        val id = prefs(ctx).getString(KEY_SLOT + slot, null) ?: return null
        return loadTags(ctx).find { it.id == id }
    }

    fun getLabelForSlot(ctx: Context, slot: Int): String =
        getTagForSlot(ctx, slot)?.name ?: ""

    /** Returns the slot indices (0-5) that the given tag is currently assigned to. */
    fun getSlotsForTag(ctx: Context, id: String): List<Int> =
        getSlotAssignments(ctx).mapIndexedNotNull { slot, tagId ->
            if (tagId == id) slot else null
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadTags(ctx: Context): List<Tag> {
        val json = prefs(ctx).getString(KEY_TAGS, "") ?: ""
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                Tag(obj.getString("id"), obj.getString("name"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveTags(ctx: Context, tags: List<Tag>) {
        val arr = JSONArray()
        tags.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs(ctx).edit().putString(KEY_TAGS, arr.toString()).apply()
    }
}
