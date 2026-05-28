package com.example.gpstagger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Mirrors the server's `rows` table: a straight line between two GPS
 * endpoints representing an orchard / vineyard row. Synced via the same
 * last-write-wins protocol as [TaggedLocation].
 */
@Entity(tableName = "rows")
data class RowEntity(
    @PrimaryKey
    val uuid: String,
    val name: String,
    val block: String = "",
    @ColumnInfo(name = "start_lat") val startLat: Double,
    @ColumnInfo(name = "start_lng") val startLng: Double,
    @ColumnInfo(name = "end_lat")   val endLat: Double,
    @ColumnInfo(name = "end_lng")   val endLng: Double,
    @ColumnInfo(name = "width_m")   val widthM: Double = 0.0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val synced: Boolean = false,
    /**
     * Smallest and largest along-row fractions ever observed for this row
     * since the last coverage reset. `null` means "not visited yet". The
     * derived span `max - min` is what determines whether the row counts as
     * covered (see [coverageState]).
     *
     * These are local-only — coverage is a property of an in-progress task
     * for this operator, not something the server cares about.
     */
    @ColumnInfo(name = "coverage_min_t") val coverageMinT: Double? = null,
    @ColumnInfo(name = "coverage_max_t") val coverageMaxT: Double? = null
) {
    fun coverageState(coveredThreshold: Double = 0.9, partialThreshold: Double = 0.1): Coverage {
        val mn = coverageMinT
        val mx = coverageMaxT
        if (mn == null || mx == null) return Coverage.NONE
        val span = (mx - mn).coerceAtLeast(0.0)
        return when {
            span >= coveredThreshold  -> Coverage.COVERED
            span >= partialThreshold  -> Coverage.PARTIAL
            else                      -> Coverage.NONE
        }
    }

    enum class Coverage { NONE, PARTIAL, COVERED }
}
