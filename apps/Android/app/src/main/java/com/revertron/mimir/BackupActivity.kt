package com.revertron.mimir

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
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

class BackupActivity : BaseActivity(), SettingsAdapter.Listener {

    companion object {
        private const val TAG = "BackupActivity"
        private const val BACKUP_FILE_PREFIX = "mimir_backup_"
    }

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
        try {
            val outputStream = contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                Toast.makeText(this, R.string.backup_export_failed, Toast.LENGTH_LONG).show()
                return
            }

            ZipOutputStream(outputStream).use { zipOut ->
                // Export database
                val dbFile = getDatabasePath(SqlStorage.DATABASE_NAME)
                if (dbFile.exists()) {
                    addFileToZip(zipOut, dbFile, SqlStorage.DATABASE_NAME)
                }

                // Export avatars directory
                val avatarsDir = File(filesDir, "avatars")
                if (avatarsDir.exists()) {
                    addDirectoryToZip(zipOut, avatarsDir, "avatars")
                }

                // Export files directory
                val filesDir = File(filesDir, "files")
                if (filesDir.exists()) {
                    addDirectoryToZip(zipOut, filesDir, "files")
                }

                // Export group chat avatars directories (avatars_*)
                val appFilesDir = getFilesDir()
                appFilesDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.name.startsWith("avatars_")) {
                        addDirectoryToZip(zipOut, file, file.name)
                    }
                }
            }

            Toast.makeText(this, R.string.backup_export_success, Toast.LENGTH_LONG).show()
            Log.i(TAG, "Backup exported successfully to $uri")

        } catch (e: Exception) {
            Log.e(TAG, "Error exporting backup", e)
            Toast.makeText(this, getString(R.string.backup_export_failed_with_error, e.message), Toast.LENGTH_LONG).show()
        }
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
        try {
            // First, validate the backup file
            if (!isValidBackupFile(uri)) {
                Toast.makeText(this, R.string.backup_import_invalid_file, Toast.LENGTH_LONG).show()
                Log.w(TAG, "Invalid backup file selected: $uri")
                return
            }

            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Toast.makeText(this, R.string.backup_import_failed, Toast.LENGTH_LONG).show()
                return
            }

            // Close database before importing
            val storage = SqlStorage(this)
            storage.close()

            // Delete existing files before importing
            deleteExistingData()

            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name

                    if (entry.isDirectory) {
                        // Create directory
                        val dir = if (entryName.startsWith("avatars") || entryName.startsWith("files")) {
                            File(filesDir, entryName)
                        } else {
                            File(filesDir, entryName)
                        }
                        dir.mkdirs()
                    } else {
                        // Extract file
                        val targetFile = when {
                            entryName == SqlStorage.DATABASE_NAME -> {
                                getDatabasePath(SqlStorage.DATABASE_NAME)
                            }
                            entryName.startsWith("avatars/") ||
                            entryName.startsWith("files/") ||
                            entryName.startsWith("avatars_") -> {
                                val file = File(filesDir, entryName)
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

            Toast.makeText(this, R.string.backup_import_success, Toast.LENGTH_LONG).show()
            Log.i(TAG, "Backup imported successfully from $uri")

            // Restart the app after import
            val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
            val builder = AlertDialog.Builder(wrapper)
            builder.setTitle(R.string.backup_import_restart_title)
            builder.setMessage(R.string.backup_import_restart_message)
            builder.setPositiveButton(R.string.backup_restart_now) { _, _ ->
                // Restart app
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finishAffinity()
            }
            builder.setCancelable(false)
            builder.show()

        } catch (e: Exception) {
            Log.e(TAG, "Error importing backup", e)
            Toast.makeText(this, getString(R.string.backup_import_failed_with_error, e.message), Toast.LENGTH_LONG).show()
        }
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
                            entryName == "avatars" ||
                            entryName == "files"

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