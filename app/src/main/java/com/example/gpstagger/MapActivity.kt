package com.example.gpstagger

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gpstagger.data.LocationDatabase
import com.example.gpstagger.databinding.ActivityMapBinding
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class MapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapBinding
    private lateinit var db: LocationDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Saved Locations"
        }

        db = LocationDatabase.getDatabase(this)
        setupMap()
        loadLocations()
        setupLegendToggle()

        binding.btnExport.setOnClickListener { exportCsv() }
        binding.btnClearAll.setOnClickListener { confirmClearAll() }
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(3.0)
        }
    }

    private fun loadLocations() {
        db.locationDao().getAllLocations().observe(this) { locations ->
            binding.mapView.overlays.clear()

            if (locations.isEmpty()) {
                binding.tvNoPoints.visibility = View.VISIBLE
                binding.mapView.invalidate()
                return@observe
            }
            binding.tvNoPoints.visibility = View.GONE

            var sumLat = 0.0
            var sumLon = 0.0

            locations.forEach { loc ->
                val marker = Marker(binding.mapView).apply {
                    position = GeoPoint(loc.latitude, loc.longitude)
                    title    = "${loc.tag}  —  ${loc.formattedTime()}"
                    snippet  = "Lat: ${loc.latitude}\nLon: ${loc.longitude}\nAccuracy: ±${loc.accuracy.toInt()} m"
                    icon     = createCircleMarker(markerColor(loc.slot))
                }
                binding.mapView.overlays.add(marker)
                sumLat += loc.latitude
                sumLon += loc.longitude
            }

            val centerLat = sumLat / locations.size
            val centerLon = sumLon / locations.size
            binding.mapView.controller.apply {
                setZoom(15.0)
                animateTo(GeoPoint(centerLat, centerLon))
            }
            binding.mapView.invalidate()
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

    /** Rebuilds the legend rows from current label preferences. Call from onResume. */
    private fun populateLegend() {
        val dp     = resources.displayMetrics.density
        val labels = TagPreferences.getLabels(this)
        binding.legendItems.removeAllViews()

        labels.forEachIndexed { slot, label ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }

            // Colored circle dot
            val dotSize = (12 * dp).toInt()
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).also {
                    it.marginEnd = (8 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(TagPreferences.slotColor(slot))
                }
            }

            val text = TextView(this).apply {
                text     = label
                textSize = 13f
                setTextColor(Color.WHITE)
            }

            row.addView(dot)
            row.addView(text)
            binding.legendItems.addView(row)
        }
    }

    /** Returns the display color for a location, using slot index when available. */
    private fun markerColor(slot: Int): Int = TagPreferences.slotColor(slot)

    /** Creates a filled circle marker icon in the given color. */
    private fun createCircleMarker(color: Int): Drawable {
        val dp = resources.displayMetrics.density
        val size = (36 * dp).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Filled circle
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        // White border
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * dp
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - paint.strokeWidth, paint)

        return BitmapDrawable(resources, bitmap)
    }

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() { super.onResume(); binding.mapView.onResume(); populateLegend() }
    override fun onPause()  { super.onPause();  binding.mapView.onPause()  }
}
