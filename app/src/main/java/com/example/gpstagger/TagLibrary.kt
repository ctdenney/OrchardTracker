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
 * Slot colours are fixed per position (0-5). Map markers are coloured per
 * *tag* at display time via [colorForTag] — the slot stored on each point is
 * only a record of which button recorded it, and tags drift between slots
 * over time, so using it for display paints one tag in several colours.
 */
object TagLibrary {

    private const val PREFS          = "tag_library"
    private const val KEY_TAGS       = "tags"
    private const val KEY_NEXT_ID    = "next_id"
    private const val KEY_SLOT       = "slot_"
    private const val KEY_SLOT_TS    = "slot_ts_"
    private const val KEY_INIT       = "initialized"
    private const val KEY_TOMBSTONES = "tag_tombstones"

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

    /**
     * Display colour for a tag name, resolved at render time:
     * the assigned slot's colour if the tag currently sits on a button
     * (so markers match the buttons), otherwise a hue hashed from the name.
     * Both the fallback hash (String.hashCode) and the hue formula match the
     * server web UI, so every surface paints the same tag the same colour.
     */
    fun colorForTag(ctx: Context, name: String): Int {
        val tag = loadTags(ctx).find { it.name == name }
        if (tag != null) {
            val slot = getSlotsForTag(ctx, tag.id).firstOrNull()
            if (slot != null) return slotColor(slot)
        }
        val hue = ((name.hashCode() % 360) + 360) % 360
        // Web uses hsl(hue, 65%, 45%); this is the same colour in HSV terms.
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.788f, 0.7425f))
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

        // Defaults get updatedAt = 0 so they never win a sync conflict against
        // names/assignments configured on another device or the server.
        val tags = names.mapIndexed { i, name -> Tag(i.toString(), name) }
        saveTags(ctx, tags)
        p.edit().putInt(KEY_NEXT_ID, tags.size).apply()
        tags.forEachIndexed { slot, tag -> setSlot(ctx, slot, tag.id, updatedAt = 0L) }
        p.edit().putBoolean(KEY_INIT, true).apply()
    }

    // ── Tag CRUD ──────────────────────────────────────────────────────────────

    fun getAllTags(ctx: Context): List<Tag> = loadTags(ctx)

    fun addTag(ctx: Context, name: String): Tag {
        val p  = prefs(ctx)
        val id = p.getInt(KEY_NEXT_ID, 0).toString()
        p.edit().putInt(KEY_NEXT_ID, id.toInt() + 1).apply()
        val tag  = Tag(id, name.trim(), System.currentTimeMillis())
        val tags = loadTags(ctx).toMutableList().also { it.add(tag) }
        saveTags(ctx, tags)
        return tag
    }

    /** Adds a tag with a specific ID (used during sync from server). */
    fun addTagWithId(ctx: Context, id: String, name: String, updatedAt: Long): Tag {
        val tag = Tag(id, name.trim(), updatedAt)
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

    fun renameTag(
        ctx: Context, id: String, newName: String,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        saveTags(ctx, loadTags(ctx).map {
            if (it.id == id) it.copy(name = newName.trim(), updatedAt = updatedAt) else it
        })
    }

    /**
     * Removes the tag from the library and clears it from any active slots.
     * A local deliberate delete records a tombstone so the deletion reaches
     * the server on the next sync; pass recordTombstone = false when applying
     * a delete that came *from* the server.
     */
    fun deleteTag(
        ctx: Context, id: String,
        updatedAt: Long = System.currentTimeMillis(),
        recordTombstone: Boolean = true
    ) {
        val edit = prefs(ctx).edit()
        getSlotAssignments(ctx).forEachIndexed { slot, tagId ->
            if (tagId == id) {
                edit.remove(KEY_SLOT + slot)
                edit.putLong(KEY_SLOT_TS + slot, updatedAt)
            }
        }
        edit.apply()
        saveTags(ctx, loadTags(ctx).filter { it.id != id })

        if (recordTombstone) {
            val obj = tombstonesJson(ctx).put(id, updatedAt)
            prefs(ctx).edit().putString(KEY_TOMBSTONES, obj.toString()).apply()
        }
    }

    /** Deleted tag id → deletion epoch-millis, awaiting push to the server. */
    fun getTagTombstones(ctx: Context): Map<String, Long> {
        val obj = tombstonesJson(ctx)
        return obj.keys().asSequence().associateWith { obj.getLong(it) }
    }

    /** Forgets tombstones that were successfully pushed to the server. */
    fun clearTagTombstones(ctx: Context, ids: Collection<String>) {
        val obj = tombstonesJson(ctx)
        ids.forEach { obj.remove(it) }
        prefs(ctx).edit().putString(KEY_TOMBSTONES, obj.toString()).apply()
    }

    private fun tombstonesJson(ctx: Context): JSONObject =
        try {
            JSONObject(prefs(ctx).getString(KEY_TOMBSTONES, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    // ── Slot assignments ──────────────────────────────────────────────────────

    /** Size-6 array of tag IDs; null means the slot is unassigned. */
    fun getSlotAssignments(ctx: Context): Array<String?> =
        Array(6) { slot -> prefs(ctx).getString(KEY_SLOT + slot, null) }

    fun setSlot(
        ctx: Context, slot: Int, tagId: String?,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        prefs(ctx).edit().apply {
            if (tagId == null) remove(KEY_SLOT + slot) else putString(KEY_SLOT + slot, tagId)
            putLong(KEY_SLOT_TS + slot, updatedAt)
        }.apply()
    }

    /** Epoch-millis of the slot's last deliberate edit; 0 if never edited. */
    fun getSlotUpdatedAt(ctx: Context, slot: Int): Long =
        prefs(ctx).getLong(KEY_SLOT_TS + slot, 0L)

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
                Tag(obj.getString("id"), obj.getString("name"), obj.optLong("updated_at", 0L))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveTags(ctx: Context, tags: List<Tag>) {
        val arr = JSONArray()
        tags.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("updated_at", it.updatedAt))
        }
        prefs(ctx).edit().putString(KEY_TAGS, arr.toString()).apply()
    }
}
