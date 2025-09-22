package com.revertron.mimir

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import com.github.chrisbanes.photoview.PhotoView
import java.io.File

class PictureActivity : BaseActivity() {

    private lateinit var photoView: PhotoView
    private lateinit var toolbar: Toolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picture)

        // ---- Toolbar ----
        toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // ---- PhotoView ----
        photoView = findViewById<PhotoView>(R.id.picture)
        val uri = intent?.data
        if (uri != null) {
            photoView.setImageURI(uri)
        }

        // single-tap toggles toolbar
        photoView.setOnPhotoTapListener { _, _, _ ->
            toolbar.visibility =
                if (toolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            // optional fade animation
            toolbar.animate()
                .alpha(if (toolbar.visibility == View.VISIBLE) 1f else 0f)
                .setDuration(200)
                .start()
        }
    }

    // ---- Share menu ----
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_picture, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_share) {
            shareImage()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun shareImage() {
        intent?.data?.let { oldUri ->
                        val file = File(oldUri.path!!)

            val shareUri = FileProvider.getUriForFile(
                this,
                "${packageName}.file_provider",
                file
            )

            val share = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(oldUri) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                startActivity(Intent.createChooser(share, getString(R.string.share)))
            } catch (e: ActivityNotFoundException) {
                e.printStackTrace()
                Toast.makeText(this, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }
}