package com.revertron.mimir

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import androidx.core.net.toUri

class UpdateActivity: BaseActivity() {

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

        val progressBar = findViewById<ProgressBar>(R.id.progress_bar)
        progressBar.visibility = View.GONE

        val apkUrl = intent.getStringExtra("apk")!!
        val downloadButton = findViewById<AppCompatButton>(R.id.download_button)
        downloadButton.setOnClickListener { button ->
            ApkInstaller.download(this, apkUrl!!, object : DownloadListener {
                override fun onDownloadStarted() {
                    runOnUiThread { progressBar.visibility = View.VISIBLE }
                }

                override fun onDownloadProgress(percent: Int) {
                    runOnUiThread { progressBar.progress = percent }
                }

                override fun onDownloadFinished(error: String?) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        error?.let {
                            Toast.makeText(this@UpdateActivity, it, Toast.LENGTH_LONG).show()
                            openUrl(this@UpdateActivity, apkUrl)
                        }
                    }
                }
            })
        }
        downloadButton.setOnLongClickListener {
            openUrl(this@UpdateActivity, apkUrl)
            true
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