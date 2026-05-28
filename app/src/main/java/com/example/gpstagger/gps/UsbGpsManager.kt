package com.example.gpstagger.gps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * Owns the lifecycle of a USB-attached GPS receiver. Probes for any
 * supported USB-serial bridge (CDC-ACM, FTDI, CP210x, CH34x, PL2303), asks
 * the user for permission if needed, opens the port, and streams NMEA 0183
 * sentences into [NmeaParser]. Parsed [Location] objects are forwarded to
 * the listener supplied at construction.
 *
 * The class is intentionally self-contained: callers don't need to know
 * anything about USB or NMEA — they just register a listener and call
 * [start] / [stop].
 */
class UsbGpsManager(
    private val appContext: Context,
    private val baudRate: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onLocation(location: Location)
        fun onStatus(status: Status, detail: String? = null)
    }

    enum class Status { DISCONNECTED, NO_DEVICE, AWAITING_PERMISSION, CONNECTED, ERROR }

    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor = Executors.newSingleThreadExecutor()
    private val parser = NmeaParser { loc -> listener.onLocation(loc) }

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private var status: Status = Status.DISCONNECTED

    /**
     * Try to attach to the first supported USB-serial device. Idempotent:
     * calling while already connected is a no-op.
     */
    fun start() {
        if (status == Status.CONNECTED) return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            updateStatus(Status.NO_DEVICE, "No USB GPS attached")
            return
        }
        val driver = drivers.first()
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return
        }
        openDevice(device)
    }

    /** Tear down the port, IO manager, and any pending permission receiver. */
    fun stop() {
        try {
            ioManager?.stop()
        } catch (_: Exception) { /* already stopped */ }
        ioManager = null

        try {
            port?.close()
        } catch (_: Exception) { /* already closed */ }
        port = null

        permissionReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) { /* not registered */ }
        }
        permissionReceiver = null
        updateStatus(Status.DISCONNECTED)
    }

    fun currentStatus(): Status = status

    // ── Permission flow ──────────────────────────────────────────────────────

    private fun requestPermission(device: UsbDevice) {
        updateStatus(Status.AWAITING_PERMISSION, device.productName ?: device.deviceName)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else 0
        val intent = PendingIntent.getBroadcast(
            appContext, 0, Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName), flags
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice?
                }
                try {
                    appContext.unregisterReceiver(this)
                } catch (_: IllegalArgumentException) { /* ignore */ }
                permissionReceiver = null

                if (granted && dev != null) {
                    openDevice(dev)
                } else {
                    updateStatus(Status.ERROR, "USB permission denied")
                }
            }
        }
        permissionReceiver = receiver

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        usbManager.requestPermission(device, intent)
    }

    // ── Port open / read loop ────────────────────────────────────────────────

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber()
            .probeDevice(device)
            ?: run {
                updateStatus(Status.ERROR, "No driver for ${device.productName ?: device.deviceName}")
                return
            }
        val connection = usbManager.openDevice(driver.device) ?: run {
            updateStatus(Status.ERROR, "Could not open USB device")
            return
        }
        val newPort = driver.ports.firstOrNull() ?: run {
            updateStatus(Status.ERROR, "Device has no serial ports")
            return
        }
        try {
            newPort.open(connection)
            newPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Most NMEA receivers don't use hardware flow control; toggling DTR
            // wakes a few u-blox modules out of low-power mode.
            try { newPort.dtr = true } catch (_: Exception) { /* unsupported on some drivers */ }
            try { newPort.rts = true } catch (_: Exception) { /* unsupported on some drivers */ }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open USB GPS port", e)
            try { newPort.close() } catch (_: Exception) {}
            updateStatus(Status.ERROR, e.message ?: "Port open failed")
            return
        }

        val io = SerialInputOutputManager(newPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                parser.feed(data, data.size)
            }
            override fun onRunError(e: Exception) {
                Log.w(TAG, "USB GPS read error", e)
                listener.onStatus(Status.ERROR, e.message ?: "Read error")
                stop()
            }
        })
        executor.submit(io)

        port = newPort
        ioManager = io
        updateStatus(Status.CONNECTED, device.productName ?: device.deviceName)
    }

    private fun updateStatus(next: Status, detail: String? = null) {
        status = next
        listener.onStatus(next, detail)
    }

    companion object {
        private const val TAG = "UsbGpsManager"
        private const val ACTION_USB_PERMISSION = "com.example.gpstagger.USB_PERMISSION"
    }
}
