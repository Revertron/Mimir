package com.revertron.mimir

import android.app.Application
import android.os.Handler
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import com.revertron.mimir.storage.SqlStorage


class App: Application() {

    companion object {
        lateinit var app: App
    }

    var online: Boolean = false
    lateinit var storage: SqlStorage

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

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