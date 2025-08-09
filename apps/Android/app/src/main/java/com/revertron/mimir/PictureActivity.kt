package com.revertron.mimir

import android.os.Bundle
import com.github.chrisbanes.photoview.PhotoView

class PictureActivity: BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picture)

        if (intent == null) {
            return
        }
        if (intent.data == null) {
            return
        }

        val uri = intent.data
        findViewById<PhotoView>(R.id.picture).setImageURI(uri)
    }
}