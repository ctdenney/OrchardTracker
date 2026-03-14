package com.example.gpstagger

import android.content.Context
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

object TileSources {

    /** Standard OpenStreetMap street tiles. */
    val OSM = TileSourceFactory.MAPNIK

    /**
     * ESRI World Imagery satellite tiles.
     * URL format: {baseUrl}/{z}/{y}/{x}  (note: y before x, unlike OSM)
     * Tile service: https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer
     * Attribution: Tiles © Esri — Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community
     */
    val ESRI_SATELLITE = object : OnlineTileSourceBase(
        "ESRISatellite", 0, 19, 256, ".jpg",
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
