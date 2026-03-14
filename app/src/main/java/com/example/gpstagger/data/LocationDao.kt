package com.example.gpstagger.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
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

    @Query("SELECT COUNT(*) FROM tagged_locations")
    fun getCount(): LiveData<Int>
}
