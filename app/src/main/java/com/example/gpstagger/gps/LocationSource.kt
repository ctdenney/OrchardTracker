package com.example.gpstagger.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Unified location stream that hides whether the underlying fix comes from
 * the phone's built-in GPS (via Google's FusedLocationProviderClient) or from
 * an attached USB receiver speaking NMEA 0183.
 *
 * Callers register a [Listener], call [start] in `onResume`, and [stop] in
 * `onPause`. The active source is read from [GpsPrefs] each time [start] is
 * invoked so that toggling the preference takes effect on the next resume.
 */
class LocationSource(private val context: Context) {

    interface Listener {
        fun onLocation(location: Location)
        /** UI-friendly state for the status line. */
        fun onSourceStatus(label: String)
    }

    private var listener: Listener? = null
    private var activeSource: GpsPrefs.Source = GpsPrefs.Source.INTERNAL

    // Internal (FLP)
    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { listener?.onLocation(it) }
        }
    }
    private var fusedRunning = false

    // USB
    private var usb: UsbGpsManager? = null
    private val usbListener = object : UsbGpsManager.Listener {
        override fun onLocation(location: Location) {
            listener?.onLocation(location)
        }
        override fun onStatus(status: UsbGpsManager.Status, detail: String?) {
            listener?.onSourceStatus(usbStatusLabel(status, detail))
        }
    }

    fun setListener(l: Listener?) { listener = l }

    /**
     * Begin emitting location updates from whichever source is currently
     * configured. Safe to call multiple times — duplicate starts are ignored
     * and a source switch tears down the previous source first.
     */
    fun start() {
        val desired = GpsPrefs.source(context)
        if (activeSource != desired) stopInternalAndUsb()
        activeSource = desired

        when (desired) {
            GpsPrefs.Source.INTERNAL -> startInternal()
            GpsPrefs.Source.USB -> startUsb()
        }
    }

    fun stop() {
        stopInternalAndUsb()
    }

    private fun stopInternalAndUsb() {
        if (fusedRunning) {
            fused.removeLocationUpdates(fusedCallback)
            fusedRunning = false
        }
        usb?.stop()
        usb = null
    }

    private fun startInternal() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener?.onSourceStatus("Internal GPS: permission needed")
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
        fused.requestLocationUpdates(request, fusedCallback, context.mainLooper)
        fusedRunning = true
        listener?.onSourceStatus("Internal GPS: acquiring…")
    }

    private fun startUsb() {
        // Reuse a live manager — creating a second one would orphan the first
        // with the port still open.
        usb?.let { it.start(); return }
        val baud = GpsPrefs.baudRate(context)
        val manager = UsbGpsManager(context.applicationContext, baud, usbListener)
        usb = manager
        manager.start()
    }

    private fun usbStatusLabel(status: UsbGpsManager.Status, detail: String?): String =
        when (status) {
            UsbGpsManager.Status.CONNECTED -> "USB GPS: ${detail ?: "connected"}"
            UsbGpsManager.Status.AWAITING_PERMISSION -> "USB GPS: awaiting permission…"
            UsbGpsManager.Status.NO_DEVICE -> "USB GPS: no device attached"
            UsbGpsManager.Status.ERROR -> "USB GPS error: ${detail ?: "unknown"}"
            UsbGpsManager.Status.DISCONNECTED -> "USB GPS: disconnected"
        }
}
