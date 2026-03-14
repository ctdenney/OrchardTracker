package com.example.gpstagger.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val timestamp: Long = System.currentTimeMillis()
) {
    fun formattedTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    fun toCsvRow(): String =
        "$id,$tag,$latitude,$longitude,$altitude,$accuracy,${formattedTime()}"
}
