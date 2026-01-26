package com.revertron.mimir

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.ui.SettingsAdapter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

class BackupActivity : BaseActivity(), SettingsAdapter.Listener {

    companion object {
        private const val TAG = "BackupActivity"
        private const val BACKUP_FILE_PREFIX = "mimir_backup_"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressDialog: AlertDialog? = null
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { exportData(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importData(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val items = listOf(
            SettingsAdapter.Item(
                id = R.string.backup_export,
                titleRes = R.string.backup_export,
                descriptionRes = R.string.backup_export_desc,
                isSwitch = false,
                checked = false
            ),
            SettingsAdapter.Item(
                id = R.string.backup_import,
                titleRes = R.string.backup_import,
                descriptionRes = R.string.backup_import_desc,
                isSwitch = false,
                checked = false
            )
        )

        val recycler = findViewById<RecyclerView>(R.id.backupRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = SettingsAdapter(items, this)
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
        // No switches in backup activity
    }

    private fun lockOrientation() {
        savedOrientation = requestedOrientation
        val currentOrientation = resources.configuration.orientation
        requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun unlockOrientation() {
        requestedOrientation = savedOrientation
    }

    private fun showProgressDialog(messageResId: Int) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder = AlertDialog.Builder(wrapper)

        // Create a simple layout with progress bar and text
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)

            val progressBar = ProgressBar(context).apply {
                isIndeterminate = true
            }
            addView(progressBar)

            val textView = TextView(context).apply {
                text = getString(messageResId)
                setPadding(32, 0, 0, 0)
            }
            addView(textView)
        }

        builder.setView(layout)
        builder.setCancelable(false)
        progressDialog = builder.show()
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            R.string.backup_export -> {
                val timestamp = System.currentTimeMillis()
                exportLauncher.launch("${BACKUP_FILE_PREFIX}${timestamp}.zip")
            }
            R.string.backup_import -> {
                importLauncher.launch(arrayOf("application/zip"))
            }
        }
    }

    private fun exportData(uri: Uri) {
        val outputStream = contentResolver.openOutputStream(uri)
        if (outputStream == null) {
            Toast.makeText(this, R.string.backup_export_failed, Toast.LENGTH_LONG).show()
            return
        }

        // Lock orientation and show progress
        lockOrientation()
        showProgressDialog(R.string.backup_exporting)

        // Capture paths on main thread before background work
        val dbFile = getDatabasePath(SqlStorage.DATABASE_NAME)
        val avatarsDir = File(filesDir, "avatars")
        val mediaFilesDir = File(filesDir, "files")
        val appFilesDir = filesDir
        val prefsDir = File(applicationInfo.dataDir, "shared_prefs")

        Thread {
            var success = false
            var errorMessage: String? = null

            try {
                // Checkpoint the WAL to ensure all data is written to the main database file
                try {
                    App.app.storage.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                        cursor.moveToFirst()
                        Log.i(TAG, "WAL checkpoint completed")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WAL checkpoint failed, continuing with export", e)
                }

                ZipOutputStream(outputStream).use { zipOut ->
                    // Export database
                    if (dbFile.exists()) {
                        addFileToZip(zipOut, dbFile, SqlStorage.DATABASE_NAME)
                    }

                    // Export avatars directory
                    if (avatarsDir.exists()) {
                        addDirectoryToZip(zipOut, avatarsDir, "avatars")
                    }

                    // Export files directory
                    if (mediaFilesDir.exists()) {
                        addDirectoryToZip(zipOut, mediaFilesDir, "files")
                    }

                    // Export group chat avatars directories (avatars_*)
                    appFilesDir.listFiles()?.forEach { file ->
                        if (file.isDirectory && file.name.startsWith("avatars_")) {
                            addDirectoryToZip(zipOut, file, file.name)
                        }
                    }

                    // Export shared preferences
                    if (prefsDir.exists()) {
                        addDirectoryToZip(zipOut, prefsDir, "shared_prefs")
                        Log.i(TAG, "Exported shared preferences")
                    }
                }

                success = true
                Log.i(TAG, "Backup exported successfully to $uri")

            } catch (e: Exception) {
                Log.e(TAG, "Error exporting backup", e)
                errorMessage = e.message
            }

            // Post results back to main thread
            mainHandler.post {
                dismissProgressDialog()
                unlockOrientation()

                if (success) {
                    Toast.makeText(this, R.string.backup_export_success, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.backup_export_failed_with_error, errorMessage), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun importData(uri: Uri) {
        try {
            // Show warning dialog
            val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
            val builder = AlertDialog.Builder(wrapper)
            builder.setTitle(R.string.backup_import_warning_title)
            builder.setMessage(R.string.backup_import_warning_message)
            builder.setPositiveButton(R.string.backup_import_continue) { _, _ ->
                performImport(uri)
            }
            builder.setNegativeButton(R.string.cancel, null)
            builder.show()

        } catch (e: Exception) {
            Log.e(TAG, "Error showing import dialog", e)
            Toast.makeText(this, getString(R.string.backup_import_failed_with_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun performImport(uri: Uri) {
        // Lock orientation and show progress
        lockOrientation()
        showProgressDialog(R.string.backup_importing)

        // Capture paths on main thread before background work
        val dbPath = getDatabasePath(SqlStorage.DATABASE_NAME)
        val appFilesDir = filesDir
        val appDataDir = applicationInfo.dataDir

        Thread {
            var success = false
            var errorMessage: String? = null
            var isInvalidFile = false

            try {
                // Validate the backup file
                if (!isValidBackupFile(uri)) {
                    Log.w(TAG, "Invalid backup file selected: $uri")
                    isInvalidFile = true
                    throw Exception("Invalid backup file")
                }

                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    throw Exception("Cannot open backup file")
                }

                // Stop ConnectionService before import to release database connections
                Log.i(TAG, "Stopping ConnectionService before import")
                mainHandler.post {
                    stopService(Intent(this, ConnectionService::class.java))
                }
                // Give service time to stop
                Thread.sleep(500)

                // Close the application's database connection (not a new instance!)
                Log.i(TAG, "Closing application database connection")
                App.app.storage.close()

                // Delete existing files before importing
                deleteExistingData()

                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val entryName = entry.name

                        if (entry.isDirectory) {
                            // Create directory
                            val dir = if (entryName.startsWith("avatars") || entryName.startsWith("files")) {
                                File(appFilesDir, entryName)
                            } else {
                                File(appFilesDir, entryName)
                            }
                            dir.mkdirs()
                        } else {
                            // Extract file
                            val targetFile = when {
                                entryName == SqlStorage.DATABASE_NAME -> {
                                    dbPath
                                }
                                entryName.startsWith("avatars/") ||
                                entryName.startsWith("files/") ||
                                entryName.startsWith("avatars_") -> {
                                    val file = File(appFilesDir, entryName)
                                    file.parentFile?.mkdirs()
                                    file
                                }
                                entryName.startsWith("shared_prefs/") -> {
                                    val file = File(appDataDir, entryName)
                                    file.parentFile?.mkdirs()
                                    file
                                }
                                else -> {
                                    Log.w(TAG, "Skipping unknown file: $entryName")
                                    zipIn.closeEntry()
                                    entry = zipIn.nextEntry
                                    continue
                                }
                            }

                            // Write file
                            FileOutputStream(targetFile).use { output ->
                                zipIn.copyTo(output)
                            }
                        }

                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }

                success = true
                Log.i(TAG, "Backup imported successfully from $uri")

            } catch (e: Exception) {
                Log.e(TAG, "Error importing backup", e)
                errorMessage = e.message
            }

            // Post results back to main thread
            mainHandler.post {
                dismissProgressDialog()
                unlockOrientation()

                if (success) {
                    Toast.makeText(this, R.string.backup_import_success, Toast.LENGTH_LONG).show()

                    // Restart the app after import
                    val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
                    val builder = AlertDialog.Builder(wrapper)
                    builder.setTitle(R.string.backup_import_restart_title)
                    builder.setMessage(R.string.backup_import_restart_message)
                    builder.setPositiveButton(R.string.backup_restart_now) { _, _ ->
                        // Restart app by scheduling launch and killing the process
                        // This ensures clean restart with fresh Application instance
                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)

                        // Kill the process to force complete restart
                        // This clears App singleton and all database connections
                        android.os.Process.killProcess(android.os.Process.myPid())
                        exitProcess(0)
                    }
                    builder.setCancelable(false)
                    builder.show()
                } else if (isInvalidFile) {
                    Toast.makeText(this, R.string.backup_import_invalid_file, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.backup_import_failed_with_error, errorMessage), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun isValidBackupFile(uri: Uri): Boolean {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return false

            var hasDatabaseFile = false
            val foundEntries = mutableListOf<String>()

            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    foundEntries.add(entryName)

                    // Check for required database file
                    if (entryName == SqlStorage.DATABASE_NAME) {
                        hasDatabaseFile = true
                    }

                    // Validate that all entries match expected patterns
                    val isValidEntry = entryName == SqlStorage.DATABASE_NAME ||
                            entryName.startsWith("avatars/") ||
                            entryName.startsWith("files/") ||
                            entryName.startsWith("avatars_") ||
                            entryName.startsWith("shared_prefs/") ||
                            entryName == "avatars" ||
                            entryName == "files" ||
                            entryName == "shared_prefs"

                    if (!isValidEntry) {
                        Log.w(TAG, "Invalid entry found in backup: $entryName")
                        return false
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            // Must contain database file
            if (!hasDatabaseFile) {
                Log.w(TAG, "Backup file does not contain database file")
                return false
            }

            Log.i(TAG, "Backup file validation passed. Found entries: $foundEntries")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error validating backup file", e)
            return false
        }
    }

    private fun deleteExistingData() {
        try {
            // Delete database file
            val dbFile = getDatabasePath(SqlStorage.DATABASE_NAME)
            if (dbFile.exists()) {
                val deleted = dbFile.delete()
                Log.i(TAG, "Database file deleted: $deleted")
            }

            // Delete SQLite WAL and SHM files (Write-Ahead Log)
            val walFile = File(dbFile.absolutePath + "-wal")
            if (walFile.exists()) {
                val deleted = walFile.delete()
                Log.i(TAG, "WAL file deleted: $deleted")
            }
            val shmFile = File(dbFile.absolutePath + "-shm")
            if (shmFile.exists()) {
                val deleted = shmFile.delete()
                Log.i(TAG, "SHM file deleted: $deleted")
            }

            // Delete avatars directory
            val avatarsDir = File(filesDir, "avatars")
            if (avatarsDir.exists()) {
                val deleted = avatarsDir.deleteRecursively()
                Log.i(TAG, "Avatars directory deleted: $deleted")
            }

            // Delete files directory
            val filesDirectory = File(filesDir, "files")
            if (filesDirectory.exists()) {
                val deleted = filesDirectory.deleteRecursively()
                Log.i(TAG, "Files directory deleted: $deleted")
            }

            // Delete group chat avatars directories (avatars_*)
            val appFilesDir = getFilesDir()
            appFilesDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith("avatars_")) {
                    val deleted = file.deleteRecursively()
                    Log.i(TAG, "Group avatars directory ${file.name} deleted: $deleted")
                }
            }

            // Delete shared preferences directory
            val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                val deleted = prefsDir.deleteRecursively()
                Log.i(TAG, "Shared preferences directory deleted: $deleted")
            }

            Log.i(TAG, "All existing data deleted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting existing data", e)
            throw e
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { input ->
            val entry = ZipEntry(entryName)
            zipOut.putNextEntry(entry)
            input.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun addDirectoryToZip(zipOut: ZipOutputStream, directory: File, basePath: String) {
        directory.listFiles()?.forEach { file ->
            val entryName = "$basePath/${file.name}"
            if (file.isDirectory) {
                addDirectoryToZip(zipOut, file, entryName)
            } else {
                addFileToZip(zipOut, file, entryName)
            }
        }
    }
}