package com.example.gpstagger

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The server-designated orchard boundary (from /api/v1/orchard-bounds), cached
 * locally so the map can constrain panning to it even when offline. Mirrors the
 * boundary the web map uses to bound satellite tiles.
 */
object OrchardBounds {

    data class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    private const val PREFS = "orchard_bounds"
    private const val K_SET = "set"
    private const val K_S = "south"
    private const val K_W = "west"
    private const val K_N = "north"
    private const val K_E = "east"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun cached(ctx: Context): Box? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(K_SET, false)) return null
        return Box(
            p.getFloat(K_S, 0f).toDouble(), p.getFloat(K_W, 0f).toDouble(),
            p.getFloat(K_N, 0f).toDouble(), p.getFloat(K_E, 0f).toDouble()
        )
    }

    /** Fetches the boundary from the server and caches it. Null on any failure
     *  or when no boundary is set (server returns `null`). */
    suspend fun fetch(ctx: Context): Box? = withContext(Dispatchers.IO) {
        SyncConfig.load(ctx)
        if (!SyncConfig.isConfigured()) return@withContext null
        val body = try {
            val req = Request.Builder()
                .url("${SyncConfig.serverUrl}/api/v1/orchard-bounds")
                .addHeader("X-API-Key", SyncConfig.apiKey)
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.body?.string() ?: return@withContext null
            }
        } catch (e: Exception) {
            return@withContext null
        }
        if (body.isBlank() || body.trim() == "null") return@withContext null
        val box = try {
            val j = JSONObject(body)
            Box(j.getDouble("south"), j.getDouble("west"), j.getDouble("north"), j.getDouble("east"))
        } catch (e: Exception) {
            return@withContext null
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_SET, true)
            .putFloat(K_S, box.south.toFloat()).putFloat(K_W, box.west.toFloat())
            .putFloat(K_N, box.north.toFloat()).putFloat(K_E, box.east.toFloat())
            .apply()
        box
    }
}
