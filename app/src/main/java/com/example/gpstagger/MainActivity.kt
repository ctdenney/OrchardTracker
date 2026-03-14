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

    /** Reads current labels from SharedPreferences and updates every button text. */
    private fun applyLabels() {
        val labels = TagPreferences.getLabels(this)
        tagButtons.forEachIndexed { i, button -> button.text = labels[i] }
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_edit_labels) {
            startActivity(Intent(this, EditLabelsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
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
        val loc = currentLocation
        if (loc == null) {
            Toast.makeText(this, "Waiting for GPS fix…", Toast.LENGTH_SHORT).show()
            return
        }
        val label = TagPreferences.getLabels(this)[slot]
        vibrate()
        lifecycleScope.launch {
            db.locationDao().insert(
                TaggedLocation(
                    latitude  = loc.latitude,
                    longitude = loc.longitude,
                    altitude  = loc.altitude,
                    accuracy  = loc.accuracy,
                    tag       = label,
                    slot      = slot
                )
            )
            runOnUiThread {
                Toast.makeText(this@MainActivity, "✓ $label recorded", Toast.LENGTH_SHORT).show()
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
