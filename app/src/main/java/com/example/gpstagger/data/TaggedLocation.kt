package com.example.gpstagger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Entity(tableName = "tagged_locations")
data class TaggedLocation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val tag: String,
    /** Button slot index (0–5). -1 for records created before this field existed. */
    val slot: Int = -1,
    val timestamp: Long = System.currentTimeMillis(),
    /** Unique ID for server sync. Generated on creation. */
    val uuid: String = UUID.randomUUID().toString(),
    /** Last modification time (millis) for sync conflict resolution. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    /** Whether this record has been synced to the server. */
    val synced: Boolean = false
) {
    fun formattedTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    fun toCsvRow(): String =
        "$id,$tag,$latitude,$longitude,$altitude,$accuracy,${formattedTime()}"
}
