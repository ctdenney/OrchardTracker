package com.example.gpstagger

import android.content.Context
import com.example.gpstagger.data.LocationDatabase
import com.example.gpstagger.data.TaggedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Handles bidirectional sync with the OrchardTracker server. */
object SyncManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    sealed class Result {
        data class Success(val pushed: Int, val pulled: Int) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun sync(ctx: Context): Result = withContext(Dispatchers.IO) {
        if (!SyncConfig.isConfigured()) return@withContext Result.Error("Server not configured")

        val db = LocationDatabase.getDatabase(ctx)
        val dao = db.locationDao()
        val deviceId = SyncConfig.getDeviceId(ctx)
        val lastSync = SyncConfig.getLastSyncAt(ctx)

        // Gather unsynced locations
        val unsynced = dao.getUnsynced()

        // Gather tag library state
        val tags = TagLibrary.getAllTags(ctx)
        val slots = TagLibrary.getSlotAssignments(ctx)

        // Build sync request
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("last_sync_at", lastSync)
            put("locations", locationsToJson(unsynced))
            put("tags", tagsToJson(tags))
            put("slots", slotsToJson(slots))
        }

        val request = Request.Builder()
            .url("${SyncConfig.serverUrl}/api/v1/sync")
            .addHeader("X-API-Key", SyncConfig.apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            return@withContext Result.Error("Network error: ${e.message}")
        }

        if (!response.isSuccessful) {
            return@withContext Result.Error("Server error: ${response.code}")
        }

        val json = try {
            JSONObject(response.body?.string() ?: "{}")
        } catch (e: Exception) {
            return@withContext Result.Error("Invalid response")
        }

        val serverTime = json.optLong("server_time", System.currentTimeMillis())

        // Mark pushed locations as synced
        if (unsynced.isNotEmpty()) {
            dao.markSynced(unsynced.map { it.uuid })
        }

        // Apply incoming locations
        val incomingLocations = json.optJSONArray("locations") ?: JSONArray()
        var pulled = 0
        for (i in 0 until incomingLocations.length()) {
            val loc = incomingLocations.getJSONObject(i)
            val uuid = loc.getString("uuid")
            val deleted = loc.optBoolean("deleted", false)

            if (deleted) {
                dao.deleteByUuid(uuid)
                pulled++
                continue
            }

            val existing = dao.getByUuid(uuid)
            val incoming = TaggedLocation(
                id        = existing?.id ?: 0,
                uuid      = uuid,
                latitude  = loc.getDouble("latitude"),
                longitude = loc.getDouble("longitude"),
                altitude  = loc.optDouble("altitude", 0.0),
                accuracy  = loc.optDouble("accuracy", 0.0).toFloat(),
                tag       = loc.getString("tag"),
                slot      = loc.optInt("slot", -1),
                timestamp = loc.getLong("timestamp"),
                updatedAt = loc.getLong("updated_at"),
                synced    = true
            )

            // Only apply if server version is newer
            if (existing == null || incoming.updatedAt > existing.updatedAt) {
                dao.upsert(incoming)
                pulled++
            }
        }

        // Apply incoming tags
        val incomingTags = json.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until incomingTags.length()) {
            val t = incomingTags.getJSONObject(i)
            val id = t.getString("id")
            val deleted = t.optBoolean("deleted", false)
            if (deleted) {
                TagLibrary.deleteTag(ctx, id)
            } else {
                val name = t.getString("name")
                val existingTags = TagLibrary.getAllTags(ctx)
                if (existingTags.any { it.id == id }) {
                    TagLibrary.renameTag(ctx, id, name)
                } else {
                    TagLibrary.addTagWithId(ctx, id, name)
                }
            }
        }

        // Apply incoming slot assignments
        val incomingSlots = json.optJSONArray("slots") ?: JSONArray()
        for (i in 0 until incomingSlots.length()) {
            val s = incomingSlots.getJSONObject(i)
            val slot = s.getInt("slot")
            val tagId = s.optString("tag_id", "")
            TagLibrary.setSlot(ctx, slot, tagId.ifEmpty { null })
        }

        SyncConfig.setLastSyncAt(ctx, serverTime)

        Result.Success(pushed = unsynced.size, pulled = pulled)
    }

    private fun locationsToJson(locations: List<TaggedLocation>): JSONArray {
        val arr = JSONArray()
        locations.forEach { loc ->
            arr.put(JSONObject().apply {
                put("uuid", loc.uuid)
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("altitude", loc.altitude)
                put("accuracy", loc.accuracy.toDouble())
                put("tag", loc.tag)
                put("slot", loc.slot)
                put("timestamp", loc.timestamp)
                put("created_at", loc.timestamp)
                put("updated_at", loc.updatedAt)
                put("deleted", false)
            })
        }
        return arr
    }

    private fun tagsToJson(tags: List<Tag>): JSONArray {
        val arr = JSONArray()
        tags.forEach { tag ->
            arr.put(JSONObject().apply {
                put("id", tag.id)
                put("name", tag.name)
                put("updated_at", System.currentTimeMillis())
                put("deleted", false)
            })
        }
        return arr
    }

    private fun slotsToJson(assignments: Array<String?>): JSONArray {
        val arr = JSONArray()
        assignments.forEachIndexed { slot, tagId ->
            arr.put(JSONObject().apply {
                put("slot", slot)
                put("tag_id", tagId ?: "")
                put("updated_at", System.currentTimeMillis())
            })
        }
        return arr
    }
}
