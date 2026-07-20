package com.example.gpstagger.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
abstract class ResumePointDao {

    @Query("SELECT * FROM resume_points WHERE deleted = 0 ORDER BY created_at")
    abstract fun observeActive(): LiveData<List<ResumePointEntity>>

    @Query("SELECT * FROM resume_points WHERE deleted = 0")
    abstract suspend fun getActive(): List<ResumePointEntity>

    @Query("SELECT * FROM resume_points WHERE uuid = :uuid LIMIT 1")
    abstract suspend fun getByUuid(uuid: String): ResumePointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(point: ResumePointEntity)

    /** Soft delete so the tombstone syncs; observeActive/getActive filter it out. */
    @Query("UPDATE resume_points SET deleted = 1, synced = 0, updated_at = :now WHERE uuid = :uuid")
    abstract suspend fun softDelete(uuid: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM resume_points WHERE synced = 0")
    abstract suspend fun getUnsynced(): List<ResumePointEntity>

    @Query("UPDATE resume_points SET synced = 1 WHERE uuid IN (:uuids)")
    abstract suspend fun markSynced(uuids: List<String>)
}
