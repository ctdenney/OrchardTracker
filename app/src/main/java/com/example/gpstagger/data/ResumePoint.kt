package com.example.gpstagger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Mirrors the server's `resume_points` table: a spot where work paused mid-row
 * — typically where a spray tank ran empty — so the operator can drive back and
 * pick up with the next tank. Several can be open at once.
 *
 * [rowUuid] / [rowT] are optional context captured by Drive Mode: which row the
 * mark fell on and the along-row fraction (0..1) there. Synced last-write-wins
 * on [updatedAt] like the other records; deletion is soft so a cleared point
 * propagates instead of reappearing on the next sync.
 */
@Entity(tableName = "resume_points")
data class ResumePointEntity(
    @PrimaryKey
    val uuid: String,
    val latitude: Double,
    val longitude: Double,
    val label: String = "",
    @ColumnInfo(name = "row_uuid") val rowUuid: String = "",
    @ColumnInfo(name = "row_t") val rowT: Double? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val synced: Boolean = false
)
