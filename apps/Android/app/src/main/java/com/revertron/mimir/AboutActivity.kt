package com.revertron.mimir

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar

class AboutActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        // App version
        val versionView = findViewById<AppCompatTextView>(R.id.app_version)
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        versionView.text = "Version: $versionName (Build $versionCode)"

        // Link buttons
        findViewById<AppCompatImageButton>(R.id.button_github).setOnClickListener {
            openLink("https://github.com/Revertron/Mimir")
        }

        findViewById<AppCompatImageButton>(R.id.button_patron).setOnClickListener {
            openLink("https://patreon.com/Revertron")
        }

        findViewById<AppCompatImageButton>(R.id.button_website).setOnClickListener {
            openLink("https://yggdrasil.link")
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    private fun openLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
