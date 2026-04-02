package com.example.gpstagger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gpstagger.data.LocationDatabase
import com.example.gpstagger.data.TaggedLocation
import com.example.gpstagger.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: Location? = null
    private lateinit var db: LocationDatabase

    /** Ordered list matching the 6 tag buttons by slot index. */
    private val tagButtons: List<Button> by lazy {
        listOf(
            binding.btnStart, binding.btnEnd, binding.btnObstacle,
            binding.btnWaypoint, binding.btnSample, binding.btnMark
        )
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        ) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        TagLibrary.ensureInitialized(this)
        db = LocationDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupLocationCallback()

        tagButtons.forEachIndexed { slot, button ->
            button.setOnClickListener { recordLocation(slot) }
        }

        binding.btnViewMap.setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }

        db.locationDao().getCount().observe(this) { count ->
            binding.tvPointCount.text = "Saved: $count pts"
        }
    }

    override fun onResume() {
        super.onResume()
        applyLabels()
        checkLocationPermission()
    }

    /** Reads current slot assignments and updates every button text and opacity. */
    private fun applyLabels() {
        tagButtons.forEachIndexed { slot, button ->
            val tag = TagLibrary.getTagForSlot(this, slot)
            button.text = tag?.name ?: "(empty)"
            button.alpha = if (tag != null) 1f else 0.4f
        }
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_manage_buttons -> {
                startActivity(Intent(this, ManageTagsActivity::class.java))
                return true
            }
            R.id.action_sync -> {
                performSync()
                return true
            }
            R.id.action_sync_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_check_update -> {
                checkForUpdate()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun performSync() {
        SyncConfig.load(this)
        if (!SyncConfig.isConfigured()) {
            Toast.makeText(this, "Configure sync server first (⋮ → Sync Settings)", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val result = SyncManager.sync(this@MainActivity)) {
                is SyncManager.Result.Success -> {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            "Sync complete: ${result.pushed} pushed, ${result.pulled} pulled",
                            Toast.LENGTH_SHORT).show()
                        applyLabels()
                    }
                }
                is SyncManager.Result.Error -> {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Sync failed: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val release = AppUpdater.checkForUpdate()
            if (release == null) {
                Toast.makeText(this@MainActivity, "Could not reach update server", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val localVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
            if (AppUpdater.isNewer(release.tagName, localVersion)) {
                AppUpdater.promptAndInstall(this@MainActivity, release)
            } else {
                Toast.makeText(this@MainActivity, "You're on the latest version", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { updateLocationDisplay(it) }
            }
        }
    }

    private fun updateLocationDisplay(location: Location) {
        currentLocation = location
        binding.tvGpsStatus.text = "GPS: Active"
        binding.tvLatitude.text  = String.format(Locale.US, "%.6f°", location.latitude)
        binding.tvLongitude.text = String.format(Locale.US, "%.6f°", location.longitude)

        val accuracyColor = when {
            location.accuracy <= 5f  -> getColor(R.color.gps_excellent)
            location.accuracy <= 15f -> getColor(R.color.gps_good)
            else                     -> getColor(R.color.gps_poor)
        }
        binding.tvAccuracy.text = String.format(Locale.US, "±%.0f m", location.accuracy)
        binding.tvAccuracy.setTextColor(accuracyColor)
        binding.viewGpsIndicator.setBackgroundColor(accuracyColor)
    }

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
                Toast.makeText(this@MainActivity, "✓ ${tag.name} recorded", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        } else {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
            binding.tvGpsStatus.text = "GPS: Acquiring…"
        }
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
