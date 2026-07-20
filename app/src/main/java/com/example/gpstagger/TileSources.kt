package com.example.gpstagger

import android.content.Context
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

object TileSources {

    /**
     * Deepest zoom the user can reach. Past the tile source's native max,
     * osmdroid upscales the deepest available tile — pixelated but still
     * useful for sub-tile precision when tagging points or sighting rows.
     */
    const val MAX_ZOOM = 22
    const val OSM_NATIVE_MAX = 19
    // Esri World Imagery has real z20 tiles over the farm; past that its server
    // returns "map data not yet available" placeholders, so cap native tiles at
    // 20 and let osmdroid upscale beyond (see MAX_ZOOM).
    const val ESRI_NATIVE_MAX = 20

    /**
     * Standard OpenStreetMap street tiles. Wraps the stock MAPNIK source so
     * we can extend the allowed zoom beyond OSM's native 19.
     */
    val OSM = XYTileSource(
        "Mapnik", 0, OSM_NATIVE_MAX, 256, ".png",
        arrayOf(
            "https://a.tile.openstreetmap.org/",
            "https://b.tile.openstreetmap.org/",
            "https://c.tile.openstreetmap.org/"
        ),
        "© OpenStreetMap contributors"
    )

    /**
     * ESRI World Imagery satellite tiles.
     * URL format: {baseUrl}/{z}/{y}/{x}  (note: y before x, unlike OSM)
     * Tile service: https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer
     * Attribution: Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community
     */
    val ESRI_SATELLITE = object : OnlineTileSourceBase(
        "ESRISatellite", 0, ESRI_NATIVE_MAX, 256, ".jpg",
        arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            "${baseUrl}${MapTileIndex.getZoom(pMapTileIndex)}" +
            "/${MapTileIndex.getY(pMapTileIndex)}" +
            "/${MapTileIndex.getX(pMapTileIndex)}"
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private const val PREFS_NAME   = "app_prefs"
    private const val KEY_TILE_SRC = "tile_source"
    private const val VAL_ESRI     = "esri"
    private const val VAL_OSM      = "osm"

    fun isEsriSelected(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TILE_SRC, VAL_OSM) == VAL_ESRI

    fun saveSelection(context: Context, esri: Boolean) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TILE_SRC, if (esri) VAL_ESRI else VAL_OSM).apply()

    fun saved(context: Context) = if (isEsriSelected(context)) ESRI_SATELLITE else OSM
}
