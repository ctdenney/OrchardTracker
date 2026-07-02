package com.example.gpstagger

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gpstagger.data.LocationDatabase
import com.example.gpstagger.data.RowEntity
import com.example.gpstagger.data.TaggedLocation
import com.example.gpstagger.databinding.ActivityMapBinding
import com.example.gpstagger.gps.LocationSourceMyLocationProvider
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var db: LocationDatabase
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var areaSelectionOverlay: AreaSelectionOverlay

    /** Full unfiltered list from the database. */
    private var allLocations: List<TaggedLocation> = emptyList()

    /** Tag names to show; empty set means "show all". */
    private var activeTagFilter: Set<String> = emptySet()

    /** Tracks which TaggedLocation each live marker represents. */
    private val markerLocationMap = mutableMapOf<Marker, TaggedLocation>()

    private var inSelectionMode = false

    /** Row polylines currently rendered on the map, keyed by row UUID. */
    private val rowPolylines = mutableMapOf<String, Polyline>()
    private var rowsVisible: Boolean = true
    private var lastRows: List<RowEntity> = emptyList()

    /**
     * Whether we've already done the one-time auto-centre on the saved
     * point centroid. Without this guard, every DB change (e.g. deleting a
     * point) re-fires the LiveData observer and snaps the viewport back to
     * the centroid — clobbering whatever the user was looking at.
     */
    private var hasAutoCentered: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue    = packageName
            osmdroidBasePath  = java.io.File(filesDir, "osmdroid")
            osmdroidTileCache = java.io.File(filesDir, "osmdroid/tiles")
        }

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Saved Locations"
        }

        db = LocationDatabase.getDatabase(this)
        setupMap()
        loadLocations()
        loadRows()
        setupLegendToggle()

        binding.btnExport.setOnClickListener { exportCsv() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }
        binding.btnSelectArea.setOnClickListener {
            if (inSelectionMode) exitSelectionMode() else enterSelectionMode()
        }
        binding.fabMyLocation.setOnClickListener { centerOnMyLocation() }
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSources.saved(this@MapActivity))
            setMultiTouchControls(true)
            // Allow the user to over-zoom past the tile source's native max;
            // osmdroid's MapTileApproximater fills missing tiles by scaling
            // their parents, so the view stays usable for placing or sighting
            // points at higher detail than the imagery technically provides.
            maxZoomLevel = TileSources.MAX_ZOOM.toDouble()
            minZoomLevel = 2.0

            // Restore the last viewport if we have one — otherwise start at
            // a sensible "world" zoom and let loadLocations centre on points.
            val saved = MapViewportPrefs.get(this@MapActivity)
            if (saved != null) {
                controller.setZoom(saved.zoom)
                controller.setCenter(GeoPoint(saved.lat, saved.lng))
                hasAutoCentered = true
            } else {
                controller.setZoom(3.0)
            }
        }

        myLocationOverlay = MyLocationNewOverlay(
            LocationSourceMyLocationProvider(this), binding.mapView
        )
        myLocationOverlay.enableMyLocation()

        areaSelectionOverlay = AreaSelectionOverlay(binding.mapView) { bbox ->
            val inArea = allLocations.filter { loc ->
                bbox.contains(loc.latitude, loc.longitude)
            }
            exitSelectionMode()
            if (inArea.isEmpty()) {
                Toast.makeText(this, "No points in selected area", Toast.LENGTH_SHORT).show()
                return@AreaSelectionOverlay
            }
            val count = inArea.size
            AlertDialog.Builder(this)
                .setTitle("Delete $count point${if (count != 1) "s" else ""}?")
                .setMessage("Permanently delete $count selected point${if (count != 1) "s" else ""}? This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch { db.locationDao().deleteByIds(inArea.map { it.id }) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.mapView.overlays.add(myLocationOverlay)
        binding.mapView.overlays.add(areaSelectionOverlay)
    }

    private fun applyTileSource() {
        binding.mapView.setTileSource(TileSources.saved(this))
        binding.mapView.invalidate()
        invalidateOptionsMenu()
    }

    // ── Location loading & marker rendering ───────────────────────────────────

    private fun loadLocations() {
        db.locationDao().getAllLocations().observe(this) { locations ->
            allLocations = locations

            if (locations.isEmpty()) {
                binding.tvNoPoints.visibility = View.VISIBLE
                refreshMarkers()
                return@observe
            }
            binding.tvNoPoints.visibility = View.GONE
            refreshMarkers()

            // Auto-centre once per activity-launch, only when we don't already
            // have a saved viewport. Any subsequent DB change (delete, sync,
            // tag rename) leaves the user's current pan/zoom alone.
            if (!hasAutoCentered) {
                hasAutoCentered = true
                val centerLat = locations.sumOf { it.latitude } / locations.size
                val centerLon = locations.sumOf { it.longitude } / locations.size
                binding.mapView.controller.apply {
                    setZoom(18.0)
                    animateTo(GeoPoint(centerLat, centerLon))
                }
            }
        }
    }

    /** Rebuilds map markers from [allLocations], applying [activeTagFilter]. */
    private fun refreshMarkers() {
        markerLocationMap.clear()
        binding.mapView.overlays.clear()
        binding.mapView.overlays.add(myLocationOverlay)
        binding.mapView.overlays.add(areaSelectionOverlay)
        // Rows live underneath markers so they don't intercept marker clicks.
        rowPolylines.values.forEach { binding.mapView.overlays.add(it) }

        val filtered = if (activeTagFilter.isEmpty()) allLocations
                       else allLocations.filter { it.tag in activeTagFilter }

        filtered.forEach { loc ->
            val marker = Marker(binding.mapView).apply {
                position = GeoPoint(loc.latitude, loc.longitude)
                title    = "${loc.tag}  —  ${loc.formattedTime()}"
                snippet  = "Lat: ${loc.latitude}\nLon: ${loc.longitude}\nAccuracy: ±${loc.accuracy.toInt()} m"
                icon     = createCircleMarker(TagLibrary.colorForTag(this@MapActivity, loc.tag))
                setOnMarkerClickListener { m, _ ->
                    showPointDeleteDialog(markerLocationMap[m] ?: return@setOnMarkerClickListener true)
                    true
                }
            }
            markerLocationMap[marker] = loc
            binding.mapView.overlays.add(marker)
        }

        binding.mapView.invalidate()
    }

    // ── Row rendering & coverage ──────────────────────────────────────────────

    private fun loadRows() {
        db.rowDao().observeActive().observe(this) { latest ->
            lastRows = latest ?: emptyList()
            renderRows()
        }
    }

    /**
     * Rebuild the row overlay. Color encodes coverage state — green for
     * driven, amber for partial, yellow for pending. Rows are drawn under
     * point markers but over the base tile layer.
     */
    private fun renderRows() {
        // Remove any polylines for rows that no longer exist.
        val live = lastRows.associateBy { it.uuid }
        val gone = rowPolylines.keys - live.keys
        gone.forEach { uuid ->
            rowPolylines.remove(uuid)?.let { binding.mapView.overlays.remove(it) }
        }

        if (!rowsVisible) {
            rowPolylines.values.forEach { binding.mapView.overlays.remove(it) }
            rowPolylines.clear()
            binding.mapView.invalidate()
            return
        }

        val dp = resources.displayMetrics.density
        lastRows.forEach { row ->
            val color = when (row.coverageState()) {
                RowEntity.Coverage.COVERED -> Color.parseColor("#66BB6A")
                RowEntity.Coverage.PARTIAL -> Color.parseColor("#FFA726")
                RowEntity.Coverage.NONE    -> Color.parseColor("#FFC107")
            }
            val existing = rowPolylines[row.uuid]
            val polyline = existing ?: Polyline().also {
                rowPolylines[row.uuid] = it
                binding.mapView.overlays.add(it)
            }
            polyline.setPoints(listOf(
                GeoPoint(row.startLat, row.startLng),
                GeoPoint(row.endLat,   row.endLng)
            ))
            polyline.outlinePaint.apply {
                this.color = color
                strokeWidth = 4f * dp
                isAntiAlias = true
            }
            polyline.title = row.name
        }
        binding.mapView.invalidate()
    }

    private fun confirmResetCoverage() {
        AlertDialog.Builder(this)
            .setTitle("Reset row coverage?")
            .setMessage("Clears the driven / pending state for every row so you can start a fresh task. Row definitions themselves are not affected.")
            .setPositiveButton("Reset") { _, _ ->
                lifecycleScope.launch { db.rowDao().resetAllCoverage() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Individual point deletion ─────────────────────────────────────────────

    private fun showPointDeleteDialog(loc: TaggedLocation) {
        AlertDialog.Builder(this)
            .setTitle(loc.tag)
            .setMessage(
                "${loc.formattedTime()}\n" +
                "Lat: ${loc.latitude}\nLon: ${loc.longitude}\n" +
                "Accuracy: ±${loc.accuracy.toInt()} m"
            )
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { db.locationDao().deleteById(loc.id) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Area selection mode ───────────────────────────────────────────────────

    private fun enterSelectionMode() {
        inSelectionMode = true
        areaSelectionOverlay.isActive = true
        binding.mapView.setMultiTouchControls(false)
        binding.btnSelectArea.apply {
            text = "Cancel"
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E65100"))
        }
        Toast.makeText(this, "Drag to draw a selection rectangle", Toast.LENGTH_SHORT).show()
    }

    private fun exitSelectionMode() {
        inSelectionMode = false
        areaSelectionOverlay.isActive = false
        binding.mapView.setMultiTouchControls(true)
        binding.btnSelectArea.apply {
            text = "Select Area"
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#37474F"))
        }
    }

    // ── Tag filter ────────────────────────────────────────────────────────────

    private fun showFilterDialog() {
        val presentTags = allLocations.map { it.tag }.distinct().sorted()
        if (presentTags.isEmpty()) {
            Toast.makeText(this, "No points to filter", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = presentTags.toTypedArray()

        val checked = BooleanArray(presentTags.size) { i ->
            activeTagFilter.isEmpty() || presentTags[i] in activeTagFilter
        }

        AlertDialog.Builder(this)
            .setTitle("Filter by Tag")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                val selected = presentTags.filterIndexed { i, _ -> checked[i] }.toSet()
                activeTagFilter = if (selected.size == presentTags.size) emptySet() else selected
                refreshMarkers()
                populateLegend()
                invalidateOptionsMenu()
            }
            .setNeutralButton("Show All") { _, _ ->
                activeTagFilter = emptySet()
                refreshMarkers()
                populateLegend()
                invalidateOptionsMenu()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── My Location ───────────────────────────────────────────────────────────

    private fun centerOnMyLocation() {
        val myLoc = myLocationOverlay.myLocation
        if (myLoc != null) {
            binding.mapView.controller.animateTo(myLoc)
        } else {
            Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Legend ────────────────────────────────────────────────────────────────

    private fun setupLegendToggle() {
        binding.legendHeader.setOnClickListener {
            val expanding = binding.legendItems.visibility == View.GONE
            binding.legendItems.visibility = if (expanding) View.VISIBLE else View.GONE
            binding.tvLegendArrow.text     = if (expanding) "▾" else "▸"
        }
    }

    private fun populateLegend() {
        val dp = resources.displayMetrics.density
        binding.legendItems.removeAllViews()

        // Build legend from tags actually present in the data, keyed by tag
        // name, coloured the same way as the markers.
        val tagNames = allLocations.map { it.tag }.distinct().sorted()

        for (tag in tagNames) {
            if (activeTagFilter.isNotEmpty() && tag !in activeTagFilter) continue

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }

            val dotSize = (12 * dp).toInt()
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).also {
                    it.marginEnd = (8 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(TagLibrary.colorForTag(this@MapActivity, tag))
                }
            }

            val text = TextView(this).apply {
                text     = tag
                textSize = 13f
                setTextColor(Color.WHITE)
            }

            row.addView(dot)
            row.addView(text)
            binding.legendItems.addView(row)
        }
    }

    // ── Marker helpers ────────────────────────────────────────────────────────

    private fun createCircleMarker(color: Int): Drawable {
        val dp = resources.displayMetrics.density
        val size = (18 * dp).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * dp
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - paint.strokeWidth, paint)

        return BitmapDrawable(resources, bitmap)
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun exportCsv() {
        lifecycleScope.launch {
            val locations = db.locationDao().getAllLocationsList()
            if (locations.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this@MapActivity, "No locations to export", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val csv = buildString {
                appendLine("id,tag,latitude,longitude,altitude_m,accuracy_m,timestamp")
                locations.forEach { appendLine(it.toCsvRow()) }
            }

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type   = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "GPS Tagger Export")
                putExtra(Intent.EXTRA_TEXT, csv)
            }
            runOnUiThread {
                startActivity(Intent.createChooser(sendIntent, "Export CSV via…"))
            }
        }
    }

    // ── Delete all ────────────────────────────────────────────────────────────

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Points?")
            .setMessage("This will permanently delete all saved GPS points. This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                lifecycleScope.launch { db.locationDao().deleteAll() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_map, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val esri = TileSources.isEsriSelected(this)
        menu.findItem(R.id.action_toggle_satellite)?.title =
            if (esri) "Switch to Street Map" else "Switch to Satellite"
        menu.findItem(R.id.action_filter_tags)?.title =
            if (activeTagFilter.isEmpty()) "Filter Tags" else "Filter Tags ✓"
        menu.findItem(R.id.action_toggle_rows)?.title =
            if (rowsVisible) "Hide Rows" else "Show Rows"
        menu.findItem(R.id.action_reset_coverage)?.isVisible = lastRows.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_filter_tags -> { showFilterDialog(); true }
            R.id.action_toggle_satellite -> {
                TileSources.saveSelection(this, !TileSources.isEsriSelected(this))
                applyTileSource()
                true
            }
            R.id.action_download_offline -> {
                val center = binding.mapView.mapCenter
                startActivity(
                    OfflineMapActivity.intent(
                        this,
                        center.latitude,
                        center.longitude,
                        binding.mapView.zoomLevelDouble
                    )
                )
                true
            }
            R.id.action_toggle_rows -> {
                rowsVisible = !rowsVisible
                renderRows()
                invalidateOptionsMenu()
                true
            }
            R.id.action_reset_coverage -> { confirmResetCoverage(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        myLocationOverlay.enableMyLocation()
        applyTileSource()
        populateLegend()
    }

    override fun onPause() {
        super.onPause()
        val center = binding.mapView.mapCenter
        MapViewportPrefs.save(
            this,
            lat  = center.latitude,
            lng  = center.longitude,
            zoom = binding.mapView.zoomLevelDouble
        )
        binding.mapView.onPause()
        myLocationOverlay.disableMyLocation()
    }
}
