package com.revertron.mimir

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager

const val IP_CACHE_TTL = "ip_cache_ttl"
const val IP_CACHE_PROGRESS = "ip_cache_progress"
const val IP_CACHE_DEFAULT_TTL = 43200

class SettingsActivity : BaseActivity() {

    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)

        val cacheSelectedText = findViewById<AppCompatTextView>(R.id.ip_cache_selected_text)

        val progress = preferences.getInt(IP_CACHE_PROGRESS, 3)
        val data = getSeekBarData(progress)
        cacheSelectedText.text = data.second

        val seekBar = findViewById<SeekBar>(R.id.ip_cache_seekbar)
        seekBar.progress = progress
        seekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val data = getSeekBarData(progress)
                cacheSelectedText.text = data.second
                preferences.edit().apply {
                    putInt(IP_CACHE_TTL, data.first)
                    putInt(IP_CACHE_PROGRESS, progress)
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

    fun getSeekBarData(progress: Int): Pair<Int, String> {
        return when (progress) {
            0 -> 3600 to getString(R.string.one_hour)
            1 -> 10800 to getString(R.string.three_hours)
            2 -> 21600 to getString(R.string.six_hours)
            3 -> 43200 to getString(R.string.twelve_hours)
            4 -> 86400 to getString(R.string.one_day)
            5 -> 259200 to getString(R.string.three_days)
            else -> 1296000 to getString(R.string.fifteen_days)
        }
    }
}