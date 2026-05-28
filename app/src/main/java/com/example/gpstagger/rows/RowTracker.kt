package com.example.gpstagger.rows

import com.example.gpstagger.data.RowEntity
import kotlin.math.abs

/**
 * Continuously decides which orchard row a position belongs to, given a set
 * of [RowEntity] definitions.
 *
 * Decision rule, per [update] call:
 *
 *  1. Project the input point onto every candidate row's line segment.
 *     Reject any row whose along-row fraction is far outside [0,1] (we use
 *     [endpointSlackM] to allow the tractor to start slightly before the
 *     defined start or coast slightly past the defined end).
 *  2. Score each remaining row by its perpendicular distance.
 *  3. If the closest row equals the currently-locked row, keep it.
 *  4. Otherwise the alternative only wins if:
 *     - perpendicular_distance(alt) <= perpendicular_distance(current) - [hysteresisM], AND
 *     - the alternative has been the leader for at least [switchSamples] consecutive samples.
 *
 * The default hysteresis is half a typical row's width. When a row's
 * `width_m` is set we use that instead, falling back to [defaultHysteresisM]
 * for rows that didn't have a width specified by the operator.
 */
class RowTracker(
    private val defaultHysteresisM: Double = 1.5,
    private val endpointSlackM: Double = 5.0,
    private val switchSamples: Int = 3,
    private val maxPerpendicularM: Double = 50.0
) {

    /** Snapshot describing the tracker's current opinion. */
    data class State(
        val row: RowEntity?,
        val alongFraction: Double,     // 0..1 along the locked row
        val alongDistanceM: Double,    // metres from the row's start point
        val rowLengthM: Double,        // total row length
        val signedPerpendicularM: Double,  // +ve = left of start→end
        val candidates: Int            // rows that passed the slack filter this update
    ) {
        val progressPct: Int get() = (alongFraction.coerceIn(0.0, 1.0) * 100).toInt()
    }

    private var lockedUuid: String? = null
    private var contenderUuid: String? = null
    private var contenderStreak: Int = 0

    fun reset() {
        lockedUuid = null
        contenderUuid = null
        contenderStreak = 0
    }

    /**
     * Recompute the locked row given a fresh position. Pass the active list
     * of row definitions on every call so the tracker stays correct when rows
     * are added or removed.
     */
    fun update(latitude: Double, longitude: Double, rows: List<RowEntity>): State {
        if (rows.isEmpty()) {
            reset()
            return State(null, 0.0, 0.0, 0.0, 0.0, 0)
        }

        // All projections happen in a local metric plane anchored at the
        // first row's start point. The choice of anchor is arbitrary — the
        // relative distances we care about are anchor-independent.
        val anchor = rows.first()
        val refLat = anchor.startLat
        val refLng = anchor.startLng
        val (px, py) = Geo.toLocalMeters(latitude, longitude, refLat, refLng)

        data class Candidate(val row: RowEntity, val proj: Geo.Projection, val lengthM: Double)
        val candidates = ArrayList<Candidate>(rows.size)

        for (row in rows) {
            val (ax, ay) = Geo.toLocalMeters(row.startLat, row.startLng, refLat, refLng)
            val (bx, by) = Geo.toLocalMeters(row.endLat,   row.endLng,   refLat, refLng)
            val length = Geo.lengthM(ax, ay, bx, by)
            if (length < 0.5) continue  // degenerate
            val proj = Geo.projectOntoSegment(px, py, ax, ay, bx, by, clamp = true)

            // Reject if too far away from any reasonable row.
            if (proj.perpendicularM > maxPerpendicularM) continue

            // Reject if the unclamped projection is far outside the segment —
            // we're not even near this row in the along-track direction.
            val slackFraction = endpointSlackM / length
            if (proj.rawT < -slackFraction || proj.rawT > 1.0 + slackFraction) continue

            candidates.add(Candidate(row, proj, length))
        }

        if (candidates.isEmpty()) {
            reset()
            return State(null, 0.0, 0.0, 0.0, 0.0, 0)
        }

        // Sort by perpendicular distance ascending; closest is leader.
        candidates.sortBy { it.proj.perpendicularM }
        val leader = candidates.first()
        val currentLocked = lockedUuid?.let { uuid -> candidates.firstOrNull { it.row.uuid == uuid } }

        val chosen: Candidate = when {
            currentLocked == null -> {
                // No prior lock — accept the leader and reset debounce state.
                lockedUuid = leader.row.uuid
                contenderUuid = null
                contenderStreak = 0
                leader
            }
            leader.row.uuid == currentLocked.row.uuid -> {
                // Leader is still our locked row.
                contenderUuid = null
                contenderStreak = 0
                currentLocked
            }
            else -> {
                // A different row is closer. Apply hysteresis margin + N-sample debounce.
                val margin = effectiveHysteresis(leader.row, currentLocked.row)
                val delta = currentLocked.proj.perpendicularM - leader.proj.perpendicularM
                if (delta < margin) {
                    // Not convincing enough — stick with current.
                    contenderUuid = null
                    contenderStreak = 0
                    currentLocked
                } else {
                    if (contenderUuid == leader.row.uuid) {
                        contenderStreak += 1
                    } else {
                        contenderUuid = leader.row.uuid
                        contenderStreak = 1
                    }
                    if (contenderStreak >= switchSamples) {
                        lockedUuid = leader.row.uuid
                        contenderUuid = null
                        contenderStreak = 0
                        leader
                    } else {
                        currentLocked
                    }
                }
            }
        }

        val alongDistance = chosen.proj.t * chosen.lengthM
        return State(
            row = chosen.row,
            alongFraction = chosen.proj.t,
            alongDistanceM = alongDistance,
            rowLengthM = chosen.lengthM,
            signedPerpendicularM = chosen.proj.signedPerpM,
            candidates = candidates.size
        )
    }

    /** Margin between adjacent rows. Use the wider of either row's width if set. */
    private fun effectiveHysteresis(a: RowEntity, b: RowEntity): Double {
        val widths = listOf(a.widthM, b.widthM).filter { it > 0 }
        return if (widths.isEmpty()) defaultHysteresisM else widths.max() / 2.0
    }
}
