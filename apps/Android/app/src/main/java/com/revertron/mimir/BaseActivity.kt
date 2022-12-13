package com.revertron.mimir

import androidx.appcompat.app.AppCompatActivity
import com.revertron.mimir.storage.SqlStorage

open class BaseActivity: AppCompatActivity() {
    fun getStorage(): SqlStorage {
        return (application as App).storage
    }
}