package com.example.gpstagger

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.gpstagger.databinding.ActivityEditLabelsBinding

class EditLabelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditLabelsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditLabelsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Edit Button Labels"
        }

        loadCurrentLabels()

        binding.btnSaveLabels.setOnClickListener { saveAndFinish() }

        binding.btnResetDefaults.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset to Defaults?")
                .setMessage("This will restore all button labels to their original names.")
                .setPositiveButton("Reset") { _, _ ->
                    TagPreferences.resetAll(this)
                    loadCurrentLabels()
                    Toast.makeText(this, "Labels reset to defaults", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadCurrentLabels() {
        val labels = TagPreferences.getLabels(this)
        val fields = listOf(
            binding.etLabel0, binding.etLabel1, binding.etLabel2,
            binding.etLabel3, binding.etLabel4, binding.etLabel5
        )
        val tils = listOf(
            binding.tilLabel0, binding.tilLabel1, binding.tilLabel2,
            binding.tilLabel3, binding.tilLabel4, binding.tilLabel5
        )
        fields.forEachIndexed { i, field -> field.setText(labels[i]) }

        // Apply each slot's colour to the TextInputLayout outline + hint
        tils.forEachIndexed { i, til ->
            val color = TagPreferences.slotColor(i)
            val csl   = ColorStateList.valueOf(color)
            til.setBoxStrokeColorStateList(csl)
            til.hintTextColor = csl
        }
    }

    private fun saveAndFinish() {
        val fields = listOf(
            binding.etLabel0, binding.etLabel1, binding.etLabel2,
            binding.etLabel3, binding.etLabel4, binding.etLabel5
        )
        fields.forEachIndexed { i, field ->
            TagPreferences.setLabel(this, i, field.text.toString())
        }
        Toast.makeText(this, "Labels saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
