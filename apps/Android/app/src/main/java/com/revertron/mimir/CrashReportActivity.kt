package com.revertron.mimir

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import java.io.File

class CrashReportActivity : AppCompatActivity() {

    private lateinit var crashFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.crash_report_title)

        val crashFilePath = intent.getStringExtra("crash_file")
        if (crashFilePath == null) {
            finish()
            return
        }

        crashFile = File(crashFilePath)
        if (!crashFile.exists()) {
            finish()
            return
        }

        val crashText = findViewById<TextView>(R.id.crash_text)
        crashText.text = crashFile.readText()

        findViewById<Button>(R.id.button_close).setOnClickListener {
            finishAffinity()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_crash_report, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_share) {
            shareCrashReport()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun shareCrashReport() {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.file_provider",
            crashFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_report_subject))
            clipData = ClipData.newRawUri(null, uri)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}
