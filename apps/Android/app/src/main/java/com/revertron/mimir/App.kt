package com.revertron.mimir

import android.app.Application
import android.os.Handler
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
        val handler = Handler(mainLooper)
        handler.postDelayed({
            val callback = NetworkStateCallback(this)
            callback.register()
        }, 15000)
    }
}