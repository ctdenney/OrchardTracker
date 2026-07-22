package com.example.gpstagger.rows

import com.example.gpstagger.data.RowEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests drive the tracker with positions expressed in a local metric plane
 * anchored at ([REF_LAT], [REF_LNG]) and converted to lat/lng with the same
 * [Geo] math the production code uses, so distances in the assertions are
 * real metres.
 */
class RowTrackerTest {

    companion object {
        private const val REF_LAT = 45.0
        private const val REF_LNG = -120.0
    }

    /** A row parallel to the x (east) axis at northing [yM], [lengthM] long. */
    private fun rowAt(yM: Double, name: String, lengthM: Double = 100.0, widthM: Double = 0.0): RowEntity {
        val (sLat, sLng) = Geo.fromLocalMeters(0.0, yM, REF_LAT, REF_LNG)
        val (eLat, eLng) = Geo.fromLocalMeters(lengthM, yM, REF_LAT, REF_LNG)
        return RowEntity(
            uuid = name, name = name,
            startLat = sLat, startLng = sLng,
            endLat = eLat, endLng = eLng,
            widthM = widthM, updatedAt = 0L
        )
    }

    /** Feed the tracker a position given in local metres. */
    private fun RowTracker.updateAt(xM: Double, yM: Double, rows: List<RowEntity>): RowTracker.State {
        val (lat, lng) = Geo.fromLocalMeters(xM, yM, REF_LAT, REF_LNG)
        return update(lat, lng, rows)
    }

    private val rowA = rowAt(0.0, "A")
    private val rowB = rowAt(3.0, "B")

    @Test
    fun `empty row list yields no lock`() {
        val state = RowTracker().updateAt(10.0, 0.0, emptyList())
        assertNull(state.row)
        assertEquals(0, state.candidates)
    }

    @Test
    fun `locks the nearest row and reports progress`() {
        val state = RowTracker().updateAt(25.0, 0.4, listOf(rowA, rowB))
        assertEquals("A", state.row?.name)
        assertEquals(0.25, state.alongFraction, 1e-6)
        assertEquals(25.0, state.alongDistanceM, 1e-3)
        assertEquals(100.0, state.rowLengthM, 1e-3)
        // B is 2.6 m away — outside its 1.5 m half-width + 1.0 m slack
        // corridor, so it isn't even a candidate.
        assertEquals(1, state.candidates)
    }

    @Test
    fun `signed perpendicular reports side of row`() {
        // North of row A (left when driving start -> end, i.e. eastbound).
        val state = RowTracker().updateAt(50.0, 0.5, listOf(rowA))
        assertEquals(0.5, state.signedPerpendicularM, 1e-3)
    }

    @Test
    fun `small excursions toward a neighbor do not steal the lock`() {
        val tracker = RowTracker()
        val rows = listOf(rowA, rowB)
        assertEquals("A", tracker.updateAt(10.0, 0.0, rows).row?.name)

        // 1.6 m north: B is now marginally closer (1.4 m vs 1.6 m) but the
        // 0.2 m advantage is far below the 1.5 m hysteresis margin.
        repeat(10) {
            assertEquals("A", tracker.updateAt(10.0, 1.6, rows).row?.name)
        }
    }

    @Test
    fun `decisive move switches lock only after debounce samples`() {
        val tracker = RowTracker(switchSamples = 3)
        val rows = listOf(rowA, rowB)
        assertEquals("A", tracker.updateAt(10.0, 0.0, rows).row?.name)

        // 2.4 m north: both rows still candidates (corridor gate is 2.5 m);
        // B is 0.6 m away, advantage 1.8 m >= 1.5 m margin.
        assertEquals("A", tracker.updateAt(11.0, 2.4, rows).row?.name)  // streak 1
        assertEquals("A", tracker.updateAt(12.0, 2.4, rows).row?.name)  // streak 2
        assertEquals("B", tracker.updateAt(13.0, 2.4, rows).row?.name)  // streak 3 -> switch
    }

    @Test
    fun `interrupted contender streak resets the debounce`() {
        val tracker = RowTracker(switchSamples = 3)
        val rows = listOf(rowA, rowB)
        tracker.updateAt(10.0, 0.0, rows)

        tracker.updateAt(11.0, 2.4, rows)                               // streak 1
        tracker.updateAt(12.0, 2.4, rows)                               // streak 2
        tracker.updateAt(13.0, 0.0, rows)                               // back near A: streak cleared
        assertEquals("A", tracker.updateAt(14.0, 2.4, rows).row?.name)  // streak 1 again
        assertEquals("A", tracker.updateAt(15.0, 2.4, rows).row?.name)  // streak 2
        assertEquals("B", tracker.updateAt(16.0, 2.4, rows).row?.name)  // streak 3 -> switch
    }

    @Test
    fun `row width sets the hysteresis margin`() {
        // 6 m wide rows -> margin 3 m. At y=3.4, B leads by 3.4-0.6=2.8 m,
        // below the 3 m margin: never convincing enough. (With the default
        // 1.5 m margin this same position would switch after the debounce.)
        val wideA = rowAt(0.0, "A", widthM = 6.0)
        val wideB = rowAt(4.0, "B", widthM = 6.0)
        val tracker = RowTracker()
        val rows = listOf(wideA, wideB)
        assertEquals("A", tracker.updateAt(10.0, 0.0, rows).row?.name)
        repeat(10) {
            assertEquals("A", tracker.updateAt(10.0, 3.4, rows).row?.name)
        }
    }

    @Test
    fun `positions slightly beyond the endpoints still track`() {
        // 4 m before the start is inside the 5 m slack.
        val state = RowTracker().updateAt(-4.0, 0.0, listOf(rowA))
        assertNotNull(state.row)
        assertEquals(0.0, state.alongFraction, 1e-6)  // clamped to the start
    }

    @Test
    fun `positions far beyond the endpoints drop the row`() {
        val state = RowTracker().updateAt(-10.0, 0.0, listOf(rowA))
        assertNull(state.row)
        assertEquals(0, state.candidates)
    }

    @Test
    fun `positions outside the row corridor yield no lock`() {
        // 2.6 m from the centerline: just past the 1.5 m half-width + 1.0 m
        // GPS-slack gate. Driving the orchard's outside edge must not record
        // the nearest row.
        assertNull(RowTracker().updateAt(50.0, 2.6, listOf(rowA)).row)
        assertNotNull(RowTracker().updateAt(50.0, 2.4, listOf(rowA)).row)
    }

    @Test
    fun `row width widens the candidacy corridor`() {
        // 6 m wide row: corridor gate 3.0 + 1.0 = 4.0 m.
        val wide = rowAt(0.0, "wide", widthM = 6.0)
        assertNotNull(RowTracker().updateAt(10.0, 3.5, listOf(wide)).row)
        assertNull(RowTracker().updateAt(10.0, 3.5, listOf(rowA)).row)
    }

    @Test
    fun `coverage needs a settled lock and along-row movement`() {
        val tracker = RowTracker()
        val rows = listOf(rowA, rowB)
        // Driving east along row A at 2 m per sample.
        val s1 = tracker.updateAt(0.0, 0.0, rows)   // fresh lock, no heading yet
        assertEquals(false, s1.coverageEligible)
        val s2 = tracker.updateAt(2.0, 0.0, rows)   // aligned, but lock too young
        assertEquals(false, s2.coverageEligible)
        val s3 = tracker.updateAt(4.0, 0.0, rows)   // 3rd locked sample, aligned
        assertEquals(true, s3.coverageEligible)
        assertEquals(3, s3.lockSamples)
    }

    @Test
    fun `stationary jitter keeps the previous alignment verdict`() {
        val tracker = RowTracker()
        val rows = listOf(rowA)
        tracker.updateAt(0.0, 0.0, rows)
        tracker.updateAt(2.0, 0.0, rows)
        assertEquals(true, tracker.updateAt(4.0, 0.0, rows).coverageEligible)
        // Paused mid-row: a 0.2 m jitter step carries no heading — the
        // previous aligned verdict stands.
        assertEquals(true, tracker.updateAt(4.1, 0.2, rows).coverageEligible)
    }

    @Test
    fun `crossing a row end on the headland never becomes coverage eligible`() {
        // Drive north straight across row A near its end (x = 98), the way a
        // headland turn sweeps over the ends of adjacent rows. The row may
        // lock briefly, but perpendicular movement must not count as coverage.
        val tracker = RowTracker()
        val rows = listOf(rowA)
        var y = -2.0
        while (y <= 2.0) {
            assertEquals(false, tracker.updateAt(98.0, y, rows).coverageEligible)
            y += 1.0
        }
    }

    @Test
    fun `degenerate zero-length rows are ignored`() {
        val point = rowAt(0.0, "dot", lengthM = 0.1)
        val state = RowTracker().updateAt(0.0, 0.0, listOf(point))
        assertNull(state.row)
    }

    @Test
    fun `losing all candidates resets the lock`() {
        val tracker = RowTracker(switchSamples = 3)
        val rows = listOf(rowA, rowB)
        assertEquals("A", tracker.updateAt(10.0, 0.0, rows).row?.name)

        // Drive far away: lock drops.
        assertNull(tracker.updateAt(10.0, 100.0, rows).row)

        // Coming back near B locks immediately — no stale debounce state.
        assertEquals("B", tracker.updateAt(10.0, 3.1, rows).row?.name)
    }
}
