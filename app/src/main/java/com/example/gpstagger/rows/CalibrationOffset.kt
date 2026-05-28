package com.example.gpstagger.rows

import android.content.Context
import com.example.gpstagger.data.RowEntity

/**
 * Persisted translational offset applied to every incoming GPS coordinate
 * before row-snapping logic runs. Use it when the satellite imagery used to
 * draw the rows on the server is offset from the GPS coordinate frame the
 * receiver reports — a common situation with orchards mapped against
 * imagery that's a few metres off true ground position.
 *
 * Internally we store the offset in metres (east, north) so the correction
 * stays geometrically constant regardless of where in the world the operator
 * is working. Conversion to/from lat-lng happens at the row's local frame.
 */
object CalibrationOffset {

    private const val PREFS = "row_calibration_prefs"
    private const val KEY_EAST_M = "offset_east_m"
    private const val KEY_NORTH_M = "offset_north_m"
    private const val KEY_ENABLED = "offset_enabled"

    data class Offset(val eastM: Double, val northM: Double, val enabled: Boolean) {
        val magnitudeM: Double get() = kotlin.math.sqrt(eastM * eastM + northM * northM)
    }

    fun get(ctx: Context): Offset {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Offset(
            eastM   = p.getFloat(KEY_EAST_M, 0f).toDouble(),
            northM  = p.getFloat(KEY_NORTH_M, 0f).toDouble(),
            enabled = p.getBoolean(KEY_ENABLED, false)
        )
    }

    fun save(ctx: Context, eastM: Double, northM: Double, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_EAST_M, eastM.toFloat())
            .putFloat(KEY_NORTH_M, northM.toFloat())
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Apply the (east, north) shift to a raw lat/lng. Returns (lat, lng). */
    fun apply(rawLat: Double, rawLng: Double, offset: Offset): DoubleArray {
        if (!offset.enabled || (offset.eastM == 0.0 && offset.northM == 0.0)) {
            return doubleArrayOf(rawLat, rawLng)
        }
        return Geo.fromLocalMeters(offset.eastM, offset.northM, rawLat, rawLng)
    }
}

/**
 * Captures positions during a calibration run and produces a translational
 * offset that, applied to incoming GPS, would best align the captured track
 * with a chosen [RowEntity].
 *
 * Algorithm: project every sample onto the row in the row's local frame,
 * record the signed perpendicular offset, then take the mean. The mean gives
 * a translation perpendicular to the row that minimises mean squared
 * perpendicular error — which is what we want when the satellite imagery is
 * uniformly offset.
 *
 * We deliberately do not estimate a rotation: with a single straight row we
 * cannot distinguish rotation from translation without other geometry, and
 * empirically the dominant orchard-imagery error is translational anyway.
 */
class CalibrationSession(private val row: RowEntity) {

    private val samples = ArrayList<DoubleArray>(256)  // each: [east, north]

    /** Add a raw GPS sample (no offset applied). */
    fun addSample(lat: Double, lng: Double) {
        samples.add(Geo.toLocalMeters(lat, lng, row.startLat, row.startLng))
    }

    val sampleCount: Int get() = samples.size

    /**
     * Compute the offset that should be added (in metres, in the row's local
     * frame) to incoming GPS positions so that future samples align with the
     * row. Returns null if not enough data was collected.
     */
    fun computeOffset(): Result? {
        if (samples.size < 5) return null

        val (ax, ay) = doubleArrayOf(0.0, 0.0)  // row start = origin
        val end = Geo.toLocalMeters(row.endLat, row.endLng, row.startLat, row.startLng)
        val bx = end[0]; val by = end[1]
        val length = Geo.lengthM(ax, ay, bx, by)
        if (length < 1.0) return null

        // Mean signed perpendicular offset across all samples.
        // Positive signed values mean samples sit to the left of start→end;
        // shifting samples by -perp restores alignment with the row line.
        var sumSignedPerp = 0.0
        var minT = Double.POSITIVE_INFINITY
        var maxT = Double.NEGATIVE_INFINITY
        for (s in samples) {
            val proj = Geo.projectOntoSegment(s[0], s[1], ax, ay, bx, by, clamp = false)
            sumSignedPerp += proj.signedPerpM
            if (proj.t < minT) minT = proj.t
            if (proj.t > maxT) maxT = proj.t
        }
        val alongCoverage = (maxT - minT).coerceAtLeast(0.0)
        val meanSignedPerp = sumSignedPerp / samples.size

        // To remove a +perp offset we shift the GPS in the opposite direction.
        // The shift vector in local frame is perpendicular to the row, oriented
        // such that positive perp is to the LEFT of (start→end). To reverse a
        // leftward error we move RIGHT, i.e. in the direction (dy, -dx)/length.
        val dx = bx - ax
        val dy = by - ay
        val perpEast  = (dy / length) * (-meanSignedPerp)   // right-perpendicular component east
        val perpNorth = (-dx / length) * (-meanSignedPerp)  // right-perpendicular component north
        // Because Geo's signed convention: cross = dx*(py-ay) - dy*(px-ax) > 0 → left of segment.
        // Right-perpendicular unit vector = (dy, -dx)/length. So shift = right_perp * meanSignedPerp.
        // The two negations above produce the same result; spelled out for clarity.

        return Result(
            offsetEastM = perpEast,
            offsetNorthM = perpNorth,
            meanPerpendicularM = meanSignedPerp,
            coverageFraction = alongCoverage.coerceIn(0.0, 1.0),
            sampleCount = samples.size
        )
    }

    data class Result(
        val offsetEastM: Double,
        val offsetNorthM: Double,
        val meanPerpendicularM: Double,
        val coverageFraction: Double,
        val sampleCount: Int
    ) {
        val magnitudeM: Double get() = kotlin.math.sqrt(offsetEastM * offsetEastM + offsetNorthM * offsetNorthM)
    }
}
