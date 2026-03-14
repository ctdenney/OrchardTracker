package com.example.gpstagger

import android.content.Context
import android.graphics.Color

/**
 * Persistent storage for the six button label names.
 * Labels are keyed by slot index (0-5) and fall back to the built-in defaults.
 */
object TagPreferences {

    private const val PREFS_NAME = "tag_labels"

    /** Built-in names used as fallback / hint text. */
    val DEFAULTS = listOf("Start", "End", "Obstacle", "Waypoint", "Sample", "Mark")

    /** Fetch all six labels, substituting defaults where the user has not set a custom name. */
    fun getLabels(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DEFAULTS.mapIndexed { i, default -> prefs.getString("tag_$i", default) ?: default }
    }

    /** Persist a single label; blank input restores the default for that slot. */
    fun setLabel(context: Context, slot: Int, label: String) {
        val value = label.trim().ifEmpty { DEFAULTS[slot] }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("tag_$slot", value)
            .apply()
    }

    /** Reset every label back to the built-in defaults. */
    fun resetAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Fixed color per slot — survives any label rename. */
    fun slotColor(slot: Int): Int = when (slot) {
        0    -> Color.parseColor("#2E7D32") // green  – originally Start
        1    -> Color.parseColor("#C62828") // red    – originally End
        2    -> Color.parseColor("#E65100") // orange – originally Obstacle
        3    -> Color.parseColor("#1565C0") // blue   – originally Waypoint
        4    -> Color.parseColor("#6A1B9A") // purple – originally Sample
        5    -> Color.parseColor("#F9A825") // amber  – originally Mark
        else -> Color.GRAY
    }

    /** Color resource id per slot (for XML / tint use). */
    fun slotColorRes(slot: Int): Int = when (slot) {
        0    -> R.color.tag_start
        1    -> R.color.tag_end
        2    -> R.color.tag_obstacle
        3    -> R.color.tag_waypoint
        4    -> R.color.tag_sample
        5    -> R.color.tag_mark
        else -> android.R.color.darker_gray
    }
}
