package com.example.gpstagger

import android.content.Context
import android.location.Location
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gpstagger.data.LocationDatabase
import com.example.gpstagger.data.RowEntity
import com.example.gpstagger.data.TaggedLocation
import com.example.gpstagger.databinding.ActivityDriveModeBinding
import com.example.gpstagger.gps.LocationSource
import com.example.gpstagger.rows.CalibrationOffset
import com.example.gpstagger.rows.CalibrationSession
import com.example.gpstagger.rows.RowTracker
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Continuous row-tracking screen. Shows which row the operator is currently
 * driving, progress along it, and lateral offset. Offers a calibration flow
 * that captures a known-good row traversal and saves the resulting
 * translational offset as a global GPS correction.
 */
class DriveModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDriveModeBinding
    private lateinit var locationSource: LocationSource
    private val tracker = RowTracker()
    private var rows: List<RowEntity> = emptyList()

    /** Latest raw fix used both for tag recording and for tracker input. */
    private var currentLocation: Location? = null

    private val tagButtons: List<Button> by lazy {
        listOf(
            binding.btnStart, binding.btnEnd, binding.btnObstacle,
            binding.btnWaypoint, binding.btnSample, binding.btnMark
        )
    }

    private var calibrationSession: CalibrationSession? = null

    /**
     * Throttle DB writes for coverage widening — every GPS sample would push
     * 1–2 Hz of writes per row, which is more than necessary. Writing once
     * every ~2 s of locked time still gives sub-percent precision for typical
     * orchard rows and keeps Room idle.
     */
    private var lastCoverageWriteAt: Long = 0L
    private val coverageWriteIntervalMs = 1_500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDriveModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Drive Mode"
        }

        locationSource = LocationSource(this).also { src ->
            src.setListener(object : LocationSource.Listener {
                override fun onLocation(location: Location) = onLocationUpdate(location)
                override fun onSourceStatus(label: String) {
                    binding.tvGpsStatus.text = label
                }
            })
        }

        binding.btnCalibrate.setOnClickListener { onCalibrateButton() }
        binding.btnClearOffset.setOnClickListener { confirmClearOffset() }

        TagLibrary.ensureInitialized(this)
        tagButtons.forEachIndexed { slot, button ->
            button.setOnClickListener { recordLocation(slot) }
        }

        // Observe rows; refresh tracker any time the row set changes.
        val db = LocationDatabase.getDatabase(this)
        db.rowDao().observeActive().observe(this) { latest ->
            rows = latest ?: emptyList()
            renderCalibrationStatus()
            renderCoverageCounter()
        }

        renderCalibrationStatus()
        renderCoverageCounter()
    }

    override fun onResume() {
        super.onResume()
        locationSource.start()
        applyLabels()
    }

    /** Reads current slot assignments and updates every button text and opacity. */
    private fun applyLabels() {
        tagButtons.forEachIndexed { slot, button ->
            val tag = TagLibrary.getTagForSlot(this, slot)
            button.text = tag?.name ?: "(empty)"
            button.alpha = if (tag != null) 1f else 0.4f
        }
    }

    override fun onPause() {
        super.onPause()
        locationSource.stop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_drive, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset_coverage -> { confirmResetCoverage(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmResetCoverage() {
        AlertDialog.Builder(this)
            .setTitle("Reset row coverage?")
            .setMessage("This clears the driven / pending state for every row so you can start a fresh task. Row definitions themselves are not affected.")
            .setPositiveButton("Reset") { _, _ ->
                lifecycleScope.launch {
                    LocationDatabase.getDatabase(this@DriveModeActivity)
                        .rowDao().resetAllCoverage()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderCoverageCounter() {
        val total = rows.size
        if (total == 0) {
            binding.tvCoverage.text = ""
            return
        }
        val covered = rows.count { it.coverageState() == RowEntity.Coverage.COVERED }
        binding.tvCoverage.text = "$covered / $total"
    }

    // ── Location loop ────────────────────────────────────────────────────────

    private fun onLocationUpdate(raw: Location) {
        currentLocation = raw

        // GPS quality indicator
        val accuracyColor = when {
            raw.accuracy <= 5f  -> getColor(R.color.gps_excellent)
            raw.accuracy <= 15f -> getColor(R.color.gps_good)
            else                -> getColor(R.color.gps_poor)
        }
        binding.tvAccuracy.text = String.format(Locale.US, "±%.0f m", raw.accuracy)
        binding.tvAccuracy.setTextColor(accuracyColor)
        binding.viewGpsIndicator.setBackgroundColor(accuracyColor)

        // Feed the (raw) sample to any in-flight calibration before applying
        // the offset — calibration measures uncorrected GPS against the row.
        calibrationSession?.addSample(raw.latitude, raw.longitude)

        // Apply the persisted offset (if enabled) to the position used for
        // row inference. This is what aligns the satellite-imagery-derived
        // rows with the GPS receiver's frame.
        val offset = CalibrationOffset.get(this)
        val corrected = CalibrationOffset.apply(raw.latitude, raw.longitude, offset)

        val state = tracker.update(corrected[0], corrected[1], rows)
        renderTracker(state)

        // Persist coverage progress for the locked row. We throttle these
        // writes since GPS comes in at 1–2 Hz and we don't need that
        // resolution; even ~1 write/2 s gives sub-percent coverage accuracy.
        val locked = state.row
        val now = System.currentTimeMillis()
        if (locked != null && now - lastCoverageWriteAt >= coverageWriteIntervalMs) {
            lastCoverageWriteAt = now
            val uuid = locked.uuid
            val t = state.alongFraction.coerceIn(0.0, 1.0)
            lifecycleScope.launch {
                LocationDatabase.getDatabase(this@DriveModeActivity)
                    .rowDao().widenCoverage(uuid, t)
            }
        }

        renderCalibrationStatus()
    }

    private fun renderTracker(state: RowTracker.State) {
        val row = state.row
        if (row == null) {
            binding.tvBlock.text = ""
            binding.tvRowName.text = if (rows.isEmpty()) "No rows defined" else "Off row"
            binding.tvProgress.text = "-- %"
            binding.progressBar.progress = 0
            binding.tvAlongDistance.text = ""
            binding.tvOffset.text = ""
            binding.tvCandidates.text = if (rows.isEmpty()) ""
                                        else "candidates: ${state.candidates}"
            return
        }
        binding.tvBlock.text = row.block.ifEmpty { "" }
        binding.tvRowName.text = row.name
        binding.tvProgress.text = "${state.progressPct} %"
        binding.progressBar.progress = state.progressPct
        binding.tvAlongDistance.text = String.format(
            Locale.US, "%.0f / %.0f m", state.alongDistanceM, state.rowLengthM
        )
        val side = when {
            state.signedPerpendicularM > 0 -> "L"
            state.signedPerpendicularM < 0 -> "R"
            else -> ""
        }
        binding.tvOffset.text = String.format(
            Locale.US, "offset: %.1f m %s", abs(state.signedPerpendicularM), side
        )
        binding.tvCandidates.text = "candidates: ${state.candidates}"
    }

    // ── Tag recording (same as MainActivity) ─────────────────────────────────

    private fun recordLocation(slot: Int) {
        val tag = TagLibrary.getTagForSlot(this, slot)
        if (tag == null) {
            Toast.makeText(this, "No tag assigned to this slot", Toast.LENGTH_SHORT).show()
            return
        }
        val loc = currentLocation
        if (loc == null) {
            Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
            return
        }
        vibrate()
        val db = LocationDatabase.getDatabase(this)
        lifecycleScope.launch {
            db.locationDao().insert(
                TaggedLocation(
                    latitude  = loc.latitude,
                    longitude = loc.longitude,
                    altitude  = loc.altitude,
                    accuracy  = loc.accuracy,
                    tag       = tag.name,
                    slot      = slot
                )
            )
            runOnUiThread {
                Toast.makeText(this@DriveModeActivity, "✓ ${tag.name} recorded", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ── Calibration ──────────────────────────────────────────────────────────

    private fun renderCalibrationStatus() {
        val offset = CalibrationOffset.get(this)
        val active = calibrationSession
        binding.tvCalibrationStatus.text = when {
            active != null -> "Calibrating: ${active.sampleCount} samples on “${active.rowName()}” — drive end-to-end, then tap Stop."
            offset.enabled && offset.magnitudeM > 0 ->
                String.format(Locale.US, "GPS offset active: %.2f m", offset.magnitudeM)
            else -> "No GPS offset applied."
        }
        binding.btnCalibrate.text = if (active != null) "Stop calibration" else "Calibrate"
    }

    private fun CalibrationSession.rowName(): String {
        // CalibrationSession doesn't expose the row directly; we read it back
        // from the tracker's last state instead. Simpler than threading it.
        return _calibrationRowName ?: "row"
    }

    /** Tracks which row we picked when calibration started, for UI labelling. */
    private var _calibrationRowName: String? = null

    private fun onCalibrateButton() {
        val active = calibrationSession
        if (active != null) {
            finishCalibration(active)
        } else {
            startCalibration()
        }
    }

    private fun startCalibration() {
        if (rows.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No rows")
                .setMessage("Define at least one row on the server first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val rowOptions = rows
        val labels = rowOptions.map {
            if (it.block.isEmpty()) it.name else "${it.block} — ${it.name}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Calibrate against which row?")
            .setItems(labels) { _, which ->
                val chosen = rowOptions[which]
                calibrationSession = CalibrationSession(chosen)
                _calibrationRowName = chosen.name
                renderCalibrationStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun finishCalibration(active: CalibrationSession) {
        val result = active.computeOffset()
        calibrationSession = null
        if (result == null) {
            AlertDialog.Builder(this)
                .setTitle("Not enough data")
                .setMessage("Need at least 5 GPS samples on the row to compute an offset.")
                .setPositiveButton("OK", null)
                .show()
            renderCalibrationStatus()
            return
        }

        val coverage = (result.coverageFraction * 100).toInt()
        val msg = String.format(
            Locale.US,
            "Captured %d samples covering ~%d%% of the row.\n\n" +
            "Mean perpendicular deviation: %.2f m\n" +
            "Proposed correction shift: %.2f m\n\n" +
            "Apply this as the GPS offset?",
            result.sampleCount, coverage,
            result.meanPerpendicularM, result.magnitudeM
        )
        AlertDialog.Builder(this)
            .setTitle("Calibration result")
            .setMessage(msg)
            .setPositiveButton("Apply") { _, _ ->
                CalibrationOffset.save(
                    this,
                    eastM = result.offsetEastM,
                    northM = result.offsetNorthM,
                    enabled = true
                )
                renderCalibrationStatus()
            }
            .setNegativeButton("Discard", null)
            .show()
    }

    private fun confirmClearOffset() {
        AlertDialog.Builder(this)
            .setTitle("Clear GPS offset?")
            .setMessage("Future positions will be used unmodified.")
            .setPositiveButton("Clear") { _, _ ->
                CalibrationOffset.clear(this)
                renderCalibrationStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
