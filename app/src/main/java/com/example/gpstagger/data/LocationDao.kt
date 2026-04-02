package com.example.gpstagger.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationDao {

    @Query("SELECT * FROM tagged_locations ORDER BY timestamp DESC")
    fun getAllLocations(): LiveData<List<TaggedLocation>>

    @Query("SELECT * FROM tagged_locations ORDER BY timestamp ASC")
    suspend fun getAllLocationsList(): List<TaggedLocation>

    @Insert
    suspend fun insert(location: TaggedLocation): Long

    @Query("DELETE FROM tagged_locations")
    suspend fun deleteAll()

    @Query("DELETE FROM tagged_locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tagged_locations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM tagged_locations")
    fun getCount(): LiveData<Int>

    // ── Sync queries ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM tagged_locations WHERE synced = 0")
    suspend fun getUnsynced(): List<TaggedLocation>

    @Query("UPDATE tagged_locations SET synced = 1 WHERE uuid IN (:uuids)")
    suspend fun markSynced(uuids: List<String>)

    @Query("SELECT * FROM tagged_locations WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): TaggedLocation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(location: TaggedLocation)

    @Query("DELETE FROM tagged_locations WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
