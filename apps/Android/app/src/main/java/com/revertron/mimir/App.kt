package com.revertron.mimir

import android.app.Application
import com.revertron.mimir.storage.SqlStorage

class App: Application() {

    companion object {
        lateinit var app: App
    }

    lateinit var storage: SqlStorage

    override fun onCreate() {
        super.onCreate()
        storage = SqlStorage(this)
        storage.cleanUp()
        app = this
    }
}