package com.example.gpstagger.gps

import android.location.Location
import android.os.SystemClock
import java.util.Calendar
import java.util.TimeZone

/**
 * Streaming parser for NMEA 0183 sentences emitted by external GPS receivers.
 *
 * Feed bytes (or strings) from a serial port via [feed]; the [onFix] callback
 * fires whenever a sentence updates the current fix enough that a downstream
 * consumer should see a new [Location]. We synthesize fixes from a blend of
 * sentence types so callers get the richest data the receiver provides:
 *
 *   - GGA  → lat/lon, altitude, HDOP, fix quality, satellites in use
 *   - RMC  → lat/lon, speed, course, UTC time/date
 *   - GSA  → HDOP / fix mode (used to refine accuracy when GGA is silent)
 *
 * Talker prefixes are accepted for any constellation (GP, GL, GA, GB, GN, BD,
 * QZ) — only the three-letter sentence type matters.
 *
 * NMEA 0183 reference: NMEA 0183 v4.10, "Standard for Interfacing Marine
 * Electronic Devices". Implementations across receivers vary in which optional
 * fields are populated; missing fields are silently skipped.
 */
class NmeaParser(private val onFix: (Location) -> Unit) {

    private val buffer = StringBuilder(128)

    /** Mutable accumulator — sentences fill in pieces; we emit on GGA or RMC. */
    private var lat: Double? = null
    private var lon: Double? = null
    private var altitude: Double? = null
    private var speedMps: Float? = null
    private var bearingDeg: Float? = null
    private var hdop: Float? = null
    private var satellites: Int? = null
    private var utcMillis: Long? = null
    private var fixQuality: Int = 0

    /** Push raw bytes from the serial port. Splits on CR/LF and parses each line. */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) {
            val c = bytes[i].toInt().toChar()
            if (c == '\n' || c == '\r') {
                if (buffer.isNotEmpty()) {
                    parseLine(buffer.toString())
                    buffer.setLength(0)
                }
            } else {
                buffer.append(c)
                // Defensive cap — a real NMEA line is ≤ 82 chars; anything longer
                // is junk and we shouldn't grow forever on a misbehaving stream.
                if (buffer.length > 256) buffer.setLength(0)
            }
        }
    }

    private fun parseLine(line: String) {
        if (line.length < 7 || line[0] != '$') return
        val starIdx = line.lastIndexOf('*')
        val body = if (starIdx > 0) line.substring(1, starIdx) else line.substring(1)

        // Optional checksum verification — bytes XOR'd between '$' and '*'.
        if (starIdx > 0 && starIdx + 2 < line.length) {
            val provided = line.substring(starIdx + 1, starIdx + 3).toIntOrNull(16) ?: return
            var sum = 0
            for (ch in body) sum = sum xor ch.code
            if (sum != provided) return
        }

        val fields = body.split(',')
        if (fields[0].length < 5) return
        val type = fields[0].substring(2)  // strip talker prefix (GP, GN, GL, ...)

        when (type) {
            "GGA" -> parseGga(fields)
            "RMC" -> parseRmc(fields)
            "GSA" -> parseGsa(fields)
        }
    }

    /**
     * GGA — Global Positioning System Fix Data.
     * 0: $xxGGA
     * 1: UTC hhmmss.sss
     * 2,3: lat (ddmm.mmmm), N/S
     * 4,5: lon (dddmm.mmmm), E/W
     * 6: fix quality (0 = invalid, 1 = GPS, 2 = DGPS, 4 = RTK fixed, 5 = RTK float)
     * 7: satellites in use
     * 8: HDOP
     * 9,10: altitude, units (M)
     */
    private fun parseGga(f: List<String>) {
        if (f.size < 10) return
        val quality = f[6].toIntOrNull() ?: 0
        fixQuality = quality
        if (quality == 0) return

        val parsedLat = parseLatLon(f[2], f[3]) ?: return
        val parsedLon = parseLatLon(f[4], f[5]) ?: return
        lat = parsedLat
        lon = parsedLon
        satellites = f[7].toIntOrNull()
        hdop = f[8].toFloatOrNull()
        altitude = f[9].toDoubleOrNull()
        utcMillis = mergeUtc(timeStr = f[1], dateStr = null) ?: utcMillis
        emit()
    }

    /**
     * RMC — Recommended Minimum Specific GNSS Data.
     * 0: $xxRMC
     * 1: UTC hhmmss.sss
     * 2: status (A = active, V = void)
     * 3,4: lat, N/S
     * 5,6: lon, E/W
     * 7: speed over ground (knots)
     * 8: course over ground (degrees true)
     * 9: date ddmmyy
     */
    private fun parseRmc(f: List<String>) {
        if (f.size < 10) return
        if (f[2] != "A") return  // void / no fix

        val parsedLat = parseLatLon(f[3], f[4]) ?: return
        val parsedLon = parseLatLon(f[5], f[6]) ?: return
        lat = parsedLat
        lon = parsedLon
        f[7].toFloatOrNull()?.let { knots -> speedMps = knots * KNOTS_TO_MPS }
        f[8].toFloatOrNull()?.let { bearingDeg = it }
        utcMillis = mergeUtc(timeStr = f[1], dateStr = f[9]) ?: utcMillis
        emit()
    }

    /**
     * GSA — GNSS DOP and Active Satellites.
     * 16: HDOP (we ignore PDOP/VDOP for our purposes)
     */
    private fun parseGsa(f: List<String>) {
        if (f.size < 17) return
        f[16].toFloatOrNull()?.let { hdop = it }
    }

    private fun emit() {
        val la = lat ?: return
        val lo = lon ?: return
        val alt = altitude
        val spd = speedMps
        val brg = bearingDeg
        val dop = hdop
        val fix = fixQuality
        val tMs = utcMillis
        val loc = Location("usb-nmea").apply {
            latitude = la
            longitude = lo
            if (alt != null) altitude = alt
            if (spd != null) speed = spd
            if (brg != null) bearing = brg
            // Horizontal accuracy estimate: HDOP * nominal UERE (~5 m for
            // consumer L1 receivers, lower for RTK). Without DGPS/RTK metadata
            // we use 5 m as a conservative default that matches what FLP reports
            // for a typical phone GPS fix.
            if (dop != null) {
                val uere = when (fix) {
                    4 -> 0.05f  // RTK fixed
                    5 -> 0.5f   // RTK float
                    2 -> 1.0f   // DGPS / SBAS
                    else -> 5.0f
                }
                accuracy = dop * uere
            }
            time = tMs ?: System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        onFix(loc)
    }

    // ── Field parsers ────────────────────────────────────────────────────────

    /** Convert NMEA "ddmm.mmmm" + hemisphere to signed decimal degrees. */
    private fun parseLatLon(raw: String, hemi: String): Double? {
        if (raw.isEmpty() || hemi.isEmpty()) return null
        val dot = raw.indexOf('.')
        if (dot < 3) return null
        val degEnd = dot - 2
        val deg = raw.substring(0, degEnd).toIntOrNull() ?: return null
        val min = raw.substring(degEnd).toDoubleOrNull() ?: return null
        val value = deg + min / 60.0
        return if (hemi == "S" || hemi == "W") -value else value
    }

    /**
     * Combine UTC time and (optional) date fields into epoch millis. If no date
     * is supplied we reuse the system date — fine for tagging timestamps where
     * the date already advanced before the receiver got its first RMC.
     */
    private fun mergeUtc(timeStr: String, dateStr: String?): Long? {
        if (timeStr.length < 6) return null
        val hh = timeStr.substring(0, 2).toIntOrNull() ?: return null
        val mm = timeStr.substring(2, 4).toIntOrNull() ?: return null
        val ss = timeStr.substring(4, 6).toIntOrNull() ?: return null
        val millis = if (timeStr.length > 7) {
            (timeStr.substring(7).padEnd(3, '0').take(3).toIntOrNull() ?: 0)
        } else 0

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        if (dateStr != null && dateStr.length == 6) {
            val dd = dateStr.substring(0, 2).toIntOrNull() ?: return null
            val mo = dateStr.substring(2, 4).toIntOrNull() ?: return null
            val yy = dateStr.substring(4, 6).toIntOrNull() ?: return null
            cal.set(Calendar.YEAR, 2000 + yy)
            cal.set(Calendar.MONTH, mo - 1)
            cal.set(Calendar.DAY_OF_MONTH, dd)
        }
        cal.set(Calendar.HOUR_OF_DAY, hh)
        cal.set(Calendar.MINUTE, mm)
        cal.set(Calendar.SECOND, ss)
        cal.set(Calendar.MILLISECOND, millis)
        return cal.timeInMillis
    }

    companion object {
        private const val KNOTS_TO_MPS = 0.514444f
    }
}
