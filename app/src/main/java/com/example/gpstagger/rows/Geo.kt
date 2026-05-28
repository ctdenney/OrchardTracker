package com.example.gpstagger.rows

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Local-tangent-plane math used to project GPS positions onto an orchard row.
 *
 * Rows are at most a few hundred metres long, so we can treat the area
 * around a reference point as a flat metric plane. Working in metres lets us
 * compute perpendicular offsets, along-row progress and hysteresis bounds
 * directly — far simpler than chasing the math in WGS84 degrees.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG_TO_RAD = PI / 180.0

    /**
     * Convert a lat/lng to metric (east, north) offsets from a reference point.
     * Accurate to better than 1 cm for offsets under ~1 km — well past row scale.
     */
    fun toLocalMeters(lat: Double, lng: Double, refLat: Double, refLng: Double): DoubleArray {
        val cosRef = cos(refLat * DEG_TO_RAD)
        val east   = (lng - refLng) * DEG_TO_RAD * EARTH_RADIUS_M * cosRef
        val north  = (lat - refLat) * DEG_TO_RAD * EARTH_RADIUS_M
        return doubleArrayOf(east, north)
    }

    /** Inverse of [toLocalMeters]. */
    fun fromLocalMeters(east: Double, north: Double, refLat: Double, refLng: Double): DoubleArray {
        val cosRef = cos(refLat * DEG_TO_RAD)
        val lat = refLat + (north / EARTH_RADIUS_M) / DEG_TO_RAD
        val lng = refLng + (east / (EARTH_RADIUS_M * cosRef)) / DEG_TO_RAD
        return doubleArrayOf(lat, lng)
    }

    /**
     * Result of projecting a point onto a line segment.
     * - [t] is the fraction along the segment (0 = start, 1 = end). Clamped
     *   if [clamp] was true at the call site.
     * - [perpendicularM] is the absolute perpendicular distance from the
     *   point to the (extended) line.
     * - [signedPerpM] is the signed perpendicular distance, with positive
     *   values to the left of the segment direction (start → end).
     */
    data class Projection(
        val t: Double,
        val perpendicularM: Double,
        val signedPerpM: Double,
        val rawT: Double
    )

    /** Project (px, py) onto the segment (ax, ay)→(bx, by). Caller decides whether to clamp t. */
    fun projectOntoSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
        clamp: Boolean = true
    ): Projection {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        if (lengthSq < 1e-9) {
            // Degenerate: start == end. Treat as a point.
            val distSq = (px - ax) * (px - ax) + (py - ay) * (py - ay)
            return Projection(t = 0.0, perpendicularM = sqrt(distSq), signedPerpM = 0.0, rawT = 0.0)
        }
        val rawT = ((px - ax) * dx + (py - ay) * dy) / lengthSq
        val t = if (clamp) rawT.coerceIn(0.0, 1.0) else rawT
        val closestX = ax + t * dx
        val closestY = ay + t * dy
        val perp = sqrt((px - closestX) * (px - closestX) + (py - closestY) * (py - closestY))
        // Signed perpendicular: cross product of segment direction × point vector.
        // Positive when (px, py) is to the left of the start→end direction.
        val cross = dx * (py - ay) - dy * (px - ax)
        val signed = cross / sqrt(lengthSq)
        return Projection(t = t, perpendicularM = perp, signedPerpM = signed, rawT = rawT)
    }

    /** Segment length in metres. */
    fun lengthM(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }
}
