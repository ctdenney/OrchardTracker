package com.example.gpstagger.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class LocationDao {

    @Query("SELECT * FROM tagged_locations ORDER BY timestamp DESC")
    abstract fun getAllLocations(): LiveData<List<TaggedLocation>>

    @Query("SELECT * FROM tagged_locations ORDER BY timestamp ASC")
    abstract suspend fun getAllLocationsList(): List<TaggedLocation>

    @Insert
    abstract suspend fun insert(location: TaggedLocation): Long

    @Query("SELECT uuid FROM tagged_locations WHERE id = :id")
    abstract suspend fun getUuidById(id: Long): String?

    @Query("SELECT uuid FROM tagged_locations WHERE id IN (:ids)")
    abstract suspend fun getUuidsByIds(ids: List<Long>): List<String>

    @Query("DELETE FROM tagged_locations WHERE id = :id")
    abstract suspend fun rawDeleteById(id: Long)

    @Query("DELETE FROM tagged_locations WHERE id IN (:ids)")
    abstract suspend fun rawDeleteByIds(ids: List<Long>)

    @Query("DELETE FROM tagged_locations")
    abstract suspend fun rawDeleteAll()

    @Query("SELECT uuid FROM tagged_locations")
    abstract suspend fun getAllUuids(): List<String>

    /** Delete a single point and record a tombstone for sync. */
    @Transaction
    open suspend fun deleteById(id: Long) {
        val uuid = getUuidById(id)
        rawDeleteById(id)
        if (uuid != null) insertTombstone(DeletedLocation(uuid))
    }

    /** Delete multiple points and record tombstones for sync. */
    @Transaction
    open suspend fun deleteByIds(ids: List<Long>) {
        val uuids = getUuidsByIds(ids)
        rawDeleteByIds(ids)
        uuids.forEach { insertTombstone(DeletedLocation(it)) }
    }

    /** Delete all points and record tombstones for sync. */
    @Transaction
    open suspend fun deleteAll() {
        val uuids = getAllUuids()
        rawDeleteAll()
        uuids.forEach { insertTombstone(DeletedLocation(it)) }
    }

    @Query("SELECT COUNT(*) FROM tagged_locations")
    abstract fun getCount(): LiveData<Int>

    // ── Sync queries ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM tagged_locations WHERE synced = 0")
    abstract suspend fun getUnsynced(): List<TaggedLocation>

    @Query("UPDATE tagged_locations SET synced = 1 WHERE uuid IN (:uuids)")
    abstract suspend fun markSynced(uuids: List<String>)

    @Query("SELECT * FROM tagged_locations WHERE uuid = :uuid LIMIT 1")
    abstract suspend fun getByUuid(uuid: String): TaggedLocation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(location: TaggedLocation)

    @Query("DELETE FROM tagged_locations WHERE uuid = :uuid")
    abstract suspend fun deleteByUuid(uuid: String)

    // ── Tombstones ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTombstone(tombstone: DeletedLocation)

    @Query("SELECT * FROM deleted_locations")
    abstract suspend fun getPendingDeletions(): List<DeletedLocation>

    @Query("DELETE FROM deleted_locations WHERE uuid IN (:uuids)")
    abstract suspend fun clearTombstones(uuids: List<String>)
}
