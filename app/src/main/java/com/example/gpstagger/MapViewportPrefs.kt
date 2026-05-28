package com.example.gpstagger

import android.content.Context

/**
 * Persisted last-known map viewport (centre + zoom). Saved on pause and
 * restored on next open so an unintended re-launch doesn't dump the user
 * back at zoom 3 over Kansas.
 */
object MapViewportPrefs {

    private const val PREFS = "map_viewport_prefs"
    private const val KEY_LAT  = "lat"
    private const val KEY_LNG  = "lng"
    private const val KEY_ZOOM = "zoom"

    data class Viewport(val lat: Double, val lng: Double, val zoom: Double)

    fun get(ctx: Context): Viewport? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY_LAT)) return null
        return Viewport(
            lat  = java.lang.Double.longBitsToDouble(p.getLong(KEY_LAT, 0)),
            lng  = java.lang.Double.longBitsToDouble(p.getLong(KEY_LNG, 0)),
            zoom = java.lang.Double.longBitsToDouble(p.getLong(KEY_ZOOM, 0))
        )
    }

    fun save(ctx: Context, lat: Double, lng: Double, zoom: Double) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAT,  java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LNG,  java.lang.Double.doubleToRawLongBits(lng))
            .putLong(KEY_ZOOM, java.lang.Double.doubleToRawLongBits(zoom))
            .apply()
    }
}
