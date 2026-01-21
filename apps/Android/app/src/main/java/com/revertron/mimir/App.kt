package com.revertron.mimir

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.storage.SqlStorage
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess


class App: Application() {

    companion object {
        lateinit var app: App
    }

    var mediatorManager: MediatorManager? = null
    var online: Boolean = false
    lateinit var storage: SqlStorage
    lateinit var callback: NetState

    override fun onCreate() {
        super.onCreate()

        // Install global exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        NotificationHelper.createCallChannels(this)
        NotificationHelper.migrateNotificationChannels(this)

        storage = SqlStorage(this)
        storage.cleanUp()
        storage.cleanupOldDrafts(7) // Clean up drafts older than 7 days
        storage.updateUnreadCountsForGroups()
        app = this
        callback = NetState(this)
        val handler = Handler(mainLooper)
        handler.postDelayed({
            callback.register()
        }, 500)
    }

    override fun onTerminate() {
        callback.unregister()
        super.onTerminate()
    }

    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val timestamp = System.currentTimeMillis()
            val report = buildCrashReport(thread, throwable, timestamp)
            val file = saveCrashReport(report, timestamp)

            // Launch CrashReportActivity
            val intent = Intent(this, CrashReportActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("crash_file", file.absolutePath)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // If we fail to show the activity, at least the file was saved
        }

        // Kill the process
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable, timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("=== MIMIR CRASH REPORT ===")
            appendLine()
            appendLine("Timestamp: ${dateFormat.format(Date(timestamp))}")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("=== EXCEPTION ===")
            appendLine(throwable.javaClass.name + ": " + throwable.message)
            appendLine()
            appendLine("=== STACK TRACE ===")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            appendLine(sw.toString())
        }
    }

    private fun saveCrashReport(report: String, timestamp: Long): File {
        val tracesDir = File(filesDir, "traces")
        if (!tracesDir.exists()) {
            tracesDir.mkdirs()
        }
        val file = File(tracesDir, "$timestamp.txt")
        file.writeText(report)
        return file
    }
}