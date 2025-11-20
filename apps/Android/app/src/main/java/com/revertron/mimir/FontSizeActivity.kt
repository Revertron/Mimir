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

    // Discrete font sizes in sp
    private val fontSizes = intArrayOf(11, 13, 15, 18, 21)

    // String resource IDs for font size labels
    private val fontSizeLabels = intArrayOf(
        R.string.font_size_very_small,
        R.string.font_size_small,
        R.string.font_size_medium,
        R.string.font_size_large,
        R.string.font_size_very_large
    )

    private val defaultIndex = 2 // Medium (15sp)

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

        // Configure SeekBar to use discrete values (0-4 indices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            seekBar.min = 0
        }
        seekBar.max = fontSizes.size - 1

        // Get current font size and find closest index
        val currentFontSize = prefs.getInt(KEY_MESSAGE_FONT_SIZE, fontSizes[defaultIndex])
        val currentIndex = findClosestSizeIndex(currentFontSize)

        // Set initial UI state
        fontSizeValue.text = getString(fontSizeLabels[currentIndex])
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizes[currentIndex].toFloat())
        seekBar.progress = currentIndex

        seekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Ensure progress is within valid range (for API < 26)
                val index = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    progress
                } else {
                    progress.coerceIn(0, fontSizes.size - 1)
                }

                val size = fontSizes[index]
                fontSizeValue.text = getString(fontSizeLabels[index])
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

    /**
     * Find the closest font size index for a given size value
     */
    private fun findClosestSizeIndex(size: Int): Int {
        var closestIndex = defaultIndex
        var minDiff = Int.MAX_VALUE

        for (i in fontSizes.indices) {
            val diff = kotlin.math.abs(fontSizes[i] - size)
            if (diff < minDiff) {
                minDiff = diff
                closestIndex = i
            }
        }

        return closestIndex
    }
}
