package com.example.gpstagger

import android.content.Context

/** Stores server sync configuration in SharedPreferences. */
object SyncConfig {
    private const val PREFS = "sync_config"
    private const val KEY_URL = "server_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_LAST_SYNC = "last_sync_at"
    private const val KEY_DEVICE_ID = "device_id"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String = ""
    var apiKey: String = ""

    fun load(ctx: Context) {
        val p = prefs(ctx)
        serverUrl = p.getString(KEY_URL, "") ?: ""
        apiKey = p.getString(KEY_API_KEY, "") ?: ""
    }

    fun save(ctx: Context, url: String, key: String) {
        var normalized = url.trim().trimEnd('/')
        if (normalized.isNotEmpty() && !normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        serverUrl = normalized
        apiKey = key
        prefs(ctx).edit()
            .putString(KEY_URL, serverUrl)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun isConfigured(): Boolean =
        serverUrl.isNotBlank()

    fun getLastSyncAt(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LAST_SYNC, 0)

    fun setLastSyncAt(ctx: Context, millis: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_SYNC, millis).apply()
    }

    fun getDeviceId(ctx: Context): String {
        val p = prefs(ctx)
        var id = p.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().take(8)
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }
}
