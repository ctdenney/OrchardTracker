package com.example.gpstagger

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.gpstagger.gps.GpsPrefs
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SyncConfig.load(this)

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // ── Sync section ──────────────────────────────────────────────────
        layout.addView(sectionHeader("Sync"))

        val urlField = EditText(this).apply {
            hint = "Server URL (e.g. http://192.168.1.100:8080)"
            setText(SyncConfig.serverUrl)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.bottomMargin = (8 * dp).toInt()
            }
        }
        val keyField = EditText(this).apply {
            hint = "API Key"
            setText(SyncConfig.apiKey)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.bottomMargin = (16 * dp).toInt()
            }
        }

        layout.addView(urlField)
        layout.addView(keyField)

        // ── GPS source section ────────────────────────────────────────────
        layout.addView(sectionHeader("GPS source"))

        val gpsHelp = TextView(this).apply {
            text = "Use the phone's built-in GPS, or an external USB receiver " +
                    "speaking NMEA 0183 (u-blox, GlobalSat BU-353, BadElf, Garmin GLO, etc.)."
            textSize = 12f
            alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.bottomMargin = (8 * dp).toInt()
            }
        }
        layout.addView(gpsHelp)

        val sourceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val internalRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Internal GPS (phone)"
        }
        val usbRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "External USB GPS (NMEA 0183)"
        }
        sourceGroup.addView(internalRadio)
        sourceGroup.addView(usbRadio)
        when (GpsPrefs.source(this)) {
            GpsPrefs.Source.INTERNAL -> internalRadio.isChecked = true
            GpsPrefs.Source.USB -> usbRadio.isChecked = true
        }
        layout.addView(sourceGroup)

        val baudLabel = TextView(this).apply {
            text = "USB baud rate"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.topMargin = (12 * dp).toInt()
            }
        }
        val baudSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                GpsPrefs.BAUD_OPTIONS.map { "$it" }
            )
            val current = GpsPrefs.baudRate(this@SettingsActivity)
            val idx = GpsPrefs.BAUD_OPTIONS.indexOf(current).coerceAtLeast(0)
            setSelection(idx)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.bottomMargin = (16 * dp).toInt()
            }
        }

        fun updateBaudVisibility() {
            val showBaud = usbRadio.isChecked
            baudLabel.visibility = if (showBaud) View.VISIBLE else View.GONE
            baudSpinner.visibility = if (showBaud) View.VISIBLE else View.GONE
        }
        sourceGroup.setOnCheckedChangeListener { _, _ -> updateBaudVisibility() }
        updateBaudVisibility()

        layout.addView(baudLabel)
        layout.addView(baudSpinner)

        // ── Save ──────────────────────────────────────────────────────────
        val saveBtn = Button(this).apply {
            text = "Save"
            setOnClickListener {
                SyncConfig.save(this@SettingsActivity, urlField.text.toString(), keyField.text.toString())
                val source = if (usbRadio.isChecked) GpsPrefs.Source.USB else GpsPrefs.Source.INTERNAL
                GpsPrefs.setSource(this@SettingsActivity, source)
                val baud = GpsPrefs.BAUD_OPTIONS[baudSpinner.selectedItemPosition]
                GpsPrefs.setBaudRate(this@SettingsActivity, baud)
                Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        layout.addView(saveBtn)

        setContentView(layout)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Settings"
        }
    }

    private fun sectionHeader(label: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.topMargin = (8 * dp).toInt()
                it.bottomMargin = (8 * dp).toInt()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
