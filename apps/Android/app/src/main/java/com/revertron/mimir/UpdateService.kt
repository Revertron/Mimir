package com.revertron.mimir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground service for downloading app updates in the background.
 * Shows a persistent notification with download progress.
 */
class UpdateService : Service() {

    companion object {
        private const val TAG = "UpdateService"

        // Notification constants
        private const val NOTIFICATION_ID = 4478
        private const val CHANNEL_ID = "app_updates_download"

        // Intent extras
        private const val EXTRA_APK_URL = "apk_url"
        private const val EXTRA_VERSION = "version"

        // Broadcast actions
        const val ACTION_DOWNLOAD_STARTED = "com.revertron.mimir.DOWNLOAD_STARTED"
        const val ACTION_DOWNLOAD_PROGRESS = "com.revertron.mimir.DOWNLOAD_PROGRESS"
        const val ACTION_DOWNLOAD_COMPLETE = "com.revertron.mimir.DOWNLOAD_COMPLETE"
        const val ACTION_DOWNLOAD_ERROR = "com.revertron.mimir.DOWNLOAD_ERROR"

        // Broadcast extras
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        // Download configuration
        private const val BUFFER_SIZE = 8 * 1024
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000

        /**
         * Start the update download service
         * @param context Application context
         * @param apkUrl URL to download APK from
         * @param version Version string for display purposes
         */
        fun startDownload(context: Context, apkUrl: String, version: String) {
            val intent = Intent(context, UpdateService::class.java).apply {
                putExtra(EXTRA_APK_URL, apkUrl)
                putExtra(EXTRA_VERSION, version)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var downloadThread: Thread? = null
    private var isCancelled = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val apkUrl = intent.getStringExtra(EXTRA_APK_URL)
        val version = intent.getStringExtra(EXTRA_VERSION)

        if (apkUrl == null) {
            Log.e(TAG, "No APK URL provided")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground service with initial notification
        val notification = createNotification(version ?: "Unknown", 0, false)
        startForeground(NOTIFICATION_ID, notification)

        // Start download in background thread
        downloadThread = Thread {
            downloadApk(apkUrl, version ?: "Unknown")
        }.apply { start() }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
        downloadThread?.interrupt()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * Create notification channel for download progress
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.channel_name_update_download)
            val descriptionText = getString(R.string.channel_description_update_download)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create or update notification with download progress
     */
    private fun createNotification(version: String, progress: Int, indeterminate: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, UpdateService::class.java)
        val cancelPendingIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (indeterminate) {
            getString(R.string.downloading_update, version)
        } else {
            getString(R.string.downloading_update_progress, version, progress)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, getString(R.string.cancel), cancelPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    /**
     * Download APK file and track progress
     */
    private fun downloadApk(url: String, version: String) {
        val fileName = url.substringAfterLast('/')
        Log.d(TAG, "Starting download: $fileName")

        // Broadcast download started
        sendBroadcast(ACTION_DOWNLOAD_STARTED)

        val targetUri: Uri
        try {
            targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createDownloadTargetQ(fileName)
            } else {
                val targetFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
                Uri.fromFile(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create download target", e)
            handleError(e.message ?: "Failed to create download location")
            return
        }

        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }

            val contentLength = connection.contentLength
            if (contentLength <= 0) {
                handleError("Invalid content length")
                return
            }

            Log.d(TAG, "Content length: $contentLength bytes")

            contentResolver.openOutputStream(targetUri)!!.use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                var totalRead = 0L
                var lastProgress = -1

                connection.inputStream.use { input ->
                    var bytesRead = input.read(buffer)

                    while (bytesRead >= 0 && !isCancelled) {
                        out.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val progress = ((totalRead * 100) / contentLength).toInt()

                        // Update notification and broadcast every 1%
                        if (progress != lastProgress) {
                            lastProgress = progress
                            updateNotification(version, progress)
                            sendBroadcast(ACTION_DOWNLOAD_PROGRESS, progress)
                            Log.d(TAG, "Download progress: $progress%")
                        }

                        bytesRead = input.read(buffer)
                    }
                }
            }

            if (isCancelled) {
                Log.d(TAG, "Download cancelled")
                // Delete partial download
                try {
                    contentResolver.delete(targetUri, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete partial download", e)
                }
                stopSelf()
                return
            }

            Log.d(TAG, "Download complete")
            sendBroadcast(ACTION_DOWNLOAD_COMPLETE)

            // Install the APK
            installApk(targetUri)

            stopSelf()

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            handleError(e.message ?: "Download failed")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Update notification with current progress
     */
    private fun updateNotification(version: String, progress: Int) {
        val notification = createNotification(version, progress, false)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Handle download error
     */
    private fun handleError(errorMessage: String) {
        Log.e(TAG, "Error: $errorMessage")
        sendBroadcast(ACTION_DOWNLOAD_ERROR, errorMessage = errorMessage)

        // Show error notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.download_failed))
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        stopSelf()
    }

    /**
     * Send local broadcast
     */
    private fun sendBroadcast(action: String, progress: Int = 0, errorMessage: String = "") {
        val intent = Intent(action).apply {
            if (progress > 0) {
                putExtra(EXTRA_PROGRESS, progress)
            }
            if (errorMessage.isNotEmpty()) {
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Install APK after download
     */
    private fun installApk(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start install activity", e)
        }
    }

    /**
     * Create download target for Android Q+
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createDownloadTargetQ(displayName: String): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")
    }
}