package com.example.gpstagger

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.gpstagger.databinding.ActivityOfflineMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.GeoPoint
import java.io.File

class OfflineMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflineMapBinding
    private lateinit var cacheManager: CacheManager

    private var zoomMin = 13
    private var zoomMax = 17
    private var totalTiles = 0
    private var isDownloading = false

    private val estimateHandler = Handler(Looper.getMainLooper())
    private val estimateRunnable = Runnable { refreshEstimate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmdroid()

        binding = ActivityOfflineMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Download Offline Map"
        }

        setupMap()
        setupControls()
        updateCacheSize()
    }

    private fun configureOsmdroid() {
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath  = File(filesDir, "osmdroid")
            osmdroidTileCache = File(filesDir, "osmdroid/tiles")
        }
    }

    private fun setupMap() {
        // Restore the position passed from MapActivity so the user starts
        // looking at the same area rather than navigating from scratch.
        val lat  = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon  = intent.getDoubleExtra(EXTRA_LON, 0.0)
        val zoom = intent.getDoubleExtra(EXTRA_ZOOM, 14.0)

        binding.mapView.apply {
            setTileSource(TileSources.saved(this@OfflineMapActivity))
            setMultiTouchControls(true)
            controller.setZoom(zoom)
            if (lat != 0.0 || lon != 0.0) controller.setCenter(GeoPoint(lat, lon))

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent): Boolean { scheduleEstimate(); return false }
                override fun onZoom(event: ZoomEvent): Boolean     { scheduleEstimate(); return false }
            })
        }

        cacheManager = CacheManager(binding.mapView)

        // Keep the toggle button state in sync with the saved preference
        if (TileSources.isEsriSelected(this)) {
            binding.toggleTileSource.check(R.id.btnTileEsri)
        } else {
            binding.toggleTileSource.check(R.id.btnTileOsm)
        }

        binding.toggleTileSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val esri = checkedId == R.id.btnTileEsri
            TileSources.saveSelection(this, esri)
            binding.mapView.setTileSource(if (esri) TileSources.ESRI_SATELLITE else TileSources.OSM)
            binding.mapView.invalidate()
            // Recreate CacheManager so it targets the newly selected tile source
            cacheManager = CacheManager(binding.mapView)
            scheduleEstimate()
        }

        scheduleEstimate()
    }

    // ── Estimate ──────────────────────────────────────────────────────────────

    private fun scheduleEstimate() {
        estimateHandler.removeCallbacks(estimateRunnable)
        estimateHandler.postDelayed(estimateRunnable, 600)
    }

    private fun refreshEstimate() {
        val bb = binding.mapView.boundingBox ?: return
        val count = cacheManager.possibleTilesInArea(bb, zoomMin, zoomMax)
        totalTiles = count
        val mb = count * 15_000L / 1_000_000.0
        binding.tvEstimate.text = "~$count tiles  (~${"%.1f".format(mb)} MB)"

        val tooLarge = count > 5_000
        binding.tvEstimate.setTextColor(
            if (tooLarge) getColor(android.R.color.holo_orange_light) else 0xFFCCCCCC.toInt()
        )
        binding.tvWarning.visibility = if (tooLarge) View.VISIBLE else View.GONE
    }

    // ── Zoom controls ─────────────────────────────────────────────────────────

    private fun setupControls() {
        updateZoomDisplay()

        binding.btnZoomMinMinus.setOnClickListener {
            if (zoomMin > 8)      { zoomMin--;             updateZoomDisplay() }
        }
        binding.btnZoomMinPlus.setOnClickListener {
            if (zoomMin < zoomMax) { zoomMin++;             updateZoomDisplay() }
        }
        binding.btnZoomMaxMinus.setOnClickListener {
            if (zoomMax > zoomMin) { zoomMax--;             updateZoomDisplay() }
        }
        binding.btnZoomMaxPlus.setOnClickListener {
            if (zoomMax < 18)     { zoomMax++;             updateZoomDisplay() }
        }

        binding.btnDownload.setOnClickListener   { confirmDownload()   }
        binding.btnClearCache.setOnClickListener { confirmClearCache() }
    }

    private fun updateZoomDisplay() {
        binding.tvZoomMin.text = zoomMin.toString()
        binding.tvZoomMax.text = zoomMax.toString()
        scheduleEstimate()
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun confirmDownload() {
        if (totalTiles == 0) { refreshEstimate() }
        if (totalTiles > 5_000) {
            AlertDialog.Builder(this)
                .setTitle("Large Download")
                .setMessage(
                    "This will download ~$totalTiles tiles " +
                    "(~${"%.0f".format(totalTiles * 15_000.0 / 1_000_000)} MB). Continue?"
                )
                .setPositiveButton("Download") { _, _ -> startDownload() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            startDownload()
        }
    }

    private fun startDownload() {
        if (isDownloading) return
        isDownloading = true

        val bb = binding.mapView.boundingBox

        binding.progressBar.max      = totalTiles.coerceAtLeast(1)
        binding.progressBar.progress = 0
        binding.progressBar.visibility      = View.VISIBLE
        binding.tvDownloadStatus.visibility = View.VISIBLE
        binding.tvDownloadStatus.text       = "Starting download…"
        binding.btnDownload.isEnabled       = false

        cacheManager.downloadAreaAsync(
            this, bb, zoomMin, zoomMax,
            object : CacheManager.CacheManagerCallback {

                override fun setPossibleTilesInArea(total: Int) = runOnUiThread {
                    binding.progressBar.max = total.coerceAtLeast(1)
                    binding.tvDownloadStatus.text = "Preparing $total tiles…"
                }

                override fun downloadStarted() = runOnUiThread {
                    binding.tvDownloadStatus.text = "Downloading…"
                }

                override fun updateProgress(
                    progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int
                ) = runOnUiThread {
                    binding.progressBar.progress  = progress
                    binding.tvDownloadStatus.text =
                        "Zoom $currentZoomLevel — $progress / ${binding.progressBar.max} tiles"
                }

                override fun onTaskComplete() = runOnUiThread {
                    isDownloading = false
                    binding.progressBar.visibility = View.GONE
                    binding.tvDownloadStatus.text  = "Download complete ✓"
                    binding.btnDownload.isEnabled  = true
                    updateCacheSize()
                    Toast.makeText(
                        this@OfflineMapActivity, "Map saved for offline use", Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onTaskFailed(errors: Int) = runOnUiThread {
                    isDownloading = false
                    binding.progressBar.visibility = View.GONE
                    binding.tvDownloadStatus.text  = "Finished with $errors error(s)"
                    binding.btnDownload.isEnabled  = true
                    updateCacheSize()
                }
            }
        )
    }

    // ── Cache management ──────────────────────────────────────────────────────

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear Cached Tiles?")
            .setMessage("All downloaded offline map tiles will be deleted.")
            .setPositiveButton("Clear") { _, _ -> clearCache() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            File(filesDir, "osmdroid").deleteRecursively()
            withContext(Dispatchers.Main) {
                updateCacheSize()
                binding.tvDownloadStatus.visibility = View.GONE
                binding.progressBar.visibility      = View.GONE
                Toast.makeText(this@OfflineMapActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCacheSize() {
        val dir   = File(filesDir, "osmdroid")
        val bytes = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val mb    = bytes / 1_000_000.0
        binding.btnClearCache.text = "Clear Cache (${"%.1f".format(mb)} MB)"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause()  {
        super.onPause()
        binding.mapView.onPause()
        estimateHandler.removeCallbacks(estimateRunnable)
    }

    companion object {
        const val EXTRA_LAT  = "center_lat"
        const val EXTRA_LON  = "center_lon"
        const val EXTRA_ZOOM = "zoom"

        fun intent(from: android.content.Context, lat: Double, lon: Double, zoom: Double) =
            Intent(from, OfflineMapActivity::class.java).apply {
                putExtra(EXTRA_LAT,  lat)
                putExtra(EXTRA_LON,  lon)
                putExtra(EXTRA_ZOOM, zoom)
            }
    }
}
