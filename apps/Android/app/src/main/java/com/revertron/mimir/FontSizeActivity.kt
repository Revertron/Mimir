package com.revertron.mimir

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.revertron.mimir.ui.SettingsData.KEY_MESSAGE_FONT_SIZE


class FontSizeActivity: BaseActivity() {

    lateinit var prefs: SharedPreferences

    private val fontSizeMin = 11
    private val fontSizeMax = 23
    private val fontSizeDefault = 15

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.font_size_activity)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val fontSizeValue = findViewById<AppCompatTextView>(R.id.font_size_value)
        val previewText = findViewById<AppCompatTextView>(R.id.preview_text)

        val seekBar = findViewById<SeekBar>(R.id.font_size_seekbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            seekBar.min = fontSizeMin
        }
        seekBar.max = fontSizeMax

        val currentFontSize = prefs.getInt(KEY_MESSAGE_FONT_SIZE, fontSizeDefault)
        fontSizeValue.text = "${currentFontSize}sp"
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentFontSize.toFloat())
        seekBar.progress = currentFontSize

        seekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    progress
                } else {
                    progress.coerceAtLeast(fontSizeMin)
                }
                fontSizeValue.text = "${size}sp"
                previewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
                prefs.edit {
                    putInt(KEY_MESSAGE_FONT_SIZE, size)
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
}
