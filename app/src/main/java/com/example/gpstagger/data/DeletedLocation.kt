package com.example.gpstagger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Tombstone record: tracks UUIDs of locally deleted points so deletions can be synced. */
@Entity(tableName = "deleted_locations")
data class DeletedLocation(
    @PrimaryKey
    val uuid: String,
    val deletedAt: Long = System.currentTimeMillis()
)
