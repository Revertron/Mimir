package com.revertron.mimir

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import androidx.core.net.toUri

class UpdateActivity: BaseActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: AppCompatButton
    private var isServiceDownloading = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UpdateService.ACTION_DOWNLOAD_STARTED -> {
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = false
                    downloadButton.isEnabled = false
                }
                UpdateService.ACTION_DOWNLOAD_PROGRESS -> {
                    val progress = intent.getIntExtra(UpdateService.EXTRA_PROGRESS, 0)
                    progressBar.progress = progress
                }
                UpdateService.ACTION_DOWNLOAD_COMPLETE -> {
                    progressBar.visibility = View.GONE
                    downloadButton.isEnabled = true
                    isServiceDownloading = false
                    Toast.makeText(this@UpdateActivity, "Download complete", Toast.LENGTH_SHORT).show()
                    finish()
                }
                UpdateService.ACTION_DOWNLOAD_ERROR -> {
                    val error = intent.getStringExtra(UpdateService.EXTRA_ERROR_MESSAGE)
                    progressBar.visibility = View.GONE
                    downloadButton.isEnabled = true
                    isServiceDownloading = false
                    Toast.makeText(this@UpdateActivity, error ?: "Download failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.update_activity)

        val title = findViewById<AppCompatTextView>(R.id.new_version_title)
        val version = intent.getStringExtra("version")
        title.text = getString(R.string.new_version_title, version)
        val title2 = findViewById<AppCompatTextView>(R.id.old_version_title)
        title2.text = getString(R.string.old_version_title, BuildConfig.VERSION_NAME)

        val description = findViewById<AppCompatTextView>(R.id.changelogs_text)
        description.text = intent.getStringExtra("desc")

        progressBar = findViewById(R.id.progress_bar)
        progressBar.visibility = View.GONE

        val apkUrl = intent.getStringExtra("apk")!!
        downloadButton = findViewById(R.id.download_button)
        downloadButton.setOnClickListener {
            // Start UpdateService for background download
            isServiceDownloading = true
            UpdateService.startDownload(this, apkUrl, version ?: "Unknown")
            Toast.makeText(this, R.string.downloading_update, Toast.LENGTH_SHORT).show()
        }
        downloadButton.setOnLongClickListener {
            openUrl(this@UpdateActivity, apkUrl)
            true
        }

        // Register broadcast receiver for download progress
        registerDownloadReceiver()
    }

    override fun onResume() {
        super.onResume()
        registerDownloadReceiver()
    }

    override fun onPause() {
        super.onPause()
        unregisterDownloadReceiver()
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter().apply {
            addAction(UpdateService.ACTION_DOWNLOAD_STARTED)
            addAction(UpdateService.ACTION_DOWNLOAD_PROGRESS)
            addAction(UpdateService.ACTION_DOWNLOAD_COMPLETE)
            addAction(UpdateService.ACTION_DOWNLOAD_ERROR)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadReceiver, filter)
    }

    private fun unregisterDownloadReceiver() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun openUrl(ctx: Context, url: String) {
        val i = Intent(Intent.ACTION_VIEW)
        i.setData(url.toUri())
        ctx.startActivity(i)
    }

    object ApkInstaller {

        private const val BUFFER = 8 * 1024
        private val executor = Executors.newSingleThreadExecutor()

        fun download(ctx: Context, url: String, listener: DownloadListener) {
            executor.execute { realDownload(ctx, url, listener) }
        }

        private fun realDownload(ctx: Context, url: String, listener: DownloadListener) {
            val fileName = url.substringAfterLast('/')
            val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createDownloadTargetQ(ctx, fileName)
            } else {
                // API 21-28: classic file, *do* need permission
                val targetFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
                Uri.fromFile(targetFile)
            }

            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }

                val length = connection.contentLength
                if (length <= 0) {
                    listener.onDownloadFinished("Wrong content length")
                    return
                }

                ctx.contentResolver.openOutputStream(targetUri)!!.use { out ->
                    listener.onDownloadStarted()
                    var copied = 0L
                    val buffer = ByteArray(BUFFER)
                    connection.inputStream.use { inp ->
                        var bytes = inp.read(buffer)
                        while (bytes >= 0) {
                            out.write(buffer, 0, bytes)
                            copied += bytes
                            listener.onDownloadProgress((copied * 100 / length).toInt())
                            bytes = inp.read(buffer)
                        }
                    }
                }
                listener.onDownloadFinished(null)
                install(ctx, targetUri)
            } catch (e: Exception) {
                listener.onDownloadFinished(e.message)
            } finally {
                connection?.disconnect()
            }
        }

        private fun install(ctx: Context, uri: Uri) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun createDownloadTargetQ(context: Context, displayName: String): Uri =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }.let { cv ->
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            }
    }

    interface DownloadListener {
        fun onDownloadStarted()
        fun onDownloadProgress(percent: Int)
        fun onDownloadFinished(error: String?)
    }
}