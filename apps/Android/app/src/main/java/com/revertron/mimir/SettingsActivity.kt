package com.revertron.mimir

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.SettingsAdapter
import com.revertron.mimir.ui.SettingsData

class SettingsActivity : BaseActivity(), SettingsAdapter.Listener {

    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)

        val recycler = findViewById<RecyclerView>(R.id.settingsRecyclerView)
        recycler.setLayoutManager(LinearLayoutManager(this))
        recycler.setAdapter(SettingsAdapter(SettingsData.create(this), this))
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    override fun onSwitchToggled(id: Int, isChecked: Boolean) {
        when (id) {
            R.string.automatic_updates_checking -> {
                preferences.edit().apply {
                    putBoolean(SettingsData.KEY_AUTO_UPDATES, isChecked)
                    commit()
                }
            }
        }
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            R.string.configure_peers -> {
                val intent = Intent(this, PeersActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.resize_big_pics -> {
                val intent = Intent(this, ImageSettingsActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.check_for_updates -> {
                val intent = Intent(this@SettingsActivity, ConnectionService::class.java).apply {
                    putExtra("command", "check_updates")
                }
                startService(intent)
            }
            R.string.collect_logs -> {
                val intent = Intent(this, LogActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.action_about -> {
                val intent = Intent(this, AboutActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
        }
    }
}