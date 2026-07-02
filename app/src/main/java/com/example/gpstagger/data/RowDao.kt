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
    // Coverage syncs to the server last-write-wins on coverage_updated_at,
    // independently of the row definition's updated_at.

    @Query("""
        UPDATE rows
        SET coverage_min_t = MIN(COALESCE(coverage_min_t, :t), :t),
            coverage_max_t = MAX(COALESCE(coverage_max_t, :t), :t),
            coverage_updated_at = :now
        WHERE uuid = :uuid
    """)
    abstract suspend fun widenCoverage(uuid: String, t: Double, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE rows
        SET coverage_min_t = NULL, coverage_max_t = NULL, coverage_updated_at = :now
    """)
    abstract suspend fun resetAllCoverage(now: Long = System.currentTimeMillis())

    /**
     * Every row whose coverage has ever been touched (driven or reset).
     * Pushed in full on every sync — the payload is one tiny record per row,
     * and a full push can't lose updates to device/server clock skew the way
     * a changed-since filter can. The server's LWW merge dedups.
     */
    @Query("SELECT * FROM rows WHERE coverage_updated_at > 0")
    abstract suspend fun getTouchedCoverage(): List<RowEntity>

    /** Applies a server coverage record if it is newer than ours (LWW). */
    @Query("""
        UPDATE rows
        SET coverage_min_t = :minT, coverage_max_t = :maxT, coverage_updated_at = :updatedAt
        WHERE uuid = :uuid AND coverage_updated_at < :updatedAt
    """)
    abstract suspend fun applyCoverage(uuid: String, minT: Double?, maxT: Double?, updatedAt: Long)
}
