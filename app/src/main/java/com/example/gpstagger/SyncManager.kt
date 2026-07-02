package com.example.gpstagger

import android.content.Context
import com.example.gpstagger.data.LocationDatabase
import com.example.gpstagger.data.RowEntity
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
        val rowDao = db.rowDao()
        val deviceId = SyncConfig.getDeviceId(ctx)
        val lastSync = SyncConfig.getLastSyncAt(ctx)

        // Gather unsynced locations
        val unsynced = dao.getUnsynced()

        // Gather pending deletions (tombstones)
        val pendingDeletions = dao.getPendingDeletions()

        // Gather tag library state, including tombstones for locally
        // deleted tags
        val tags = TagLibrary.getAllTags(ctx)
        val tagTombstones = TagLibrary.getTagTombstones(ctx)
        val slots = TagLibrary.getSlotAssignments(ctx)

        // Gather unsynced rows — rows recorded in Drive Mode land here until
        // the next successful sync pushes them to the server.
        val unsyncedRows = rowDao.getUnsynced()

        // Coverage spans, so the web map can show task progress. Separate
        // channel from row definitions: each side merges LWW on
        // coverage_updated_at.
        val coverageChanged = rowDao.getTouchedCoverage()

        // Build sync request — include tombstones as deleted locations
        val allLocations = locationsToJson(unsynced)
        pendingDeletions.forEach { tombstone ->
            allLocations.put(JSONObject().apply {
                put("uuid", tombstone.uuid)
                put("latitude", 0.0)
                put("longitude", 0.0)
                put("altitude", 0.0)
                put("accuracy", 0.0)
                put("tag", "")
                put("slot", -1)
                put("timestamp", tombstone.deletedAt)
                put("created_at", tombstone.deletedAt)
                put("updated_at", tombstone.deletedAt)
                put("deleted", true)
            })
        }

        val allTags = tagsToJson(tags)
        tagTombstones.forEach { (id, deletedAt) ->
            allTags.put(JSONObject().apply {
                put("id", id)
                put("name", "")
                put("updated_at", deletedAt)
                put("deleted", true)
            })
        }

        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("last_sync_at", lastSync)
            put("locations", allLocations)
            put("tags", allTags)
            put("slots", slotsToJson(ctx, slots))
            put("rows", rowsToJson(unsyncedRows))
            put("coverage", coverageToJson(coverageChanged))
        }

        val response = try {
            val request = Request.Builder()
                .url("${SyncConfig.serverUrl}/api/v1/sync")
                .addHeader("X-API-Key", SyncConfig.apiKey)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
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

        // Clear tombstones that were successfully pushed
        if (pendingDeletions.isNotEmpty()) {
            dao.clearTombstones(pendingDeletions.map { it.uuid })
        }
        if (tagTombstones.isNotEmpty()) {
            TagLibrary.clearTagTombstones(ctx, tagTombstones.keys)
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

        // Apply incoming tags (only when the server's copy is newer than ours,
        // and carrying the server's timestamp so we don't re-push it as a
        // fresh local edit)
        val incomingTags = json.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until incomingTags.length()) {
            val t = incomingTags.getJSONObject(i)
            val id = t.getString("id")
            val deleted = t.optBoolean("deleted", false)
            val updatedAt = t.optLong("updated_at", 0L)
            val existing = TagLibrary.getAllTags(ctx).find { it.id == id }
            if (existing != null && existing.updatedAt >= updatedAt) continue

            if (deleted) {
                if (existing != null) {
                    TagLibrary.deleteTag(ctx, id, updatedAt, recordTombstone = false)
                }
            } else {
                val name = t.getString("name")
                if (existing != null) {
                    TagLibrary.renameTag(ctx, id, name, updatedAt)
                } else {
                    TagLibrary.addTagWithId(ctx, id, name, updatedAt)
                }
            }
        }

        // Apply incoming slot assignments (same newer-wins rule)
        val incomingSlots = json.optJSONArray("slots") ?: JSONArray()
        for (i in 0 until incomingSlots.length()) {
            val s = incomingSlots.getJSONObject(i)
            val slot = s.getInt("slot")
            val tagId = s.optString("tag_id", "")
            val updatedAt = s.optLong("updated_at", 0L)
            if (TagLibrary.getSlotUpdatedAt(ctx, slot) >= updatedAt) continue
            TagLibrary.setSlot(ctx, slot, tagId.ifEmpty { null }, updatedAt)
        }

        // Apply incoming rows
        val incomingRows = json.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until incomingRows.length()) {
            val r = incomingRows.getJSONObject(i)
            val uuid = r.getString("uuid")
            val incomingUpdated = r.getLong("updated_at")
            val existing = rowDao.getByUuid(uuid)
            if (existing != null && existing.updatedAt >= incomingUpdated) continue
            rowDao.upsert(
                RowEntity(
                    uuid      = uuid,
                    name      = r.optString("name", ""),
                    block     = r.optString("block", ""),
                    startLat  = r.getDouble("start_lat"),
                    startLng  = r.getDouble("start_lng"),
                    endLat    = r.getDouble("end_lat"),
                    endLng    = r.getDouble("end_lng"),
                    widthM    = r.optDouble("width_m", 0.0),
                    updatedAt = incomingUpdated,
                    deleted   = r.optBoolean("deleted", false),
                    synced    = true,
                    // A row *definition* update must not clobber the driven
                    // state — coverage rides its own LWW channel below.
                    coverageMinT = existing?.coverageMinT,
                    coverageMaxT = existing?.coverageMaxT,
                    coverageUpdatedAt = existing?.coverageUpdatedAt ?: 0
                )
            )
        }

        // Apply incoming coverage (LWW guard lives in the DAO query). Our own
        // just-pushed spans echo back with an equal timestamp and no-op.
        val incomingCoverage = json.optJSONArray("coverage") ?: JSONArray()
        for (i in 0 until incomingCoverage.length()) {
            val c = incomingCoverage.getJSONObject(i)
            rowDao.applyCoverage(
                uuid      = c.getString("row_uuid"),
                minT      = if (c.isNull("min_t")) null else c.getDouble("min_t"),
                maxT      = if (c.isNull("max_t")) null else c.getDouble("max_t"),
                updatedAt = c.getLong("updated_at")
            )
        }

        // Mark our pushed rows as synced
        if (unsyncedRows.isNotEmpty()) {
            rowDao.markSynced(unsyncedRows.map { it.uuid })
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
                put("updated_at", tag.updatedAt)
                put("deleted", false)
            })
        }
        return arr
    }

    private fun slotsToJson(ctx: Context, assignments: Array<String?>): JSONArray {
        val arr = JSONArray()
        assignments.forEachIndexed { slot, tagId ->
            arr.put(JSONObject().apply {
                put("slot", slot)
                put("tag_id", tagId ?: "")
                put("updated_at", TagLibrary.getSlotUpdatedAt(ctx, slot))
            })
        }
        return arr
    }

    private fun coverageToJson(rows: List<RowEntity>): JSONArray {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(JSONObject().apply {
                put("row_uuid", r.uuid)
                put("min_t", r.coverageMinT ?: JSONObject.NULL)
                put("max_t", r.coverageMaxT ?: JSONObject.NULL)
                put("updated_at", r.coverageUpdatedAt)
            })
        }
        return arr
    }

    private fun rowsToJson(rows: List<RowEntity>): JSONArray {
        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(JSONObject().apply {
                put("uuid", r.uuid)
                put("name", r.name)
                put("block", r.block)
                put("start_lat", r.startLat)
                put("start_lng", r.startLng)
                put("end_lat", r.endLat)
                put("end_lng", r.endLng)
                put("width_m", r.widthM)
                put("updated_at", r.updatedAt)
                put("deleted", r.deleted)
            })
        }
        return arr
    }
}
