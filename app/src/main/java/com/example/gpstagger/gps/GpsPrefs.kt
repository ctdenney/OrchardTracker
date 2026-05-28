package com.example.gpstagger.gps

import android.content.Context

/**
 * Persisted user preference for which GPS source to use and how to talk to it.
 */
object GpsPrefs {

    enum class Source { INTERNAL, USB }

    private const val PREFS = "gps_prefs"
    private const val KEY_SOURCE = "source"
    private const val KEY_BAUD = "baud"

    /** Common NMEA baud rates. 4800 is the NMEA 0183 default; modern receivers default to 9600. */
    val BAUD_OPTIONS = listOf(4800, 9600, 19200, 38400, 57600, 115200)
    const val DEFAULT_BAUD = 9600

    fun source(ctx: Context): Source =
        when (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCE, null)) {
            "USB" -> Source.USB
            else -> Source.INTERNAL
        }

    fun setSource(ctx: Context, source: Source) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE, source.name)
            .apply()
    }

    fun baudRate(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BAUD, DEFAULT_BAUD)

    fun setBaudRate(ctx: Context, baud: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BAUD, baud)
            .apply()
    }
}
