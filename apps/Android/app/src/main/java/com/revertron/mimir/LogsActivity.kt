package com.revertron.mimir

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.LogAdapter
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LogActivity : BaseActivity() {

    lateinit var logs: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        val recycler = findViewById<RecyclerView>(R.id.logRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))

        logs = getLogcatLastMinutes(10)

        recycler.adapter = LogAdapter(logs)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_logs, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            R.id.action_share -> {
                showExportDialog()
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    private fun showExportDialog() {
        val intervals = arrayOf(
            getString(R.string.interval_10_minutes),
            getString(R.string.interval_30_minutes),
            getString(R.string.interval_60_minutes),
            getString(R.string.interval_24_hours)
        )

        val intervalMinutes = longArrayOf(10, 30, 60, 24 * 60)

        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.select_log_interval)
            .setItems(intervals) { _, which ->
                exportLogsAsZip(intervalMinutes[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportLogsAsZip(minutes: Long) {
        Toast.makeText(this, R.string.exporting_logs, Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // Collect full logcat for the specified time interval
                val logLines = collectLogcat(minutes)

                if (logLines.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.no_logs_to_share, Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // Create filename with timestamp
                val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
                val zipFileName = "mimir-logs-$timestamp.zip"

                // Create zip file in cache directory
                val cacheDir = cacheDir
                val zipFile = File(cacheDir, zipFileName)

                // Write logs to zip file
                ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                    val entry = ZipEntry("mimir-logcat.txt")
                    zipOut.putNextEntry(entry)

                    logLines.forEach { line ->
                        zipOut.write((line + "\n").toByteArray())
                    }

                    zipOut.closeEntry()
                }

                // Share the zip file
                runOnUiThread {
                    shareZipFile(zipFile)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    val message = getString(R.string.error_exporting_logs, e.message ?: "Unknown error")
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }.start()
    }

    private fun collectLogcat(minutes: Long): List<String> {
        val result = mutableListOf<String>()

        try {
            // Run logcat command to get all logs with timestamp
            val process = Runtime.getRuntime().exec("logcat -d -v time")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val now = System.currentTimeMillis()
            val minutesAgo = now - minutes * 60 * 1000

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.length < 18) continue // Skip lines without date

                val tsString = line!!.substring(0, 18) // "MM-dd HH:mm:ss.SSS"
                try {
                    val parsed = sdf.parse(tsString)
                    val calendar = java.util.Calendar.getInstance()
                    calendar.time = parsed!!
                    // Set current year, otherwise dates will be "1970"
                    calendar.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
                    val timestamp = calendar.timeInMillis

                    if (timestamp >= minutesAgo) {
                        result.add(line!!)
                    }
                } catch (_: Exception) {
                    // Lines without proper timestamp, skip
                }
            }
            reader.close()
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    private fun shareZipFile(zipFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.file_provider",
                zipFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri(null, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }

            val chooser = Intent.createChooser(shareIntent, getString(R.string.export_logs))
            startActivity(chooser)

            Toast.makeText(this, R.string.logs_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val message = getString(R.string.error_exporting_logs, e.message ?: "Unknown error")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
}
