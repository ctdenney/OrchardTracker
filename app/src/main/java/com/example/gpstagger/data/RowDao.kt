package com.example.gpstagger.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
abstract class RowDao {

    @Query("SELECT * FROM rows WHERE deleted = 0 ORDER BY block, name")
    abstract fun observeActive(): LiveData<List<RowEntity>>

    @Query("SELECT * FROM rows WHERE deleted = 0")
    abstract suspend fun getActive(): List<RowEntity>

    @Query("SELECT * FROM rows WHERE uuid = :uuid LIMIT 1")
    abstract suspend fun getByUuid(uuid: String): RowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(row: RowEntity)

    @Query("SELECT * FROM rows WHERE synced = 0")
    abstract suspend fun getUnsynced(): List<RowEntity>

    @Query("UPDATE rows SET synced = 1 WHERE uuid IN (:uuids)")
    abstract suspend fun markSynced(uuids: List<String>)

    // ── Coverage ─────────────────────────────────────────────────────────────
    //
    // Coverage is stored as the (min, max) span of along-row fractions ever
    // observed for a row. Widening the span uses MIN/MAX so the SQL is
    // monotonic regardless of which direction the operator drove the row.
    // Coverage data is intentionally local-only: it represents a task in
    // progress, not durable orchard metadata.

    @Query("""
        UPDATE rows
        SET coverage_min_t = MIN(COALESCE(coverage_min_t, :t), :t),
            coverage_max_t = MAX(COALESCE(coverage_max_t, :t), :t)
        WHERE uuid = :uuid
    """)
    abstract suspend fun widenCoverage(uuid: String, t: Double)

    @Query("UPDATE rows SET coverage_min_t = NULL, coverage_max_t = NULL")
    abstract suspend fun resetAllCoverage()
}
