package com.revertron.mimir

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Spinner
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_FORMAT
import com.revertron.mimir.ui.SettingsData.KEY_IMAGES_QUALITY


class ImageSettingsActivity: BaseActivity(), AdapterView.OnItemSelectedListener {

    lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.image_settings_activity)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val spinner = findViewById<View?>(R.id.image_sizes_spinner) as Spinner
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.image_sizes,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.setAdapter(adapter)
        spinner.setSelection(prefs.getInt(KEY_IMAGES_FORMAT, 0))
        spinner.onItemSelectedListener = this

        val qualityText = findViewById<AppCompatTextView>(R.id.quality_percent)

        val seekBar = findViewById<SeekBar>(R.id.quality_seekbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            seekBar.min = 60
        }
        seekBar.max = 99
        val quality = prefs.getInt(KEY_IMAGES_QUALITY, 95)
        qualityText.text = "$quality%"
        seekBar.progress = quality
        seekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityText.text = "$progress%"
                prefs.edit {
                    putInt(KEY_IMAGES_QUALITY, progress)
                    commit()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                //
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                //
            }

        })
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        prefs.edit {
            putInt(KEY_IMAGES_FORMAT, position)
            commit()
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
        // nothing
    }
}