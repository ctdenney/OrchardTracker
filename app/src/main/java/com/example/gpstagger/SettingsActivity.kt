package com.example.gpstagger

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

        val saveBtn = Button(this).apply {
            text = "Save"
            setOnClickListener {
                SyncConfig.save(this@SettingsActivity, urlField.text.toString(), keyField.text.toString())
                Toast.makeText(this@SettingsActivity, "Sync settings saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        layout.addView(urlField)
        layout.addView(keyField)
        layout.addView(saveBtn)

        setContentView(layout)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Sync Settings"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
