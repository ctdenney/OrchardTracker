package com.example.gpstagger.rows

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class GeoTest {

    private val refLat = 45.0
    private val refLng = -120.0

    @Test
    fun `toLocalMeters and fromLocalMeters roundtrip`() {
        val cases = listOf(0.0 to 0.0, 100.0 to 50.0, -250.0 to 900.0, 3.2 to -0.7)
        for ((east, north) in cases) {
            val (lat, lng) = Geo.fromLocalMeters(east, north, refLat, refLng)
            val (e2, n2) = Geo.toLocalMeters(lat, lng, refLat, refLng)
            assertEquals(east, e2, 1e-6)
            assertEquals(north, n2, 1e-6)
        }
    }

    @Test
    fun `one milli-degree of latitude is about 111 meters north`() {
        val (east, north) = Geo.toLocalMeters(refLat + 0.001, refLng, refLat, refLng)
        assertEquals(0.0, east, 1e-9)
        assertEquals(111.19, north, 0.05)
    }

    @Test
    fun `projection onto middle of segment`() {
        // Horizontal segment (0,0) -> (100,0); point 10 m above its midpoint.
        val p = Geo.projectOntoSegment(50.0, 10.0, 0.0, 0.0, 100.0, 0.0)
        assertEquals(0.5, p.t, 1e-9)
        assertEquals(10.0, p.perpendicularM, 1e-9)
        // Direction is +east; +north is to the left of travel.
        assertEquals(10.0, p.signedPerpM, 1e-9)
    }

    @Test
    fun `point right of travel direction has negative signed perpendicular`() {
        val p = Geo.projectOntoSegment(50.0, -10.0, 0.0, 0.0, 100.0, 0.0)
        assertEquals(-10.0, p.signedPerpM, 1e-9)
    }

    @Test
    fun `projection clamps t but preserves rawT`() {
        val p = Geo.projectOntoSegment(150.0, 10.0, 0.0, 0.0, 100.0, 0.0, clamp = true)
        assertEquals(1.0, p.t, 1e-9)
        assertEquals(1.5, p.rawT, 1e-9)
        // Clamped closest point is the endpoint (100, 0).
        assertEquals(sqrt(50.0 * 50.0 + 10.0 * 10.0), p.perpendicularM, 1e-9)
    }

    @Test
    fun `unclamped projection lets t leave the segment`() {
        val p = Geo.projectOntoSegment(150.0, 10.0, 0.0, 0.0, 100.0, 0.0, clamp = false)
        assertEquals(1.5, p.t, 1e-9)
        // Unclamped closest point is on the extended line, so only the
        // perpendicular component remains.
        assertEquals(10.0, p.perpendicularM, 1e-9)
    }

    @Test
    fun `degenerate segment is treated as a point`() {
        val p = Geo.projectOntoSegment(3.0, 4.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, p.t, 1e-9)
        assertEquals(5.0, p.perpendicularM, 1e-9)
        assertEquals(0.0, p.signedPerpM, 1e-9)
    }

    @Test
    fun `lengthM is euclidean`() {
        assertEquals(5.0, Geo.lengthM(0.0, 0.0, 3.0, 4.0), 1e-9)
    }
}
