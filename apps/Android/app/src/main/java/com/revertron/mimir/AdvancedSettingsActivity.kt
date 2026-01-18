package com.revertron.mimir

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.text.format.Formatter
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.SettingsAdapter
import java.io.File

class AdvancedSettingsActivity : BaseActivity(), SettingsAdapter.Listener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val items = listOf(
            SettingsAdapter.Item(
                id = R.string.configure_peers,
                titleRes = R.string.configure_peers,
                descriptionRes = R.string.configure_peers_description,
                isSwitch = false,
                checked = false
            ),
            SettingsAdapter.Item(
                id = R.string.fix_corrupted_messages,
                titleRes = R.string.fix_corrupted_messages,
                descriptionRes = R.string.fix_corrupted_messages_desc,
                isSwitch = false,
                checked = false
            ),
            SettingsAdapter.Item(
                id = R.string.delete_orphaned_media,
                titleRes = R.string.delete_orphaned_media,
                descriptionRes = R.string.delete_orphaned_media_desc,
                isSwitch = false,
                checked = false
            ),
            SettingsAdapter.Item(
                id = R.string.collect_logs,
                titleRes = R.string.collect_logs,
                descriptionRes = R.string.collect_logs_desc,
                isSwitch = false,
                checked = false
            ),
            SettingsAdapter.Item(
                id = R.string.action_exit,
                titleRes = R.string.action_exit,
                descriptionRes = R.string.action_exit_desc,
                isSwitch = false,
                checked = false
            )
        )

        val recycler = findViewById<RecyclerView>(R.id.advancedRecyclerView)
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
        // No switches in advanced settings
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            R.string.configure_peers -> {
                val intent = Intent(this, PeersActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.fix_corrupted_messages -> {
                showFixCorruptedMessagesDialog()
            }
            R.string.delete_orphaned_media -> {
                showDeleteOrphanedMediaDialog()
            }
            R.string.collect_logs -> {
                val intent = Intent(this, LogActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.action_exit -> {
                showExitConfirmation()
            }
        }
    }

    private fun showFixCorruptedMessagesDialog() {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val progressDialog = AlertDialog.Builder(wrapper)
            .setTitle(R.string.fix_corrupted_messages)
            .setMessage(R.string.fixing_messages)
            .setCancelable(false)
            .create()

        progressDialog.show()

        // Run the fix in a background thread
        Thread {
            try {
                val storage = App.app.storage
                val result = storage.fixCorruptedGroupMessages()
                val fixedCount = result.first
                val scannedCount = result.second

                // Show result on UI thread
                runOnUiThread {
                    progressDialog.dismiss()

                    val message = if (fixedCount > 0) {
                        getString(R.string.messages_fixed, fixedCount, scannedCount)
                    } else {
                        getString(R.string.no_messages_to_fix)
                    }

                    AlertDialog.Builder(wrapper)
                        .setTitle(R.string.fix_corrupted_messages)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()

                    AlertDialog.Builder(wrapper)
                        .setTitle(R.string.fix_corrupted_messages)
                        .setMessage(getString(R.string.error_fixing_messages, e.message))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun showDeleteOrphanedMediaDialog() {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val progressDialog = AlertDialog.Builder(wrapper)
            .setTitle(R.string.delete_orphaned_media)
            .setMessage(R.string.scanning_media)
            .setCancelable(false)
            .create()

        progressDialog.show()

        // Run the cleanup in a background thread
        Thread {
            try {
                val storage = App.app.storage

                // 1. Get all valid filenames from database
                val validFileNames = storage.getAllMediaFileNames()

                // 2. Scan files directory and cache directory
                val filesDir = File(filesDir, "files")
                val cacheDir = File(cacheDir, "files")

                var deletedCount = 0
                var freedBytes = 0L

                // Check files in main files directory
                if (filesDir.exists() && filesDir.isDirectory) {
                    filesDir.listFiles()?.forEach { file ->
                        if (file.isFile && !validFileNames.contains(file.name)) {
                            freedBytes += file.length()
                            if (file.delete()) {
                                deletedCount++
                            }
                        }
                    }
                }

                // Check files in cache directory
                if (cacheDir.exists() && cacheDir.isDirectory) {
                    cacheDir.listFiles()?.forEach { file ->
                        if (file.isFile && !validFileNames.contains(file.name)) {
                            freedBytes += file.length()
                            if (file.delete()) {
                                deletedCount++
                            }
                        }
                    }
                }

                // Show result on UI thread
                runOnUiThread {
                    progressDialog.dismiss()

                    val message = if (deletedCount > 0) {
                        val freedSize = Formatter.formatFileSize(this, freedBytes)
                        getString(R.string.orphaned_media_deleted, deletedCount, freedSize)
                    } else {
                        getString(R.string.no_orphaned_media)
                    }

                    AlertDialog.Builder(wrapper)
                        .setTitle(R.string.delete_orphaned_media)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()

                    AlertDialog.Builder(wrapper)
                        .setTitle(R.string.delete_orphaned_media)
                        .setMessage(getString(R.string.error_deleting_orphaned_media, e.message))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }.start()
    }

    private fun showExitConfirmation() {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.exit_confirmation_title)
            .setMessage(R.string.exit_confirmation_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                exitApplication()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exitApplication() {
        // Stop the ConnectionService (triggers onDestroy cleanup)
        stopService(Intent(this, ConnectionService::class.java))

        // Close all activities in the task
        finishAffinity()

        // Force process termination to ensure complete exit
        Process.killProcess(Process.myPid())
    }
}
